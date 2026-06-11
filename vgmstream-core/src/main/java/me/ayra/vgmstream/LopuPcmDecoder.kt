package me.ayra.vgmstream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class LopuPcmDecoder(
    path: String,
    private val settings: VgmSettings = VgmSettings(),
) : PcmDecoder {
    private val file = RandomAccessFile(File(path), "r")
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val startOffset: Long
    private val dataEndOffset: Long
    private val preSkipSamples: Int
    private val displaySamples: Long
    private val playLimitSamples: Long
    private val loopStartSample: Long
    private val loopEndSample: Long
    private val fadeStartSample: Long
    private val fadeLengthSamples: Long
    private val packetIndex: List<PacketIndex>

    private var nextPacketOffset: Long
    private var queuedSamples = 0L
    private var currentPositionUs = 0L
    private var inputDone = false
    private var outputDone = false
    private var released = false
    private var pendingOutputIndex = -1
    private var pendingOutputOffset = 0
    private var pendingOutputSize = 0
    private var pendingOutputStartSample = 0L
    private var pendingOutputCopiedShorts = 0
    private var pendingDiscardFrames = 0
    private var pendingTailTrimFrames = 0
    private var pendingLoopAfterOutput = false
    private var pendingLoopOffset = -1L
    private var pendingCodecFlushBeforeInput = false
    private var loopDebugId = 0
    private var pendingLoopDebugId = 0
    private var awaitingLoopStartComparison = false
    private var loopEndFrameSnapshot: IntArray? = null

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
        loopStartSample = header.loopStartSample.coerceAtLeast(0).toLong()
        loopEndSample = header.loopEndSample.coerceAtLeast(0).toLong()
        packetIndex = buildPacketIndex(file, startOffset, header.dataSize)
        nextPacketOffset = startOffset
        displaySamples =
            calculateDisplaySamples(
                streamSamples = header.numSamples.coerceAtLeast(0).toLong(),
                loopStart = loopStartSample,
                loopEnd = loopEndSample,
                settings = settings,
            )
        playLimitSamples = calculatePlayLimitSamples(displaySamples, settings)
        fadeStartSample = calculateFadeStartSample(loopStartSample, loopEndSample, settings)
        fadeLengthSamples = msToSamples(settings.fadeLengthMs, sampleRate)
        duration = samplesToMs(displaySamples, sampleRate)

        logLoopDebug(
            "init sampleRate=$sampleRate channels=$channels preSkip=$preSkipSamples " +
                "streamSamples=${header.numSamples} displaySamples=$displaySamples " +
                "loopMode=${settings.loopMode} loopStart=$loopStartSample loopEnd=$loopEndSample " +
                "loopLength=${(loopEndSample - loopStartSample).coerceAtLeast(0L)} " +
                "packetCount=${packetIndex.size}",
        )

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setLong(MediaFormat.KEY_DURATION, duration * 1000L)
        opusInitializationData(channels, preSkipSamples, sampleRate).forEachIndexed { index, data ->
            format.setByteBuffer("csd-$index", ByteBuffer.wrap(data))
        }

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun readPcm(
        buffer: ShortArray,
        requestedFrames: Int,
    ): Int {
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
                        pendingOutputStartSample = usToSamples(bufferInfo.presentationTimeUs, sampleRate)
                        pendingOutputCopiedShorts = 0
                        currentPositionUs = bufferInfo.presentationTimeUs
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Unit
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

        val targetSamples = msToSamples(positionMs.coerceIn(0L, duration), sampleRate).coerceAtMost(displaySamples)
        resetCodecState()
        queuedSamples = targetSamples
        nextPacketOffset = packetOffsetForSourceSample(sourceSampleForPlaySample(targetSamples))
        currentPositionUs = samplesToUs(targetSamples, sampleRate)
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
        pendingOutputStartSample = 0L
        pendingOutputCopiedShorts = 0
        pendingDiscardFrames = 0
        pendingTailTrimFrames = 0
        pendingLoopAfterOutput = false
        pendingLoopOffset = -1L
        pendingCodecFlushBeforeInput = false
        awaitingLoopStartComparison = false
        loopEndFrameSnapshot = null
    }

    private fun feedInput() {
        if (inputDone) return
        if (pendingLoopAfterOutput) {
            return
        }
        if (pendingCodecFlushBeforeInput) {
            codec.flush()
            pendingCodecFlushBeforeInput = false
            inputDone = false
            outputDone = false
            logLoopDebug("loop#$pendingLoopDebugId codecFlushedBeforeLoopPreroll")
        }
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex)
        if (queuedSamples >= playLimitSamples) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                samplesToUs(queuedSamples, sampleRate),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputDone = true
            return
        }

        var packet = readPacketAt(nextPacketOffset)
        if (packet != null && shouldLoopSource(packet)) {
            val loopPrerollSample = (loopStartSample - DEFAULT_SEEK_PRE_ROLL_SAMPLES).coerceAtLeast(0L)
            val loopOffset = packetOffsetForSourceSample(loopPrerollSample)
            val packetStart = sourceSampleForPacketOffset(nextPacketOffset)
            val packetEnd = packetStart + packet.sampleCount
            val loopPacketStart = sourceSampleForPacketOffset(loopOffset)
            val discardAtLoopStart = (loopStartSample - loopPacketStart).coerceAtLeast(0).toInt()

            pendingTailTrimFrames =
                (
                    sourceSampleForPacketOffset(nextPacketOffset) +
                        packet.sampleCount -
                        loopEndSample
                ).coerceAtLeast(0).toInt()

            pendingLoopAfterOutput = true
            pendingLoopOffset = loopOffset
            loopDebugId += 1
            pendingLoopDebugId = loopDebugId
            loopEndFrameSnapshot = null
            awaitingLoopStartComparison = false
            logLoopDebug(
                "loop#$pendingLoopDebugId queueLastPacket " +
                    "playPts=${samplesToUs(queuedSamples, sampleRate)} queuedSamples=$queuedSamples " +
                    "packetOffset=$nextPacketOffset packetStart=$packetStart packetEnd=$packetEnd " +
                    "packetSamples=${packet.sampleCount} loopEnd=$loopEndSample " +
                    "tailTrim=$pendingTailTrimFrames loopOffset=$loopOffset " +
                    "loopPrerollSample=$loopPrerollSample loopPacketStart=$loopPacketStart loopStart=$loopStartSample " +
                    "discardAtLoopStart=$discardAtLoopStart",
            )
        }

        if (inputBuffer == null || packet == null) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                samplesToUs(queuedSamples, sampleRate),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
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
        queuedSamples = (queuedSamples + packet.sampleCount).coerceAtMost(playLimitSamples)
    }

    private fun shouldLoopSource(packet: Packet): Boolean {
        if (!hasUsableLoop()) return false
        if (settings.loopMode == LoopMode.IgnoreLoop) return false

        val packetStart = sourceSampleForPacketOffset(nextPacketOffset)
        val packetEnd = packetStart + packet.sampleCount

        pendingTailTrimFrames =
            (packetStart + packet.sampleCount - loopEndSample)
                .coerceAtLeast(0)
                .toInt()

        return packetEnd > loopEndSample
    }

    private fun hasUsableLoop(): Boolean = loopEndSample > loopStartSample && loopStartSample >= 0L

    private fun sourceSampleForPlaySample(playSample: Long): Long {
        if (!hasUsableLoop() || settings.loopMode == LoopMode.IgnoreLoop || playSample < loopEndSample) {
            return playSample.coerceAtLeast(0L)
        }

        val loopLength = loopEndSample - loopStartSample
        return loopStartSample + ((playSample - loopEndSample) % loopLength)
    }

    private fun packetOffsetForSourceSample(sourceSample: Long): Long {
        if (packetIndex.isEmpty()) return startOffset
        val index = packetIndex.indexOfLast { it.startSample <= sourceSample }
        return packetIndex.getOrElse(index.coerceAtLeast(0)) { packetIndex.first() }.offset
    }

    private fun sourceSampleForPacketOffset(offset: Long): Long =
        packetIndex.firstOrNull { it.offset == offset }?.startSample ?: Long.MAX_VALUE

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

    private fun copyPendingOutput(
        buffer: ShortArray,
        outputOffset: Int,
        maxShorts: Int,
    ): Int {
        val outputBuffer = codec.getOutputBuffer(pendingOutputIndex) ?: return 0
        outputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.position(pendingOutputOffset)
        outputBuffer.limit(pendingOutputOffset + pendingOutputSize)

        val shortsToCopy = minOf(maxShorts, pendingOutputSize / BYTES_PER_SHORT)
        val shortBuffer = outputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val temp = ShortArray(shortsToCopy)

        shortBuffer.get(temp, 0, shortsToCopy)
        applyFade(temp, shortsToCopy)

        var startFrame = 0
        var endFrame = temp.size / channels
        val originalStartFrame = startFrame
        val originalEndFrame = endFrame
        val discardBefore = pendingDiscardFrames
        val tailTrimBefore = pendingTailTrimFrames

        if (pendingDiscardFrames > 0) {
            val discardFrames =
                minOf(
                    pendingDiscardFrames,
                    endFrame,
                )

            startFrame += discardFrames
            pendingDiscardFrames -= discardFrames
        }

        if (pendingTailTrimFrames > 0) {
            val trimFrames =
                minOf(
                    pendingTailTrimFrames,
                    endFrame - startFrame,
                )

            endFrame -= trimFrames
            pendingTailTrimFrames -= trimFrames
        }

        val startShort = startFrame * channels
        val endShort = endFrame * channels
        val remainingShorts = endShort - startShort

        if (pendingLoopDebugId > 0 && (discardBefore > 0 || tailTrimBefore > 0 || pendingLoopAfterOutput)) {
            logLoopDebug(
                "loop#$pendingLoopDebugId copyOutput " +
                    "pts=${samplesToUs(pendingOutputStartSample, sampleRate)} " +
                    "outputStartSample=$pendingOutputStartSample " +
                    "copiedBeforeFrames=${pendingOutputCopiedShorts / channels} " +
                    "bufferFrames=${temp.size / channels} originalFrames=$originalStartFrame..$originalEndFrame " +
                    "keptFrames=$startFrame..$endFrame kept=${remainingShorts / channels} " +
                    "discardBefore=$discardBefore discardAfter=$pendingDiscardFrames " +
                    "tailBefore=$tailTrimBefore tailAfter=$pendingTailTrimFrames",
            )
        }

        if (remainingShorts <= 0) {
            val bytesCopied = shortsToCopy * BYTES_PER_SHORT

            pendingOutputOffset += bytesCopied
            pendingOutputSize -= bytesCopied
            pendingOutputCopiedShorts += shortsToCopy

            if (pendingOutputSize <= 0) {
                codec.releaseOutputBuffer(pendingOutputIndex, false)
                pendingOutputIndex = -1
                pendingOutputOffset = 0
            }

            return 0
        }

        if (awaitingLoopStartComparison) {
            val firstLoopFrame = captureFrame(temp, startShort)
            val endFrameSnapshot = loopEndFrameSnapshot
            val bestMatch = findClosestFrame(
                samples = temp,
                target = endFrameSnapshot,
                startFrame = startFrame,
                endFrame = endFrame,
            )
            val firstScore = frameDistance(endFrameSnapshot, firstLoopFrame)
            val bestFrameOffsetFromKept = bestMatch?.let { it.frameIndex - startFrame }
            val declickFrames =
                applyLoopBoundaryDeclick(
                    samples = temp,
                    startFrame = startFrame,
                    endFrame = endFrame,
                    previousFrame = endFrameSnapshot,
                    nextFrame = firstLoopFrame,
                )
            val adjustedStartFrame = captureFrame(temp, startShort)
            logLoopDebug(
                "loop#$pendingLoopDebugId boundaryCompare " +
                    "endFrame=${formatFrame(endFrameSnapshot)} " +
                    "startFrame=${formatFrame(firstLoopFrame)} " +
                    "delta=${formatFrameDelta(endFrameSnapshot, firstLoopFrame)} " +
                    "score=$firstScore " +
                    "startOutputSample=${pendingOutputStartSample + startFrame} " +
                    "adjustedStartFrame=${formatFrame(adjustedStartFrame)} " +
                    "adjustedDelta=${formatFrameDelta(endFrameSnapshot, adjustedStartFrame)} " +
                    "declickFrames=$declickFrames " +
                    "bestFrame=${formatFrame(bestMatch?.frame)} " +
                    "bestDelta=${formatFrameDelta(endFrameSnapshot, bestMatch?.frame)} " +
                    "bestScore=${bestMatch?.score} " +
                    "bestFrameOffsetFromKept=$bestFrameOffsetFromKept " +
                    "preSkipFrame=${formatFrame(captureFrame(temp, (startFrame - preSkipSamples) * channels))} " +
                    "postSkipFrame=${formatFrame(captureFrame(temp, (startFrame + preSkipSamples) * channels))}",
            )
            awaitingLoopStartComparison = false
            loopEndFrameSnapshot = null
        }

        if (pendingLoopAfterOutput && pendingTailTrimFrames <= 0) {
            loopEndFrameSnapshot = captureFrame(temp, endShort - channels)
            logLoopDebug(
                "loop#$pendingLoopDebugId loopEndFrame " +
                    "sourceSample=${loopEndSample - 1} " +
                    "outputSample=${pendingOutputStartSample + endFrame - 1} " +
                    "frame=${formatFrame(loopEndFrameSnapshot)}",
            )
        }

        temp.copyInto(
            destination = buffer,
            destinationOffset = outputOffset,
            startIndex = startShort,
            endIndex = endShort,
        )

        val bytesCopied = shortsToCopy * BYTES_PER_SHORT
        pendingOutputOffset += bytesCopied
        pendingOutputSize -= bytesCopied
        pendingOutputCopiedShorts += shortsToCopy
        currentPositionUs =
            samplesToUs(
                pendingOutputStartSample + (pendingOutputCopiedShorts / channels).toLong(),
                sampleRate,
            )
        if (pendingOutputSize <= 0) {
            if (
                pendingLoopAfterOutput &&
                pendingTailTrimFrames <= 0
            ) {
                val packetStart =
                    sourceSampleForPacketOffset(pendingLoopOffset)

                pendingDiscardFrames =
                    (
                        loopStartSample -
                            packetStart
                    ).coerceAtLeast(0).toInt()

                nextPacketOffset = pendingLoopOffset
                pendingCodecFlushBeforeInput = true

                awaitingLoopStartComparison = true
                logLoopDebug(
                    "loop#$pendingLoopDebugId switchToLoopStart " +
                        "nextPacketOffset=$nextPacketOffset packetStart=$packetStart " +
                        "loopStart=$loopStartSample discard=$pendingDiscardFrames codecFlushPending=true " +
                        "lastEndFrame=${formatFrame(loopEndFrameSnapshot)}",
                )

                pendingLoopAfterOutput = false
                pendingLoopOffset = -1L
            }

            codec.releaseOutputBuffer(pendingOutputIndex, false)
            pendingOutputIndex = -1
            pendingOutputOffset = 0
        }
        return remainingShorts
    }

    private fun captureFrame(
        samples: ShortArray,
        startShort: Int,
    ): IntArray? {
        if (channels <= 0 || startShort < 0 || startShort + channels > samples.size) return null
        return IntArray(channels) { channel -> samples[startShort + channel].toInt() }
    }

    private fun formatFrame(frame: IntArray?): String =
        frame?.joinToString(prefix = "[", postfix = "]") ?: "null"

    private fun formatFrameDelta(
        previous: IntArray?,
        next: IntArray?,
    ): String {
        if (previous == null || next == null) return "null"
        val count = minOf(previous.size, next.size)
        return (0 until count).joinToString(prefix = "[", postfix = "]") { index ->
            (next[index] - previous[index]).toString()
        }
    }

    private fun findClosestFrame(
        samples: ShortArray,
        target: IntArray?,
        startFrame: Int,
        endFrame: Int,
    ): FrameMatch? {
        if (target == null || channels <= 0) return null
        val frameCount = samples.size / channels
        val searchStart = startFrame.coerceIn(0, frameCount)
        val searchEnd = minOf(endFrame, searchStart + MAX_LOOP_ALIGNMENT_FRAMES, frameCount)
        var bestMatch: FrameMatch? = null
        for (frameIndex in searchStart until searchEnd) {
            val frame = captureFrame(samples, frameIndex * channels) ?: continue
            val score = frameDistance(target, frame) ?: continue
            val currentBest = bestMatch
            if (currentBest == null || score < currentBest.score) {
                bestMatch = FrameMatch(frameIndex, frame, score)
            }
        }
        return bestMatch
    }

    private fun frameDistance(
        previous: IntArray?,
        next: IntArray?,
    ): Long? {
        if (previous == null || next == null) return null
        var score = 0L
        for (channel in 0 until minOf(previous.size, next.size)) {
            score += kotlin.math.abs(next[channel] - previous[channel]).toLong()
        }
        return score
    }

    private fun applyLoopBoundaryDeclick(
        samples: ShortArray,
        startFrame: Int,
        endFrame: Int,
        previousFrame: IntArray?,
        nextFrame: IntArray?,
    ): Int {
        if (previousFrame == null || nextFrame == null || channels <= 0) return 0
        val score = frameDistance(previousFrame, nextFrame) ?: return 0
        if (score < LOOP_DECLICK_MIN_SCORE) return 0

        val frames = minOf(LOOP_DECLICK_FRAMES, endFrame - startFrame)
        if (frames <= 0) return 0

        for (frameOffset in 0 until frames) {
            val fadeOut = 1.0 - frameOffset.toDouble() / frames.toDouble()
            val gain = fadeOut * fadeOut
            val frameShort = (startFrame + frameOffset) * channels
            for (channel in 0 until channels) {
                val delta = previousFrame[channel] - nextFrame[channel]
                val adjusted = samples[frameShort + channel] + (delta * gain).toInt()
                samples[frameShort + channel] = adjusted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return frames
    }

    private data class FrameMatch(
        val frameIndex: Int,
        val frame: IntArray,
        val score: Long,
    )

    private fun logLoopDebug(message: String) {
        if (LOOP_DEBUG_LOGS) {
            Log.d(LOG_TAG, message)
        }
    }

    private fun applyFade(
        samples: ShortArray,
        sampleCount: Int,
    ) {
        if (fadeLengthSamples <= 0L || channels <= 0) return

        var index = 0
        while (index < sampleCount) {
            val frameInBuffer = (pendingOutputCopiedShorts + index) / channels
            val playSample = pendingOutputStartSample + frameInBuffer
            val gain = fadeGainAt(playSample)
            if (gain < 1.0) {
                for (channel in 0 until channels) {
                    val sampleIndex = index + channel
                    if (sampleIndex >= sampleCount) break
                    samples[sampleIndex] = (samples[sampleIndex] * gain).toInt().toShort()
                }
            }
            index += channels
        }
    }

    private fun fadeGainAt(playSample: Long): Double {
        if (playSample < fadeStartSample) return 1.0
        val elapsed = playSample - fadeStartSample
        if (elapsed >= fadeLengthSamples) return 0.0
        return 1.0 - elapsed.toDouble() / fadeLengthSamples.toDouble()
    }

    private data class Packet(
        val payload: ByteArray,
        val sampleCount: Int,
        val nextOffset: Long,
    )

    private data class PacketIndex(
        val offset: Long,
        val startSample: Long,
        val sampleCount: Int,
    )

    private data class ParsedHeader(
        val startOffset: Long,
        val dataSize: Long,
        val sampleRate: Int,
        val channels: Int,
        val preSkipSamples: Int,
        val numSamples: Int,
        val loopStartSample: Int,
        val loopEndSample: Int,
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
        private const val LOG_TAG = "LopuPcmDecoder"
        private const val LOOP_DEBUG_LOGS = false
        private const val FOREVER_DISPLAY_LOOP_COUNT = 10.0
        private const val MAX_LOOP_ALIGNMENT_FRAMES = 960
        private const val LOOP_DECLICK_FRAMES = 16
        private const val LOOP_DECLICK_MIN_SCORE = 2048L

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
                val loopStart = file.readInt32LE(0x18)
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
                    numSamples = adjustedSamples,
                    loopStartSample = loopStart,
                    loopEndSample = loopEnd,
                )
            }

            if (file.readAscii(0, 4) == "OPUS" && file.readUInt32BE(0x04L) == 0L) {
                return parseSwitchOpusHeader(
                    file = file,
                    baseOffset = file.readUInt32BE(0x20L),
                    fallbackNumSamples = file.readInt32BE(0x08L),
                    fallbackLoopStart = file.readInt32BE(0x14L),
                    fallbackLoopEnd = file.readInt32BE(0x18L),
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
                    fallbackLoopEnd = file.readInt32LE(0x08L),
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
                    fallbackLoopEnd = file.readInt32LE(0x0CL),
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
            fallbackLoopEnd: Int,
        ): ParsedHeader {
            require(file.hasSwitchOpusAt(baseOffset)) { "missing supported Opus header" }

            val channels = file.readUnsignedByteAt(baseOffset + 0x09L)
            val rawSampleRate = file.readInt32LE(baseOffset + 0x0CL)
            val sampleRate = if (rawSampleRate == OPUS_SAMPLE_RATE) rawSampleRate else OPUS_SAMPLE_RATE
            val dataInfoOffset = baseOffset + file.readUInt32LE(baseOffset + 0x10L)
            val contextOffset = file.readUInt32LE(baseOffset + 0x18L)
            val preSkip = file.readUInt16LE(baseOffset + 0x1CL)

            var numSamples = fallbackNumSamples
            var loopStart = fallbackLoopStart
            var loopEnd = fallbackLoopEnd
            if (contextOffset > 0) {
                val absoluteContextOffset = baseOffset + contextOffset
                if (file.readUInt32LE(absoluteContextOffset) == SWITCH_CONTEXT_INFO) {
                    numSamples = file.readInt32LE(absoluteContextOffset + 0x0CL)
                    loopStart = file.readInt32LE(absoluteContextOffset + 0x10L)
                    loopEnd = file.readInt32LE(absoluteContextOffset + 0x14L)
                }
            }

            require(file.readUInt32LE(dataInfoOffset) == SWITCH_DATA_INFO) { "missing Opus data chunk" }
            val dataSize = file.readUInt32LE(dataInfoOffset + 0x04L)
            val startOffset = dataInfoOffset + 0x08L
            val countedSamples = (countPacketSamples(file, startOffset, dataSize) - preSkip).coerceAtLeast(0)
            numSamples = maxOf(numSamples, countedSamples, loopEnd).coerceAtLeast(0)

            return ParsedHeader(
                startOffset = startOffset,
                dataSize = dataSize,
                sampleRate = sampleRate,
                channels = channels,
                preSkipSamples = preSkip,
                numSamples = numSamples,
                loopStartSample = loopStart,
                loopEndSample = loopEnd,
            )
        }

        private fun RandomAccessFile.hasSwitchOpusAt(offset: Long): Boolean =
            length() >= offset + HEADER_SIZE && readUInt32LE(offset) == SWITCH_BASIC_INFO

        private fun RandomAccessFile.hasNippon1SwitchOpus(): Boolean {
            if (length() < 0x10L + HEADER_SIZE) return false
            val firstSentinel = readUInt32BE(0x04L)
            val secondSentinel = readUInt32BE(0x0CL)
            return (
                (firstSentinel == 0x0000_0000L && secondSentinel == 0x0000_0000L) ||
                    (firstSentinel == 0xFFFF_FFFFL && secondSentinel == 0xFFFF_FFFFL)
            ) &&
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

        private fun countPacketSamples(
            file: RandomAccessFile,
            startOffset: Long,
            dataSize: Long,
        ): Int {
            var samples = 0L
            for (packet in buildPacketIndex(file, startOffset, dataSize)) {
                samples += packet.sampleCount
            }
            return samples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun buildPacketIndex(
            file: RandomAccessFile,
            startOffset: Long,
            dataSize: Long,
        ): List<PacketIndex> {
            var offset = startOffset
            val endOffset = (startOffset + dataSize).coerceAtMost(file.length())
            var samples = 0L
            val packets = mutableListOf<PacketIndex>()
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
                val sampleCount = opusPacketSampleCount(probe)
                packets += PacketIndex(offset, samples, sampleCount)
                samples += sampleCount
                offset = nextOffset
            }
            return packets
        }

        private fun calculateDisplaySamples(
            streamSamples: Long,
            loopStart: Long,
            loopEnd: Long,
            settings: VgmSettings,
        ): Long {
            if (settings.loopMode == LoopMode.IgnoreLoop || loopEnd <= loopStart) {
                return streamSamples
            }
            if (settings.loopMode == LoopMode.Forever) {
                val loopLength = loopEnd - loopStart
                return loopEnd + (loopLength * (FOREVER_DISPLAY_LOOP_COUNT - 1.0)).toLong()
            }

            val loopLength = loopEnd - loopStart
            val extraLoops = (settings.loopCount - 1.0).coerceAtLeast(0.0)
            val loopedSamples = loopEnd + (loopLength * extraLoops).toLong()
            return loopedSamples +
                msToSamples(settings.fadeDelayMs, OPUS_SAMPLE_RATE) +
                msToSamples(settings.fadeLengthMs, OPUS_SAMPLE_RATE)
        }

        private fun calculatePlayLimitSamples(
            displaySamples: Long,
            settings: VgmSettings,
        ): Long =
            if (settings.loopMode == LoopMode.Forever) {
                Long.MAX_VALUE / 4
            } else {
                displaySamples
            }

        private fun calculateFadeStartSample(
            loopStart: Long,
            loopEnd: Long,
            settings: VgmSettings,
        ): Long {
            if (settings.fadeLengthMs <= 0L ||
                settings.loopMode != LoopMode.Normal ||
                loopEnd <= loopStart
            ) {
                return Long.MAX_VALUE
            }

            val loopLength = loopEnd - loopStart
            val extraLoops = (settings.loopCount - 1.0).coerceAtLeast(0.0)
            return loopEnd +
                (loopLength * extraLoops).toLong() +
                msToSamples(settings.fadeDelayMs, OPUS_SAMPLE_RATE)
        }

        private fun opusInitializationData(
            channels: Int,
            preSkipSamples: Int,
            sampleRate: Int,
        ): List<ByteArray> {
            val header =
                ByteBuffer
                    .allocate(19)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
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
                nativeLongBytes(samplesToNs(DEFAULT_SEEK_PRE_ROLL_SAMPLES.toLong(), OPUS_SAMPLE_RATE)),
            )
        }

        private fun opusPacketSampleCount(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val frames =
                when (packet[0].toInt() and 0x03) {
                    0 -> 1
                    1, 2 -> 2
                    else -> if (packet.size < 2) 0 else packet[1].toInt() and 0x3F
                }
            return frames * opusSamplesPerFrame(packet[0].toInt() and 0xFF)
        }

        private fun opusSamplesPerFrame(toc: Int): Int =
            when {
                toc and 0x80 != 0 -> {
                    (OPUS_SAMPLE_RATE shl ((toc shr 3) and 0x03)) / 400
                }

                toc and 0x60 == 0x60 -> {
                    if (toc and 0x08 != 0) OPUS_SAMPLE_RATE / 50 else OPUS_SAMPLE_RATE / 100
                }

                else -> {
                    val audioSize = (toc shr 3) and 0x03
                    if (audioSize == 3) {
                        OPUS_SAMPLE_RATE * 60 / 1000
                    } else {
                        (OPUS_SAMPLE_RATE shl audioSize) / 100
                    }
                }
            }

        private fun nativeLongBytes(value: Long): ByteArray =
            ByteBuffer
                .allocate(8)
                .order(ByteOrder.nativeOrder())
                .putLong(value)
                .array()

        private fun samplesToMs(
            samples: Long,
            sampleRate: Int,
        ): Long = if (sampleRate <= 0) 0L else samples * 1000L / sampleRate

        private fun samplesToUs(
            samples: Long,
            sampleRate: Int,
        ): Long = if (sampleRate <= 0) 0L else samples * 1_000_000L / sampleRate

        private fun samplesToNs(
            samples: Long,
            sampleRate: Int,
        ): Long = if (sampleRate <= 0) 0L else samples * 1_000_000_000L / sampleRate

        private fun msToSamples(
            ms: Long,
            sampleRate: Int,
        ): Long = if (sampleRate <= 0) 0L else ms * sampleRate / 1000L

        private fun usToSamples(
            us: Long,
            sampleRate: Int,
        ): Long = if (sampleRate <= 0) 0L else us * sampleRate / 1_000_000L
    }
}

private fun RandomAccessFile.readAscii(
    offset: Long,
    length: Int,
): String {
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

private fun RandomAccessFile.readUInt32LE(offset: Long): Long = readInt32LE(offset).toLong() and 0xFFFF_FFFFL

private fun RandomAccessFile.readUInt32BE(offset: Long): Long {
    seek(offset)
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return ((b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()) and 0xFFFF_FFFFL
}

private fun RandomAccessFile.readInt32BE(offset: Long): Int = readUInt32BE(offset).toInt()
