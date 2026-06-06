package me.ayra.vgmstream

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

internal class AndroidMediaPcmDecoder(
    private val path: String
) : PcmDecoder {
    private var extractor: MediaExtractor = MediaExtractor()
    private var codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    private var inputDone = false
    private var outputDone = false
    private var released = false
    private var pendingOutputIndex = -1
    private var pendingOutputOffset = 0
    private var pendingOutputSize = 0
    private var currentPositionUs = 0L

    override var duration: Long = 0L
        private set
    override var sampleRate: Int = 0
        private set
    override var channels: Int = 0
        private set

    override val position: Long
        get() = currentPositionUs / 1000L

    init {
        require(File(path).isFile) { "file does not exist: $path" }
        val track = selectAudioTrack(extractor, path)
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) {
            "missing audio mime"
        }
        require(mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            "Android media fallback only supports Opus, got $mime"
        }

        duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / 1000L
        } else {
            0L
        }
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun readPcm(buffer: ShortArray, requestedFrames: Int): Int {
        check(!released) { "decoder is closed" }
        if (requestedFrames <= 0 || channels <= 0 || outputDone) return 0

        val maxShorts = minOf(buffer.size, requestedFrames * channels)
        var copiedShorts = 0

        while (copiedShorts < maxShorts && !outputDone) {
            if (pendingOutputIndex >= 0) {
                copiedShorts += copyPendingOutput(buffer, copiedShorts, maxShorts - copiedShorts)
                continue
            }

            feedInput()
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        pendingOutputIndex = outputIndex
                        pendingOutputOffset = bufferInfo.offset
                        pendingOutputSize = bufferInfo.size
                        currentPositionUs = bufferInfo.presentationTimeUs
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && copiedShorts > 0) {
                break
            }
        }

        return copiedShorts / channels
    }

    override fun seek(positionMs: Long) {
        check(!released) { "decoder is closed" }
        if (pendingOutputIndex >= 0) {
            codec.releaseOutputBuffer(pendingOutputIndex, false)
            pendingOutputIndex = -1
        }
        extractor.seekTo(positionMs.coerceAtLeast(0L) * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        pendingOutputOffset = 0
        pendingOutputSize = 0
        currentPositionUs = positionMs.coerceAtLeast(0L) * 1000L
    }

    override fun close() {
        if (released) return
        released = true
        if (pendingOutputIndex >= 0) {
            runCatching { codec.releaseOutputBuffer(pendingOutputIndex, false) }
            pendingOutputIndex = -1
        }
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private fun feedInput() {
        if (inputDone) return
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
            inputDone = true
            return
        }

        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            inputDone = true
        } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun copyPendingOutput(buffer: ShortArray, outputOffset: Int, maxShorts: Int): Int {
        val outputBuffer = codec.getOutputBuffer(pendingOutputIndex) ?: return 0
        outputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.position(pendingOutputOffset)
        outputBuffer.limit(pendingOutputOffset + pendingOutputSize)

        val shortsToCopy = minOf(maxShorts, pendingOutputSize / BYTES_PER_SHORT)
        val shortBuffer = outputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        shortBuffer.get(buffer, outputOffset, shortsToCopy)

        val bytesCopied = shortsToCopy * BYTES_PER_SHORT
        pendingOutputOffset += bytesCopied
        pendingOutputSize -= bytesCopied
        if (pendingOutputSize <= 0) {
            codec.releaseOutputBuffer(pendingOutputIndex, false)
            pendingOutputIndex = -1
            pendingOutputOffset = 0
        }
        return shortsToCopy
    }

    private fun selectAudioTrack(extractor: MediaExtractor, path: String): Int {
        extractor.setDataSource(path)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                return i
            }
        }
        throw IllegalArgumentException("no audio track found")
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val BYTES_PER_SHORT = 2
    }
}
