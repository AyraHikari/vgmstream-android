package me.ayra.vgmstream

data class LoopInfo(
    val hasLoop: Boolean,
    val startMs: Long,
    val endMs: Long,
    val startSample: Long,
    val endSample: Long
)
