package me.ayra.vgmstream

data class VgmSettings(
    val loopCount: Double = 1.0,
    val fadeLengthMs: Long = 0L,
    val loopMode: LoopMode = LoopMode.Normal,
    val fadeDelayMs: Long = 0L,
    val disableSubsongs: Boolean = false,
    val downmixChannels: Int = 0
) {
    init {
        require(loopCount >= 0.0) { "loopCount must be >= 0" }
        require(fadeLengthMs >= 0L) { "fadeLengthMs must be >= 0" }
        require(fadeDelayMs >= 0L) { "fadeDelayMs must be >= 0" }
        require(downmixChannels >= 0) { "downmixChannels must be >= 0" }
    }

    internal val normalizedDownmixChannels: Int
        get() = downmixChannels.takeIf { it > 0 } ?: 0
}
