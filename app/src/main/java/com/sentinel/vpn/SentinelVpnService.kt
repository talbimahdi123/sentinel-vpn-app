package com.sentinel.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress

class SentinelVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tun2socks: Process? = null
    private val SOCKS_PORT = 1080

    companion object {
        const val ACTION_CONNECT = "com.sentinel.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.sentinel.vpn.DISCONNECT"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "sentinel_vpn"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startVpn()
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val session = SentinelApi.fetchSession("android_" + System.currentTimeMillis(), "player")
                ?: run { broadcast("error: S5 unreachable"); return }

            createNotificationChannel()
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sentinel Active")
                .setContentText("${session.model} · ${session.mac}")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notif)

            val builder = Builder()
                .setSession("Sentinel")
                .setMtu(1500)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            // don't route Termux (servers run there)
            try { builder.addDisallowedApplication("com.termux") } catch (_: Exception) {}

            tun = builder.establish() ?: run { broadcast("error: tun establish"); return }

            val bin = File(filesDir, "hev-socks5-tunnel")
            val yaml = File(filesDir, "hev.yml")

            FileOutputStream(yaml).use {
                it.write("""
tunnel:
  mtu: 1500
socks5:
  port: $SOCKS_PORT
  address: 127.0.0.1
  udp: 'udp'
misc:
  task-stack-size: 20480
  connect-timeout: 5000
  read-write-timeout: 60000
  log-file: stderr
  log-level: warn
""".trimIndent().toByteArray())
            }

            bin.setExecutable(true)

            // start local socat-style tunnel: we run hev-socks5-tunnel reading from the tun fd
            val pb = ProcessBuilder(
                bin.absolutePath, "-c", yaml.absolutePath, "-f", "/proc/self/fd/${tun!!.fd}"
            )
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = filesDir.absolutePath
            tun2socks = pb.start()

            broadcast("connected")
        } catch (e: Exception) {
            broadcast("error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        try { tun2socks?.destroy() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun2socks = null
        tun = null
        stopForeground(true)
        stopSelf()
        broadcast("disconnected")
    }

    private fun broadcast(msg: String) {
        sendBroadcast(Intent("sentinel.status").putExtra("msg", msg))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sentinel VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
