package me.ayra.vgmstream

import java.io.Closeable

internal interface PcmDecoder : Closeable {
    val duration: Long
    val position: Long
    val sampleRate: Int
    val channels: Int

    fun readPcm(buffer: ShortArray, requestedFrames: Int = buffer.size / channels): Int
    fun seek(positionMs: Long)
}
