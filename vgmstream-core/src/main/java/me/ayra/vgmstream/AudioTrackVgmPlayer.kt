package me.ayra.vgmstream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioTrackVgmPlayer(
    private val context: Context,
    initialSettings: VgmSettings = VgmSettings()
) : VgmPlayer {
    constructor(
        context: Context,
        loopCount: Double,
        fadeLengthMs: Long,
        loopMode: LoopMode = LoopMode.Normal
    ) : this(
        context,
        VgmSettings(
            loopCount = loopCount,
            fadeLengthMs = fadeLengthMs,
            loopMode = loopMode
        )
    )

    private var decoder: PcmDecoder? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val playing = AtomicBoolean(false)
    private val decoderLock = Any()
    private var cachedInput: File? = null

    var settings: VgmSettings = initialSettings

    override val duration: Long get() = decoder?.duration ?: 0L
    override val position: Long get() = decoder?.position ?: 0L
    override val isPlaying: Boolean get() = playing.get()

    override fun open(uri: Uri) {
        stop()
        cachedInput = copyUriToCache(uri)
        decoder = openDecoder(cachedInput!!.absolutePath)
        audioTrack = createAudioTrack(requireNotNull(decoder))
    }

    override fun play() {
        val decoder = decoder ?: return
        val track = audioTrack ?: return
        if (!playing.compareAndSet(false, true)) return

        playbackThread = thread(name = "VgmPlayer", isDaemon = true) {
            val channels = decoder.channels
            val buffer = ShortArray(2048 * channels)
            try {
                track.play()
                while (playing.get()) {
                    val frames = synchronized(decoderLock) {
                        decoder.readPcm(buffer)
                    }
                    if (frames <= 0) break
                    track.write(buffer, 0, frames * channels)
                }
            } catch (error: RuntimeException) {
                Log.e("AudioTrackVgmPlayer", "Playback failed", error)
            } finally {
                playing.set(false)
                runCatching { track.pause() }
            }
        }
    }

    override fun pause() {
        playing.set(false)
        playbackThread?.join(250)
        playbackThread = null
        audioTrack?.pause()
    }

    override fun stop() {
        pause()
        synchronized(decoderLock) {
            audioTrack?.release()
            audioTrack = null
            decoder?.close()
            decoder = null
        }
        cachedInput?.delete()
        cachedInput = null
    }

    override fun seekTo(positionMs: Long) {
        synchronized(decoderLock) {
            decoder?.seek(positionMs)
            audioTrack?.flush()
        }
    }

    private fun copyUriToCache(uri: Uri): File {
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?: "vgm"
        val file = File.createTempFile("vgmstream-", ".$extension", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open $uri" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun openDecoder(path: String): PcmDecoder =
        runCatching {
            VgmDecoderFactory.open(
                path = path,
                settings = settings
            )
        }.getOrElse { vgmstreamError ->
            if (path.endsWith(".lopus", ignoreCase = true) || path.endsWith(".opus", ignoreCase = true)) {
                runCatching {
                    val fallback = if (path.endsWith(".lopus", ignoreCase = true) || LopuPcmDecoder.canOpen(path)) {
                        LopuPcmDecoder(path, settings)
                    } else {
                        AndroidMediaPcmDecoder(path)
                    }
                    applyChannelOutput(fallback)
                }
                    .getOrElse { mediaError ->
                        Log.d("AudioTrackVgmPlayer", "Unable to open Opus file. " +
                                "vgmstream: ${vgmstreamError.message}; Android: ${mediaError.message}")
                        throw IllegalArgumentException(
                            "Unable to open Opus file. " +
                                "vgmstream: ${vgmstreamError.message}; Android: ${mediaError.message}",
                            mediaError
                        )
                    }
            } else {
                throw vgmstreamError
            }
        }

    private fun applyChannelOutput(decoder: PcmDecoder): PcmDecoder {
        val sourceChannelIndices = settings.channelOutput.sourceChannelIndices
        return if (sourceChannelIndices != null) {
            ChannelOutputPcmDecoder(decoder, sourceChannelIndices)
        } else {
            decoder
        }
    }

    private fun createAudioTrack(decoder: PcmDecoder): AudioTrack {
        val channelMask = when (decoder.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> error("Unsupported AudioTrack channel count: ${decoder.channels}")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(decoder.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val minBufferSize = AudioTrack.getMinBufferSize(
            decoder.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferSize.coerceAtLeast(decoder.sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
