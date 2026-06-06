package com.github.vgmstream.android

object VgmDecoderFactory {
    private val native = VgmNative()

    fun open(
        path: String,
        settings: VgmSettings = VgmSettings()
    ): VgmDecoder {
        val handle = native.open(
            path = path,
            loopCount = settings.loopCount,
            fadeLengthMs = settings.fadeLengthMs,
            loopMode = settings.loopMode.nativeValue,
            fadeDelayMs = settings.fadeDelayMs,
            disableSubsongs = settings.disableSubsongs,
            downmixChannels = settings.normalizedDownmixChannels
        )
        return VgmDecoder(native, handle)
    }

    fun open(
        path: String,
        loopCount: Double = 1.0,
        fadeLengthMs: Long = 0L,
        loopMode: LoopMode = LoopMode.Normal
    ): VgmDecoder = open(
        path = path,
        settings = VgmSettings(
            loopCount = loopCount,
            fadeLengthMs = fadeLengthMs,
            loopMode = loopMode
        )
    )
}
