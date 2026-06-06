package me.ayra.vgmstream.sample

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import me.ayra.vgmstream.AudioTrackVgmPlayer
import me.ayra.vgmstream.ChannelOutput
import me.ayra.vgmstream.LoopMode
import me.ayra.vgmstream.VgmSettings
import java.util.Locale

class MainActivity : Activity() {
    private val player by lazy { AudioTrackVgmPlayer(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var titleText: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var loopCountSeek: SeekBar
    private lateinit var loopCountText: TextView
    private lateinit var fadeLengthSeek: SeekBar
    private lateinit var fadeLengthText: TextView
    private lateinit var fadeDelaySeek: SeekBar
    private lateinit var fadeDelayText: TextView
    private lateinit var loopModeSpinner: Spinner
    private lateinit var disableSubsongsCheck: CheckBox
    private lateinit var downmixCheck: CheckBox
    private lateinit var downmixSeek: SeekBar
    private lateinit var downmixText: TextView
    private lateinit var channelOutputText: TextView
    private lateinit var channelOutputSpinner: Spinner

    private var selectedUri: Uri? = null
    private var userSeeking = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                val duration = player.duration
                val position = player.position.coerceAtMost(duration)
                seekBar.max = duration.toSeekMax()
                seekBar.progress = position.toSeekMax()
                positionText.text = formatTime(position)
                durationText.text = formatTime(duration)
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        handler.post(progressTick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        player.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_FILE || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        loadUri(uri)
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleText = TextView(this).apply {
            text = "No file selected"
            textSize = 18f
            maxLines = 2
        }

        val openButton = Button(this).apply {
            text = "Open File"
            setOnClickListener { openFilePicker() }
        }

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        playButton = Button(this).apply {
            text = "Play"
            isEnabled = false
            setOnClickListener { player.play() }
        }
        pauseButton = Button(this).apply {
            text = "Pause"
            isEnabled = false
            setOnClickListener { player.pause() }
        }
        transport.addView(playButton, buttonParams())
        transport.addView(pauseButton, buttonParams())

        val settingsTitle = TextView(this).apply {
            text = "Settings"
            textSize = 16f
        }

        loopModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Normal", "Forever", "Ignore Loop")
            )
            setSelection(0)
        }

        loopCountText = TextView(this)
        loopCountSeek = SeekBar(this).apply {
            max = 8
            progress = 2
            setOnSeekBarChangeListener(labelListener { updateSettingsLabels() })
        }

        fadeLengthText = TextView(this)
        fadeLengthSeek = SeekBar(this).apply {
            max = 20
            progress = 0
            setOnSeekBarChangeListener(labelListener { updateSettingsLabels() })
        }

        fadeDelayText = TextView(this)
        fadeDelaySeek = SeekBar(this).apply {
            max = 20
            progress = 0
            setOnSeekBarChangeListener(labelListener { updateSettingsLabels() })
        }

        disableSubsongsCheck = CheckBox(this).apply {
            text = "Disable subsongs"
        }

        downmixCheck = CheckBox(this).apply {
            text = "Downmix"
            setOnCheckedChangeListener { _, _ -> updateSettingsLabels() }
        }
        downmixText = TextView(this)
        downmixSeek = SeekBar(this).apply {
            max = 6
            progress = 2
            setOnSeekBarChangeListener(labelListener { updateSettingsLabels() })
        }

        channelOutputText = TextView(this).apply {
            text = "Channel Output"
        }
        channelOutputSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Auto",
                    "All Channels",
                    "Channel 1",
                    "Channel 2",
                    "Channel 3",
                    "Channel 4",
                    "Stereo 1+2",
                    "Stereo 3+4"
                )
            )
            setSelection(0)
        }

        val applySettingsButton = Button(this).apply {
            text = "Apply Settings"
            setOnClickListener {
                player.settings = currentSettings()
                selectedUri?.let(::loadUri)
            }
        }

        seekBar = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionText.text = formatTime(progress.toLong())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val target = seekBar?.progress?.toLong() ?: 0L
                    player.seekTo(target)
                    userSeeking = false
                }
            })
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionText = TextView(this).apply { text = formatTime(0L) }
        durationText = TextView(this).apply {
            text = formatTime(0L)
            gravity = Gravity.END
        }
        timeRow.addView(positionText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(durationText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(titleText, matchWrapParams())
        root.addView(openButton, matchWrapParams(topMargin = 24))
        root.addView(settingsTitle, matchWrapParams(topMargin = 24))
        root.addView(loopModeSpinner, matchWrapParams(topMargin = 8))
        root.addView(loopCountText, matchWrapParams(topMargin = 8))
        root.addView(loopCountSeek, matchWrapParams())
        root.addView(fadeLengthText, matchWrapParams(topMargin = 8))
        root.addView(fadeLengthSeek, matchWrapParams())
        root.addView(fadeDelayText, matchWrapParams(topMargin = 8))
        root.addView(fadeDelaySeek, matchWrapParams())
        root.addView(disableSubsongsCheck, matchWrapParams(topMargin = 8))
        root.addView(downmixCheck, matchWrapParams())
        root.addView(downmixText, matchWrapParams())
        root.addView(downmixSeek, matchWrapParams())
        root.addView(channelOutputText, matchWrapParams(topMargin = 8))
        root.addView(channelOutputSpinner, matchWrapParams())
        root.addView(applySettingsButton, matchWrapParams(topMargin = 8))
        root.addView(transport, matchWrapParams(topMargin = 24))
        root.addView(seekBar, matchWrapParams(topMargin = 24))
        root.addView(timeRow, matchWrapParams(topMargin = 8))

        updateSettingsLabels()
        return root
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_FILE)
    }

    private fun loadUri(uri: Uri) {
        runCatching {
            player.settings = currentSettings()
            player.open(uri)
        }.onSuccess {
            selectedUri = uri
            titleText.text = uri.lastPathSegment ?: uri.toString()
            playButton.isEnabled = true
            pauseButton.isEnabled = true
            seekBar.max = player.duration.toSeekMax()
            durationText.text = formatTime(player.duration)
            positionText.text = formatTime(0L)
        }.onFailure { error ->
            selectedUri = null
            playButton.isEnabled = false
            pauseButton.isEnabled = false
            Toast.makeText(this, error.message ?: "Unable to open file", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentSettings(): VgmSettings =
        VgmSettings(
            loopCount = loopCountValue(),
            fadeLengthMs = fadeLengthSeek.progress * 1000L,
            loopMode = when (loopModeSpinner.selectedItemPosition) {
                1 -> LoopMode.Forever
                2 -> LoopMode.IgnoreLoop
                else -> LoopMode.Normal
            },
            fadeDelayMs = fadeDelaySeek.progress * 1000L,
            disableSubsongs = disableSubsongsCheck.isChecked,
            downmixChannels = if (downmixCheck.isChecked) downmixSeek.progress.coerceAtLeast(1) else 0,
            channelOutput = when (channelOutputSpinner.selectedItemPosition) {
                1 -> ChannelOutput.AllChannels
                2 -> ChannelOutput.Channel1
                3 -> ChannelOutput.Channel2
                4 -> ChannelOutput.Channel3
                5 -> ChannelOutput.Channel4
                6 -> ChannelOutput.Stereo12
                7 -> ChannelOutput.Stereo34
                else -> ChannelOutput.Auto
            }
        )

    private fun loopCountValue(): Double = loopCountSeek.progress * 0.5

    private fun updateSettingsLabels() {
        loopCountText.text = String.format(Locale.US, "Loop Count: %.1f", loopCountValue())
        fadeLengthText.text = "Fade Length: ${fadeLengthSeek.progress}s"
        fadeDelayText.text = "Fade Delay: ${fadeDelaySeek.progress}s"
        val downmixChannels = downmixSeek.progress.coerceAtLeast(1)
        downmixText.text = if (downmixCheck.isChecked) {
            "Downmix Channels: $downmixChannels"
        } else {
            "Downmix Channels: Off"
        }
        downmixSeek.isEnabled = downmixCheck.isChecked
    }

    private fun labelListener(onChange: () -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 8
            marginEnd = 8
        }

    private fun matchWrapParams(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
        }

    private fun Long.toSeekMax(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    companion object {
        private const val REQUEST_OPEN_FILE = 100
    }
}
