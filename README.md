# VGMStream Android Library

Android wrapper around [vgmstream](https://github.com/vgmstream/vgmstream) using JNI and Android NDK.

This library allows Android applications to decode and play game audio formats supported by vgmstream, including Nintendo, PlayStation, Xbox, and many other proprietary game audio containers.

## Features

* JNI wrapper around native vgmstream
* Android NDK compiled shared libraries (.so)
* PCM decoding API
* Playback position and duration support
* Seeking support
* Loop configuration
* Fade length and fade delay configuration
* Channel selection support
* Subsong selection support

## Supported Formats

Supports most formats handled by vgmstream, including:

* BCSTM
* BFSTM
* BRSTM
* DSP
* ADX
* HCA
* FSB
* GENH
* XMA
* AT9
* LOPUS
* and many more

See the upstream vgmstream project for the complete format list.

## Installation

TBD

## Basic Usage

```kotlin
val player = VgmPlayer()

player.open(uri)

player.play()
player.pause()

player.seekTo(30_000)

val duration = player.duration
val position = player.position
```

## Playback Configuration

```kotlin
player.configure(
    loopMode = LoopMode.NORMAL,
    loopCount = 2,
    fadeLengthSeconds = 10f,
    fadeDelaySeconds = 0f
)
```

## Channel Selection

Useful for multi-channel streams such as layered Nintendo BCSTM/BFSTM audio.

```kotlin
player.setChannelOutput(
    leftChannel = 1,
    rightChannel = 2
)
```

Example:

```text
1 = Left Map
2 = Right Map
3 = Left Battle
4 = Right Battle
```

You can choose to play only specific channel pairs.

## License

This project bundles and wraps vgmstream.

Please refer to the upstream project for licensing details:

https://github.com/vgmstream/vgmstream
