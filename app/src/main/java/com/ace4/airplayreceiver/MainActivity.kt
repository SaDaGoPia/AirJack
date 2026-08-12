package com.ace4.airplayreceiver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.ace4.airplayreceiver.raop.NowPlayingInfo

class MainActivity : Activity() {

    private lateinit var editDeviceName: EditText
    private lateinit var textStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var imageArtwork: ImageView
    private lateinit var textTitle: TextView
    private lateinit var textArtist: TextView
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editDeviceName = findViewById(R.id.edit_device_name)
        textStatus = findViewById(R.id.text_status)
        btnToggle = findViewById(R.id.btn_toggle)
        imageArtwork = findViewById(R.id.image_artwork)
        textTitle = findViewById(R.id.text_title)
        textArtist = findViewById(R.id.text_artist)

        editDeviceName.setText(DeviceIdentity.getDeviceName(this))
        StatusBus.listener = { status -> onStatusChanged(status) }
        NowPlayingBus.listener = { info -> onNowPlayingChanged(info) }

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
            status == AirplayAdvertiseService.STATUS_ADVERTISING ||
            status == AirplayAdvertiseService.STATUS_RECONNECTING
        btnToggle.text = getString(if (isRunning) R.string.btn_stop else R.string.btn_start)
        textStatus.text = getString(
            when (status) {
                AirplayAdvertiseService.STATUS_STARTING -> R.string.status_starting
                AirplayAdvertiseService.STATUS_ADVERTISING -> R.string.status_advertising
                AirplayAdvertiseService.STATUS_ERROR -> R.string.status_error
                AirplayAdvertiseService.STATUS_RECONNECTING -> R.string.status_reconnecting
                else -> R.string.status_stopped
            }
        )
        if (!isRunning) onNowPlayingChanged(NowPlayingInfo())
    }

    private fun onNowPlayingChanged(info: NowPlayingInfo) {
        if (info.isEmpty) {
            textTitle.visibility = View.GONE
            textArtist.visibility = View.GONE
            imageArtwork.setImageDrawable(null)
        } else {
            textTitle.text = info.title
            textTitle.visibility = if (info.title.isNullOrBlank()) View.GONE else View.VISIBLE
            val subtitle = listOfNotNull(info.artist, info.album).joinToString(" — ")
            textArtist.text = subtitle
            textArtist.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
            imageArtwork.setImageBitmap(info.artwork)
        }
    }

    override fun onDestroy() {
        StatusBus.listener = null
        NowPlayingBus.listener = null
        super.onDestroy()
    }
}
