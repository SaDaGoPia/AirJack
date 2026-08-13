package com.ace4.airplayreceiver

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.ace4.airplayreceiver.raop.NowPlayingInfo

class MainActivity : Activity() {

    private lateinit var editDeviceName: EditText
    private lateinit var labelDeviceName: TextView
    private lateinit var textStatus: TextView
    private lateinit var statusDial: View
    private lateinit var dialLabel: TextView
    private lateinit var imageArtwork: ImageView
    private lateinit var imageArtworkGlyph: ImageView
    private lateinit var textTitle: TextView
    private lateinit var textArtist: TextView
    private var isRunning = false
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editDeviceName = findViewById(R.id.edit_device_name)
        labelDeviceName = findViewById(R.id.label_device_name)
        textStatus = findViewById(R.id.text_status)
        statusDial = findViewById(R.id.status_dial)
        dialLabel = findViewById(R.id.dial_label)
        imageArtwork = findViewById(R.id.image_artwork)
        imageArtworkGlyph = findViewById(R.id.image_artwork_glyph)
        textTitle = findViewById(R.id.text_title)
        textArtist = findViewById(R.id.text_artist)

        editDeviceName.setText(DeviceIdentity.getDeviceName(this))
        StatusBus.listener = { status -> onStatusChanged(status) }
        NowPlayingBus.listener = { info -> onNowPlayingChanged(info) }
        onStatusChanged(StatusBus.currentStatus)

        statusDial.setOnClickListener {
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
        dialLabel.text = getString(if (isRunning) R.string.btn_stop else R.string.btn_start)
        textStatus.text = getString(
            when (status) {
                AirplayAdvertiseService.STATUS_STARTING -> R.string.status_starting
                AirplayAdvertiseService.STATUS_ADVERTISING -> R.string.status_advertising
                AirplayAdvertiseService.STATUS_ERROR -> R.string.status_error
                AirplayAdvertiseService.STATUS_RECONNECTING -> R.string.status_reconnecting
                else -> R.string.status_stopped
            }
        )
        statusDial.setBackgroundResource(
            when (status) {
                AirplayAdvertiseService.STATUS_STARTING -> R.drawable.dial_starting
                AirplayAdvertiseService.STATUS_ADVERTISING -> R.drawable.dial_advertising
                AirplayAdvertiseService.STATUS_ERROR -> R.drawable.dial_error
                AirplayAdvertiseService.STATUS_RECONNECTING -> R.drawable.dial_reconnecting
                else -> R.drawable.dial_stopped
            }
        )
        setDialPulsing(
            status == AirplayAdvertiseService.STATUS_STARTING ||
                status == AirplayAdvertiseService.STATUS_RECONNECTING
        )

        editDeviceName.isEnabled = !isRunning
        editDeviceName.alpha = if (isRunning) 0.5f else 1f
        labelDeviceName.text = getString(
            if (isRunning) R.string.label_device_name_locked else R.string.label_device_name
        )
        if (!isRunning) onNowPlayingChanged(NowPlayingInfo())
    }

    private fun setDialPulsing(pulsing: Boolean) {
        if (pulsing) {
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofFloat(statusDial, "alpha", 1f, 0.5f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            statusDial.alpha = 1f
        }
    }

    private fun onNowPlayingChanged(info: NowPlayingInfo) {
        if (info.isEmpty) {
            textTitle.visibility = View.GONE
            textArtist.visibility = View.GONE
            imageArtwork.setImageDrawable(null)
            imageArtworkGlyph.visibility = View.VISIBLE
        } else {
            textTitle.text = info.title
            textTitle.visibility = if (info.title.isNullOrBlank()) View.GONE else View.VISIBLE
            val subtitle = listOfNotNull(info.artist, info.album).joinToString(" — ")
            textArtist.text = subtitle
            textArtist.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
            imageArtwork.setImageBitmap(info.artwork)
            imageArtworkGlyph.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        StatusBus.listener = null
        NowPlayingBus.listener = null
        super.onDestroy()
    }
}
