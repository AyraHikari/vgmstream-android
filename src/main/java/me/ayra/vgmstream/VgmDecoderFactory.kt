package me.ayra.vgmstream

object VgmDecoderFactory {
    private val native = VgmNative()

    fun open(
        path: String,
        settings: VgmSettings = VgmSettings()
    ): PcmDecoder {
        val handle = native.open(
            path = path,
            loopCount = settings.loopCount,
            fadeLengthMs = settings.fadeLengthMs,
            loopMode = settings.loopMode.nativeValue,
            fadeDelayMs = settings.fadeDelayMs,
            disableSubsongs = settings.disableSubsongs,
            downmixChannels = settings.normalizedDownmixChannels,
            stereoTrack = 0
        )
        return applyChannelOutput(VgmDecoder(native, handle), settings)
    }

    fun open(
        path: String,
        loopCount: Double = 1.0,
        fadeLengthMs: Long = 0L,
        loopMode: LoopMode = LoopMode.Normal
    ): PcmDecoder = open(
        path = path,
        settings = VgmSettings(
            loopCount = loopCount,
            fadeLengthMs = fadeLengthMs,
            loopMode = loopMode
        )
    )

    internal fun applyChannelOutput(decoder: PcmDecoder, settings: VgmSettings): PcmDecoder {
        val sourceChannelIndices = settings.channelOutput.sourceChannelIndices
        return if (sourceChannelIndices != null) {
            ChannelOutputPcmDecoder(decoder, sourceChannelIndices)
        } else {
            decoder
        }
    }
}
