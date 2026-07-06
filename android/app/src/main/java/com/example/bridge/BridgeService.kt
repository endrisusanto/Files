package com.example.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.hierynomus.smbj.auth.AuthenticationContext.anonymous
import com.hierynomus.smbj.share.DiskShare
import java.io.BufferedInputStream
import java.io.File
import java.util.EnumSet

class BridgeService : Service() {
    private val tag = "Bridge"
    private val localDir by lazy { File(getExternalFilesDir(null), "SUBRO") }

    companion object {
        const val SMB_HOST = "192.168.10.221"
        const val SMB_SHARE = "sambashare"
        const val TARGET = "smb://192.168.10.221/sambashare/"

        fun checkSamba() {
            SMBClient().use { client ->
                client.connect(SMB_HOST).use { connection ->
                    connection.authenticate(anonymous()).connectShare(SMB_SHARE).use {}
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "BridgeService start startId=$startId")
        startForeground(1, notification("Uploading to Samba"))
        Thread {
            val name = intent?.getStringExtra("file") ?: return@Thread run {
                Log.e(tag, "BridgeService missing file extra")
                stopSelf(startId)
            }
            val file = File(localDir, name)
            Log.i(tag, "Upload worker started file=${file.absolutePath}")
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:upload")
                .apply { acquire(2 * 60 * 60 * 1000L) }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bridge:wifi")
                .apply { acquire() }
            try {
                retry(3) { upload(file) }
                if (!file.delete()) throw IllegalStateException("uploaded but failed to delete ${file.absolutePath}")
                Log.i(tag, "Upload done and local file deleted: ${file.name}")
            } catch (t: Throwable) {
                Log.e(tag, "Upload failed: ${file.absolutePath}", t)
                getSystemService(NotificationManager::class.java).notify(2, notification("Upload failed: ${t.message}"))
            } finally {
                if (wifi.isHeld) wifi.release()
                if (wake.isHeld) wake.release()
                Log.i(tag, "BridgeService stop startId=$startId")
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun upload(file: File) {
        Log.i(tag, "SMB upload start file=${file.name} target=$TARGET")
        localDir.mkdirs()
        require(file.isFile) { "missing file: ${file.absolutePath}" }
        SMBClient().use { client ->
            client.connect(SMB_HOST).use { connection ->
                connection.authenticate(anonymous()).connectShare(SMB_SHARE).use { share ->
                    val disk = share as DiskShare
                    disk.openFile(
                        file.name,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY)
                    ).use { remote ->
                        BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
                            remote.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
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
