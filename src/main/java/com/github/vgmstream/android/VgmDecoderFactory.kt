package com.github.vgmstream.android

object VgmDecoderFactory {
    private val native = VgmNative()

    fun open(
        path: String,
        loopCount: Double = 1.0,
        fadeLengthMs: Long = 0L,
        loopMode: LoopMode = LoopMode.Normal
    ): VgmDecoder {
        val handle = native.open(path, loopCount, fadeLengthMs, loopMode.nativeValue)
        return VgmDecoder(native, handle)
    }
}
