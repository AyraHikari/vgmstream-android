package me.ayra.vgmstream

internal class ChannelOutputPcmDecoder(
    private val source: PcmDecoder,
    private val sourceChannelIndices: IntArray
) : PcmDecoder {
    private var scratch = ShortArray(0)

    override val duration: Long get() = source.duration
    override val position: Long get() = source.position
    override val sampleRate: Int get() = source.sampleRate
    override val channels: Int = sourceChannelIndices.size

    init {
        require(sourceChannelIndices.isNotEmpty()) { "sourceChannelIndices must not be empty" }
        sourceChannelIndices.forEach { index ->
            require(index >= 0 && index < source.channels) {
                "Channel ${index + 1} is not available for ${source.channels}-channel output"
            }
        }
    }

    override fun readPcm(buffer: ShortArray, requestedFrames: Int): Int {
        if (requestedFrames <= 0) return 0

        val sourceChannels = source.channels
        val framesToRead = minOf(requestedFrames, buffer.size / channels)
        val requiredScratch = framesToRead * sourceChannels
        if (scratch.size < requiredScratch) {
            scratch = ShortArray(requiredScratch)
        }

        val frames = source.readPcm(scratch, framesToRead)
        for (frame in 0 until frames) {
            val sourceFrameOffset = frame * sourceChannels
            val outputFrameOffset = frame * channels
            for (channel in sourceChannelIndices.indices) {
                buffer[outputFrameOffset + channel] = scratch[sourceFrameOffset + sourceChannelIndices[channel]]
            }
        }
        return frames
    }

    override fun seek(positionMs: Long) {
        source.seek(positionMs)
    }

    override fun close() {
        source.close()
    }
}
