package me.ayra.vgmstream

enum class ChannelOutput(
    internal val sourceChannelIndices: IntArray?
) {
    Auto(null),
    AllChannels(null),
    Channel1(intArrayOf(0)),
    Channel2(intArrayOf(1)),
    Channel3(intArrayOf(2)),
    Channel4(intArrayOf(3)),
    Stereo12(intArrayOf(0, 1)),
    Stereo34(intArrayOf(2, 3))
}
