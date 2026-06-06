package com.github.vgmstream.android

internal class VgmNative {
    external fun open(
        path: String,
        loopCount: Double,
        fadeLengthMs: Long,
        loopMode: Int,
        fadeDelayMs: Long,
        disableSubsongs: Boolean,
        downmixChannels: Int
    ): Long

    external fun close(handle: Long)
    external fun readPcm(handle: Long, buffer: ShortArray, requestedFrames: Int): Int
    external fun seek(handle: Long, positionMs: Long)
    external fun getDuration(handle: Long): Long
    external fun getPosition(handle: Long): Long
    external fun getSampleRate(handle: Long): Int
    external fun getChannels(handle: Long): Int
    external fun getLoopInfo(handle: Long): LongArray

    companion object {
        init {
            System.loadLibrary("vgmstream_jni")
        }
    }
}
