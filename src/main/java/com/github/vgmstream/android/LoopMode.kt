package com.github.vgmstream.android

enum class LoopMode(internal val nativeValue: Int) {
    Normal(0),
    Forever(1),
    IgnoreLoop(2)
}
