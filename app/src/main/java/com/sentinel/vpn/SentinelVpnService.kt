package com.sentinel.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

class SentinelVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tun2socks: Process? = null
    private var logThread: Thread? = null
    private val SOCKS_PORT = 8080  // FIX 1: was 1080, S2 listens on 8080

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

    private fun resolveBinary(): File? {
        // FIX 2: Android 10+ needs binary from nativeLibraryDir, NOT filesDir
        val libDir = applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(libDir, "libhev_socks5_tunnel.so"),
            File(libDir, "libhev-socks5-tunnel.so"),
            File(libDir, "hev-socks5-tunnel")
        )
        for (f in candidates) {
            if (f.exists()) {
                try { f.setExecutable(true, false) } catch (_: Exception) {}
                return f
            }
        }
        return null
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

            // FIX 3: keep Termux disallowed so its own traffic doesn't loop through TUN.
            // Loopback 127.0.0.1 between app and S5 still works (kernel routes it via lo).
            try { builder.addDisallowedApplication("com.termux") } catch (_: Exception) {}

            tun = builder.establish() ?: run { broadcast("error: tun establish"); return }

            val bin = resolveBinary()
            if (bin == null) {
                broadcast("error: binary missing")
                stopVpn()
                return
            }

            val yaml = File(filesDir, "hev.yml")
            FileOutputStream(yaml).use {
                it.write("""
tunnel:
  mtu: 1500
socks5:
  port: $SOCKS_PORT
  address: 127.0.0.1
  udp: udp
misc:
  log-file: stderr
  log-level: warn
""".trimIndent().toByteArray())
            }

            val pb = ProcessBuilder(bin.absolutePath, "-c", yaml.absolutePath, "-f", "/proc/self/fd/${tun!!.fd}")
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            tun2socks = pb.start()

            logThread = Thread {
                try {
                    val r = BufferedReader(InputStreamReader(tun2socks!!.inputStream))
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        line?.let { if (it.contains("error")) broadcast("error: $it") }
                    }
                } catch (_: Exception) {}
            }.apply { start() }

            broadcast("connected")
        } catch (e: Exception) {
            broadcast("error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        try { logThread?.interrupt() } catch (_: Exception) {}
        try { tun2socks?.destroy() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        logThread = null
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

    override fun onRevoke() { stopVpn(); super.onRevoke() }
}
