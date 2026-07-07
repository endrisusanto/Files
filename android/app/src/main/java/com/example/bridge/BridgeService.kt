package com.example.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.BufferedInputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.util.EnumSet

class BridgeService : Service() {
    private val tag = "Bridge"
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }

    companion object {
        const val SMB_HOST = "192.168.10.221"
        const val SMB_SHARE = "sambashare"

        @Volatile var currentFile: String = ""
        @Volatile var currentProgress: Int = 0
        @Volatile var queueSuccess: Int = 0
        @Volatile var queueTotal: Int = 0

        fun host(context: Context) = context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("smb_host", SMB_HOST) ?: SMB_HOST
        fun share(context: Context) = context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("smb_share", SMB_SHARE) ?: SMB_SHARE
        fun target(context: Context) = "smb://${host(context)}/${share(context)}/"
        fun saveTarget(context: Context, host: String, share: String) {
            context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putString("smb_host", host.trim())
                .putString("smb_share", share.trim().trim('/'))
                .apply()
        }

        fun checkSamba(context: Context) {
            SMBClient().use { client ->
                client.connect(host(context)).use { connection ->
                    connection.authenticate(guestAuth()).connectShare(share(context)).use {}
                }
            }
        }

        fun uploadTestFile(context: Context) {
            SMBClient().use { client ->
                client.connect(host(context)).use { connection ->
                    connection.authenticate(guestAuth()).connectShare(share(context)).use { smbShare ->
                        val disk = smbShare as DiskShare
                        disk.openFile(
                            "test.txt",
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY)
                        ).use { remote ->
                            ByteArrayInputStream("bridge test\n".toByteArray()).use { input ->
                                remote.outputStream.use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
            }
        }

        private fun guestAuth() = AuthenticationContext("guest", CharArray(0), null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "BridgeService start startId=$startId")
        startForeground(1, notification("Uploading to Samba"))
        Thread {
            val qTotal = intent?.getIntExtra("queue_total", 0) ?: 0
            val qSuccess = intent?.getIntExtra("queue_success", 0) ?: 0
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:upload")
                .apply { acquire(2 * 60 * 60 * 1000L) }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bridge:wifi")
                .apply { acquire() }
            try {
                val name = intent?.getStringExtra("file")
                val filesToUpload = if (name != null) {
                    listOf(File(localDir, name))
                } else {
                    localDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".md5") }
                        ?.sortedBy { it.lastModified() }
                        ?: emptyList()
                }
                if (qTotal > 0) {
                    queueTotal = qTotal
                    queueSuccess = qSuccess
                } else {
                    queueTotal = filesToUpload.size
                    queueSuccess = 0
                }
                for (file in filesToUpload) {
                    Log.i(tag, "Upload worker started file=${file.absolutePath}")
                    retry(3) { upload(file) }
                    if (!file.delete()) throw IllegalStateException("uploaded but failed to delete ${file.absolutePath}")
                    Log.i(tag, "Upload done and local file deleted: ${file.name}")
                    queueSuccess += 1
                }
            } catch (t: Throwable) {
                Log.e(tag, "Upload failed", t)
                getSystemService(NotificationManager::class.java).notify(2, notification("Upload failed: ${t.message}"))
            } finally {
                currentFile = ""
                currentProgress = 0
                if (localDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md5") }?.isEmpty() == true) {
                    queueTotal = 0
                    queueSuccess = 0
                }

                if (wifi.isHeld) wifi.release()
                if (wake.isHeld) wake.release()
                Log.i(tag, "BridgeService stop startId=$startId")
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun upload(file: File) {
        Log.i(tag, "SMB upload start file=${file.name} target=${target(this)}")
        localDir.mkdirs()
        require(file.isFile) { "missing file: ${file.absolutePath}" }
        
        currentFile = file.name
        currentProgress = 0
        val fileSize = file.length()

        SMBClient().use { client ->
            client.connect(host(this)).use { connection ->
                connection.authenticate(guestAuth()).connectShare(share(this)).use { smbShare ->
                    val disk = smbShare as DiskShare
                    disk.openFile(
                        file.name,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY)
                    ).use { remote ->
                        BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
                            remote.outputStream.use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesCopied: Long = 0
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    bytesCopied += bytes
                                    if (fileSize > 0) {
                                        currentProgress = ((bytesCopied * 100) / fileSize).toInt()
                                    }
                                    bytes = input.read(buffer)
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.i(tag, "SMB upload complete file=${file.name}")
    }

    private fun retry(times: Int, block: () -> Unit) {
        var last: Throwable? = null
        repeat(times) { attempt ->
            try {
                block()
                return
            } catch (t: Throwable) {
                last = t
                Log.e(tag, "Retry ${attempt + 1}/$times failed", t)
                Thread.sleep((attempt + 1) * 5_000L)
            }
        }
        throw last ?: IllegalStateException("upload failed")
    }

    private fun notification(text: String): Notification {
        val channel = "bridge"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channel, "Bridge Upload", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel) else Notification.Builder(this)
        return builder
            .setContentTitle("Android File Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
    }
}
