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
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.BufferedInputStream
import java.io.File
import java.util.EnumSet

class BridgeService : Service() {
    private val localDir = File("/sdcard/Download/SUBRO")
    private val smbHost = "192.168.10.221"
    private val smbShare = "sambashare"
    private val smbUser = "bridge"
    private val smbPass = "CHANGE_ME"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Uploading to Samba"))
        Thread {
            val name = intent?.getStringExtra("file") ?: return@Thread stopSelf(startId)
            val file = File(localDir, name)
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:upload")
                .apply { acquire(2 * 60 * 60 * 1000L) }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bridge:wifi")
                .apply { acquire() }
            try {
                retry(3) { upload(file) }
                if (!file.delete()) throw IllegalStateException("uploaded but failed to delete ${file.absolutePath}")
            } finally {
                if (wifi.isHeld) wifi.release()
                if (wake.isHeld) wake.release()
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun upload(file: File) {
        require(file.isFile) { "missing file: ${file.absolutePath}" }
        SMBClient().use { client ->
            client.connect(smbHost).use { connection ->
                val auth = AuthenticationContext(smbUser, smbPass.toCharArray(), "")
                connection.authenticate(auth).connectShare(smbShare).use { share ->
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
    }

    private fun retry(times: Int, block: () -> Unit) {
        var last: Throwable? = null
        repeat(times) { attempt ->
            try {
                block()
                return
            } catch (t: Throwable) {
                last = t
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
