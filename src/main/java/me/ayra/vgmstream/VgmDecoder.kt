package me.ayra.vgmstream

class VgmDecoder internal constructor(
    private val native: VgmNative,
    private var handle: Long
) : PcmDecoder {
    override val duration: Long get() = native.getDuration(requireHandle())
    override val position: Long get() = native.getPosition(requireHandle())
    override val sampleRate: Int get() = native.getSampleRate(requireHandle())
    override val channels: Int get() = native.getChannels(requireHandle())

    val loopInfo: LoopInfo
        get() {
            val values = native.getLoopInfo(requireHandle())
            return LoopInfo(
                hasLoop = values.getOrElse(0) { 0L } != 0L,
                startMs = values.getOrElse(1) { 0L },
                endMs = values.getOrElse(2) { 0L },
                startSample = values.getOrElse(3) { 0L },
                endSample = values.getOrElse(4) { 0L }
            )
        }

    override fun readPcm(buffer: ShortArray, requestedFrames: Int): Int =
        native.readPcm(requireHandle(), buffer, requestedFrames)

    override fun seek(positionMs: Long) {
        native.seek(requireHandle(), positionMs)
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            native.close(current)
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "decoder is closed" }
        return handle
    }
}
