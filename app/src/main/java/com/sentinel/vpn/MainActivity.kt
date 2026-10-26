package com.sentinel.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var connectBtn: Button
    private var connected = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("msg") ?: return
            runOnUiThread {
                when {
                    msg == "connected" -> setConnected(true)
                    msg == "disconnected" -> setConnected(false)
                    msg.startsWith("error") -> {
                        setConnected(false)
                        statusText.text = msg
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        connectBtn = findViewById(R.id.btnConnect ?: R.id.connectBtn)

        registerReceiver(receiver, IntentFilter("sentinel.status"), Context.RECEIVER_EXPORTED)

        connectBtn.setOnClickListener {
            if (connected) {
                startService(Intent(this, SentinelVpnService::class.java).apply {
                    action = SentinelVpnService.ACTION_DISCONNECT
                })
            } else {
                val prep = VpnService.prepare(this)
                if (prep != null) {
                    startActivityForResult(prep, 1)
                } else {
                    doConnect()
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ok = SentinelApi.checkHealth()
            runOnUiThread {
                infoText.text = if (ok) "Backend: ONLINE\nS1 S2 S3 S4 S5" else "Backend: OFFLINE\nopen Termux and run start"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) doConnect()
    }

    private fun doConnect() {
        statusText.text = getString(R.string.status_connecting)
        startService(Intent(this, SentinelVpnService::class.java).apply {
            action = SentinelVpnService.ACTION_CONNECT
        })
    }

    private fun setConnected(on: Boolean) {
        connected = on
        statusText.text = getString(if (on) R.string.status_connected else R.string.status_idle)
        connectBtn.text = getString(if (on) R.string.disconnect else R.string.connect)
        connectBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) 0xFFef4444.toInt() else 0xFF4ade80.toInt()
        )
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
