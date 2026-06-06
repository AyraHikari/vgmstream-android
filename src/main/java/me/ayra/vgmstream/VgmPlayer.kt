package me.ayra.vgmstream

import android.net.Uri

interface VgmPlayer {
    fun open(uri: Uri)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)

    val duration: Long
    val position: Long
    val isPlaying: Boolean
}
