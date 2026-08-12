package com.ace4.airplayreceiver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var editDeviceName: EditText
    private lateinit var textStatus: TextView
    private lateinit var btnToggle: Button
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editDeviceName = findViewById(R.id.edit_device_name)
        textStatus = findViewById(R.id.text_status)
        btnToggle = findViewById(R.id.btn_toggle)

        editDeviceName.setText(DeviceIdentity.getDeviceName(this))
        StatusBus.listener = { status -> onStatusChanged(status) }

        btnToggle.setOnClickListener {
            if (isRunning) {
                stopService(Intent(this, AirplayAdvertiseService::class.java))
            } else {
                val name = editDeviceName.text.toString().ifBlank {
                    getString(R.string.default_device_name)
                }
                DeviceIdentity.setDeviceName(this, name)
                val intent = Intent(this, AirplayAdvertiseService::class.java)
                intent.putExtra(AirplayAdvertiseService.EXTRA_DEVICE_NAME, name)
                startService(intent)
            }
        }
    }

    private fun onStatusChanged(status: Int) {
        isRunning = status == AirplayAdvertiseService.STATUS_STARTING ||
            status == AirplayAdvertiseService.STATUS_ADVERTISING
        btnToggle.text = getString(if (isRunning) R.string.btn_stop else R.string.btn_start)
        textStatus.text = getString(
            when (status) {
                AirplayAdvertiseService.STATUS_STARTING -> R.string.status_starting
                AirplayAdvertiseService.STATUS_ADVERTISING -> R.string.status_advertising
                AirplayAdvertiseService.STATUS_ERROR -> R.string.status_error
                else -> R.string.status_stopped
            }
        )
    }

    override fun onDestroy() {
        StatusBus.listener = null
        super.onDestroy()
    }
}
