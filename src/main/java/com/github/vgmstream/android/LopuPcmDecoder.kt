package com.github.vgmstream.android

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class LopuPcmDecoder(path: String) : PcmDecoder {
    private val file = RandomAccessFile(File(path), "r")
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val startOffset: Long
    private val dataEndOffset: Long
    private val preSkipSamples: Int
    private val totalSamples: Long

    private var nextPacketOffset: Long
    private var queuedSamples = 0L
    private var currentPositionUs = 0L
    private var inputDone = false
    private var outputDone = false
    private var released = false
    private var pendingOutputIndex = -1
    private var pendingOutputOffset = 0
    private var pendingOutputSize = 0

    override val duration: Long
    override val sampleRate: Int
    override val channels: Int
    override val position: Long get() = currentPositionUs / 1000L

    init {
        require(file.length() >= HEADER_SIZE) { "LOPU file is too small" }

        val header = parseHeader(file)
        startOffset = header.startOffset
        sampleRate = header.sampleRate
        channels = header.channels
        preSkipSamples = header.preSkipSamples
        dataEndOffset = (header.startOffset + header.dataSize).coerceAtMost(file.length())
        nextPacketOffset = startOffset
        totalSamples = header.numSamples.coerceAtLeast(0).toLong()
        duration = samplesToMs(totalSamples, sampleRate)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setLong(MediaFormat.KEY_DURATION, duration * 1000L)
        opusInitializationData(channels, preSkipSamples, sampleRate).forEachIndexed { index, data ->
            format.setByteBuffer("csd-$index", ByteBuffer.wrap(data))
        }

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
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
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
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

        val targetSamples = msToSamples(positionMs.coerceIn(0L, duration), sampleRate).coerceAtMost(totalSamples)
        resetCodecState()
        while (nextPacketOffset < dataEndOffset && queuedSamples < targetSamples) {
            val packet = readPacketAt(nextPacketOffset) ?: break
            nextPacketOffset = packet.nextOffset
            queuedSamples += packet.sampleCount
        }
        currentPositionUs = samplesToUs(queuedSamples, sampleRate)
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
        file.close()
    }

    private fun resetCodecState() {
        codec.flush()
        nextPacketOffset = startOffset
        queuedSamples = 0L
        inputDone = false
        outputDone = false
        pendingOutputOffset = 0
        pendingOutputSize = 0
    }

    private fun feedInput() {
        if (inputDone) return
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex)
        val packet = readPacketAt(nextPacketOffset)
        if (inputBuffer == null || packet == null) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                samplesToUs(queuedSamples, sampleRate),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            inputDone = true
            return
        }

        inputBuffer.clear()
        require(packet.payload.size <= inputBuffer.capacity()) {
            "LOPU Opus packet too large: ${packet.payload.size}"
        }
        inputBuffer.put(packet.payload)
        codec.queueInputBuffer(inputIndex, 0, packet.payload.size, samplesToUs(queuedSamples, sampleRate), 0)

        nextPacketOffset = packet.nextOffset
        queuedSamples += packet.sampleCount
    }

    private fun readPacketAt(offset: Long): Packet? {
        if (offset + SWITCH_PACKET_HEADER_SIZE > dataEndOffset) return null

        val packetSize = file.readUInt32BE(offset)
        if (packetSize <= 0 || packetSize > MAX_PACKET_SIZE) return null

        val payloadOffset = offset + SWITCH_PACKET_HEADER_SIZE
        val nextOffset = payloadOffset + packetSize
        if (nextOffset > dataEndOffset) return null

        val payload = ByteArray(packetSize.toInt())
        file.seek(payloadOffset)
        file.readFully(payload)
        return Packet(payload, opusPacketSampleCount(payload), nextOffset)
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

    private data class Packet(
        val payload: ByteArray,
        val sampleCount: Int,
        val nextOffset: Long
    )

    private data class ParsedHeader(
        val startOffset: Long,
        val dataSize: Long,
        val sampleRate: Int,
        val channels: Int,
        val preSkipSamples: Int,
        val numSamples: Int
    )

    companion object {
        private const val HEADER_SIZE = 0x2C
        private const val SWITCH_BASIC_INFO = 0x8000_0001L
        private const val SWITCH_CONTEXT_INFO = 0x8000_0003L
        private const val SWITCH_DATA_INFO = 0x8000_0004L
        private const val SWITCH_PACKET_HEADER_SIZE = 0x08
        private const val MAX_PACKET_SIZE = 0x2000
        private const val MAX_HEADER_SCAN_BYTES = 0x4000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val BYTES_PER_SHORT = 2
        private const val OPUS_SAMPLE_RATE = 48_000
        private const val DEFAULT_SEEK_PRE_ROLL_SAMPLES = 3840

        fun canOpen(path: String): Boolean =
            runCatching {
                RandomAccessFile(File(path), "r").use {
                    it.length() >= 4L && runCatching { parseHeader(it) }.isSuccess
                }
            }.getOrDefault(false)

        private fun parseHeader(file: RandomAccessFile): ParsedHeader {
            if (file.readAscii(0, 4) == "LOPU") {
                val startOffset = file.readUInt32LE(0x04)
                val sampleRate = file.readInt32LE(0x08)
                val channels = file.readUInt16LE(0x0C)
                val numSamples = file.readInt32LE(0x14)
                val loopEnd = file.readInt32LE(0x1C) + 1
                val preSkip = file.readUInt16LE(0x24)
                val dataSize = file.readUInt32LE(0x28)

                val adjustedSamples = (numSamples - preSkip).coerceAtLeast(loopEnd).coerceAtLeast(0)
                return ParsedHeader(
                    startOffset = startOffset,
                    dataSize = dataSize,
                    sampleRate = sampleRate,
                    channels = channels,
                    preSkipSamples = preSkip,
                    numSamples = adjustedSamples
                )
            }

            if (file.hasSwitchOpusAt(0x00L)) {
                return parseSwitchOpusHeader(file, 0x00L, 0, 0, 0)
            }

            if (file.hasNippon1SwitchOpus()) {
                return parseSwitchOpusHeader(
                    file = file,
                    baseOffset = 0x10L,
                    fallbackNumSamples = 0,
                    fallbackLoopStart = file.readInt32LE(0x00L),
                    fallbackLoopEnd = file.readInt32LE(0x08L)
                )
            }

            if (file.hasCapcomSwitchOpus()) {
                val channels = file.readInt32LE(0x04L)
                require(channels == 1 || channels == 2) {
                    "Capcom multistream Opus is not supported by Android fallback"
                }
                return parseSwitchOpusHeader(
                    file = file,
                    baseOffset = file.readUInt32LE(0x1CL),
                    fallbackNumSamples = file.readInt32LE(0x00L),
                    fallbackLoopStart = file.readInt32LE(0x08L),
                    fallbackLoopEnd = file.readInt32LE(0x0CL)
                )
            }

            val scannedOffset = file.findSwitchOpusOffset()
            if (scannedOffset >= 0) {
                return parseSwitchOpusHeader(file, scannedOffset, 0, 0, 0)
            }

            error("missing supported Opus header")
        }

        private fun parseSwitchOpusHeader(
            file: RandomAccessFile,
            baseOffset: Long,
            fallbackNumSamples: Int,
            fallbackLoopStart: Int,
            fallbackLoopEnd: Int
        ): ParsedHeader {
            require(file.hasSwitchOpusAt(baseOffset)) { "missing supported Opus header" }

            val channels = file.readUnsignedByteAt(baseOffset + 0x09L)
            val rawSampleRate = file.readInt32LE(baseOffset + 0x0CL)
            val sampleRate = if (rawSampleRate == OPUS_SAMPLE_RATE) rawSampleRate else OPUS_SAMPLE_RATE
            val dataInfoOffset = baseOffset + file.readUInt32LE(baseOffset + 0x10L)
            val contextOffset = file.readUInt32LE(baseOffset + 0x18L)
            val preSkip = file.readUInt16LE(baseOffset + 0x1CL)

            var numSamples = fallbackNumSamples
            if (contextOffset > 0) {
                val absoluteContextOffset = baseOffset + contextOffset
                if (file.readUInt32LE(absoluteContextOffset) == SWITCH_CONTEXT_INFO) {
                    numSamples = file.readInt32LE(absoluteContextOffset + 0x0CL)
                }
            }

            require(file.readUInt32LE(dataInfoOffset) == SWITCH_DATA_INFO) { "missing Opus data chunk" }
            val dataSize = file.readUInt32LE(dataInfoOffset + 0x04L)
            val startOffset = dataInfoOffset + 0x08L
            val countedSamples = (countPacketSamples(file, startOffset, dataSize) - preSkip).coerceAtLeast(0)
            if (countedSamples > numSamples) {
                numSamples = countedSamples
            }

            return ParsedHeader(
                startOffset = startOffset,
                dataSize = dataSize,
                sampleRate = sampleRate,
                channels = channels,
                preSkipSamples = preSkip,
                numSamples = numSamples
            )
        }

        private fun RandomAccessFile.hasSwitchOpusAt(offset: Long): Boolean =
            length() >= offset + HEADER_SIZE && readUInt32LE(offset) == SWITCH_BASIC_INFO

        private fun RandomAccessFile.hasNippon1SwitchOpus(): Boolean {
            if (length() < 0x10L + HEADER_SIZE) return false
            val firstSentinel = readUInt32BE(0x04L)
            val secondSentinel = readUInt32BE(0x0CL)
            return ((firstSentinel == 0x0000_0000L && secondSentinel == 0x0000_0000L) ||
                (firstSentinel == 0xFFFF_FFFFL && secondSentinel == 0xFFFF_FFFFL)) &&
                hasSwitchOpusAt(0x10L)
        }

        private fun RandomAccessFile.hasCapcomSwitchOpus(): Boolean {
            if (length() < 0x20L) return false
            val channels = readInt32LE(0x04L)
            if (channels != 1 && channels != 2 && channels != 6) return false
            val offset = readUInt32LE(0x1CL)
            return offset > 0 && hasSwitchOpusAt(offset)
        }

        private fun RandomAccessFile.findSwitchOpusOffset(): Long {
            val scanEnd = minOf(length() - HEADER_SIZE, MAX_HEADER_SCAN_BYTES)
            var offset = 0L
            while (offset <= scanEnd) {
                if (readUInt32LE(offset) == SWITCH_BASIC_INFO &&
                    runCatching { parseSwitchOpusHeader(this, offset, 0, 0, 0) }.isSuccess
                ) {
                    return offset
                }
                offset += 1L
            }
            return -1L
        }

        private fun countPacketSamples(file: RandomAccessFile, startOffset: Long, dataSize: Long): Int {
            var offset = startOffset
            val endOffset = (startOffset + dataSize).coerceAtMost(file.length())
            var samples = 0L
            while (offset + SWITCH_PACKET_HEADER_SIZE <= endOffset) {
                val packetSize = file.readUInt32BE(offset)
                if (packetSize <= 0 || packetSize > MAX_PACKET_SIZE) break
                val payloadOffset = offset + SWITCH_PACKET_HEADER_SIZE
                val nextOffset = payloadOffset + packetSize
                if (nextOffset > endOffset) break

                val probeSize = minOf(packetSize.toInt(), 2)
                val probe = ByteArray(probeSize)
                file.seek(payloadOffset)
                file.readFully(probe)
                samples += opusPacketSampleCount(probe)
                offset = nextOffset
            }
            return samples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun opusInitializationData(channels: Int, preSkipSamples: Int, sampleRate: Int): List<ByteArray> {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1)
                put(channels.toByte())
                putShort(preSkipSamples.toShort())
                putInt(sampleRate)
                putShort(0)
                put(0)
            }.array()

            return listOf(
                header,
                nativeLongBytes(samplesToNs(preSkipSamples.toLong(), OPUS_SAMPLE_RATE)),
                nativeLongBytes(samplesToNs(DEFAULT_SEEK_PRE_ROLL_SAMPLES.toLong(), OPUS_SAMPLE_RATE))
            )
        }

        private fun opusPacketSampleCount(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val frames = when (packet[0].toInt() and 0x03) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size < 2) 0 else packet[1].toInt() and 0x3F
            }
            return frames * opusSamplesPerFrame(packet[0].toInt() and 0xFF)
        }

        private fun opusSamplesPerFrame(toc: Int): Int {
            return when {
                toc and 0x80 != 0 -> OPUS_SAMPLE_RATE shl ((toc shr 3) and 0x03) / 400
                toc and 0x60 == 0x60 -> if (toc and 0x08 != 0) OPUS_SAMPLE_RATE / 50 else OPUS_SAMPLE_RATE / 100
                else -> {
                    val audioSize = (toc shr 3) and 0x03
                    if (audioSize == 3) {
                        OPUS_SAMPLE_RATE * 60 / 1000
                    } else {
                        OPUS_SAMPLE_RATE shl audioSize / 100
                    }
                }
            }
        }

        private fun nativeLongBytes(value: Long): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()

        private fun samplesToMs(samples: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0L else samples * 1000L / sampleRate

        private fun samplesToUs(samples: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0L else samples * 1_000_000L / sampleRate

        private fun samplesToNs(samples: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0L else samples * 1_000_000_000L / sampleRate

        private fun msToSamples(ms: Long, sampleRate: Int): Long =
            if (sampleRate <= 0) 0L else ms * sampleRate / 1000L
    }
}

private fun RandomAccessFile.readAscii(offset: Long, length: Int): String {
    val bytes = ByteArray(length)
    seek(offset)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}

private fun RandomAccessFile.readUInt16LE(offset: Long): Int {
    seek(offset)
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    return b0 or (b1 shl 8)
}

private fun RandomAccessFile.readUnsignedByteAt(offset: Long): Int {
    seek(offset)
    return readUnsignedByte()
}

private fun RandomAccessFile.readInt32LE(offset: Long): Int {
    seek(offset)
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun RandomAccessFile.readUInt32LE(offset: Long): Long =
    readInt32LE(offset).toLong() and 0xFFFF_FFFFL

private fun RandomAccessFile.readUInt32BE(offset: Long): Long {
    seek(offset)
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return ((b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()) and 0xFFFF_FFFFL
}
