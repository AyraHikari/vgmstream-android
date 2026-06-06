# vgmstream-android

Android library wrapper around [vgmstream](https://github.com/vgmstream/vgmstream).

Package: `me.ayra.vgmstream`

## Features

- Android/Kotlin API for decoding vgmstream-supported game audio.
- JNI wrapper around native vgmstream.
- `AudioTrack` player wrapper for simple playback.
- PCM decoder API for custom playback pipelines.
- Seek, duration, sample rate, channel count, and loop info.
- Loop count, fade length, fade delay, loop mode, downmix, and channel output settings.
- Shared libraries built for:
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86_64`

## Architecture

File
 ↓
VgmDecoder
 ↓
PCM (16-bit interleaved)
 ↓
AudioTrackVgmPlayer
 ↓
AudioTrack

## Installation

### Gradle Dependency

Add JitPack to dependency resolution:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the library dependency:

```kotlin
dependencies {
    implementation("com.github.AyraHikari:vgmstream-android:<version>")
}
```

Replace `<version>` with a release tag or commit hash from the repository.

### Source Module

You can also include the library as a local module:

```kotlin
include(":vgmstream")
project(":vgmstream").projectDir = file("../library")
```

Then depend on it:

```kotlin
dependencies {
    implementation(project(":vgmstream"))
}
```

## Requirements

- Android minSdk `23`
- NDK `28+`
- CMake `3.22+`

If you consume a prebuilt artifact, your app does not need to build vgmstream from source. If you include the module from source, the Android NDK and CMake must be installed.

## Basic Playback

Use `AudioTrackVgmPlayer` when you want a simple player around Android `AudioTrack`.

```kotlin
import android.net.Uri
import me.ayra.vgmstream.AudioTrackVgmPlayer

val player = AudioTrackVgmPlayer(context)

fun open(uri: Uri) {
    player.open(uri)
}

fun play() {
    player.play()
}

fun pause() {
    player.pause()
}

fun seekTo30Seconds() {
    player.seekTo(30_000L)
}

val durationMs: Long = player.duration
val positionMs: Long = player.position
val isPlaying: Boolean = player.isPlaying
```

Release resources when playback is no longer needed:

```kotlin
player.stop()
```

## Playback Settings

Settings are passed through `VgmSettings`.

```kotlin
import me.ayra.vgmstream.AudioTrackVgmPlayer
import me.ayra.vgmstream.ChannelOutput
import me.ayra.vgmstream.LoopMode
import me.ayra.vgmstream.VgmSettings

val player = AudioTrackVgmPlayer(
    context,
    VgmSettings(
        loopCount = 2.0,
        fadeLengthMs = 10_000L,
        fadeDelayMs = 0L,
        loopMode = LoopMode.Normal,
        downmixChannels = 2,
        channelOutput = ChannelOutput.Auto
    )
)
```

Settings can also be changed before reopening a file:

```kotlin
player.settings = player.settings.copy(
    loopMode = LoopMode.Forever,
    fadeLengthMs = 0L
)
player.open(uri)
```

## Loop Modes

```kotlin
LoopMode.Normal      // apply loop count and fade settings
LoopMode.Forever     // keep looping when the format has loop points
LoopMode.IgnoreLoop  // play the raw stream without loop behavior
```

## Channel Output

`ChannelOutput` selects which decoded channels are sent to playback.

```kotlin
ChannelOutput.Auto
ChannelOutput.AllChannels
ChannelOutput.Channel1
ChannelOutput.Channel2
ChannelOutput.Channel3
ChannelOutput.Channel4
ChannelOutput.Stereo12
ChannelOutput.Stereo34
```

Example:

```kotlin
player.settings = player.settings.copy(
    channelOutput = ChannelOutput.Stereo34
)
player.open(uri)
```

This is useful for multi-channel game music where channels may represent layers such as field/battle, map/interior, or instrument stems. Stereo pair selection is equivalent in purpose to `vgmstream-cli -2`.

## PCM Decoder API

Use `VgmDecoderFactory` if you want decoded PCM frames and will handle playback yourself.

```kotlin
import me.ayra.vgmstream.VgmDecoderFactory
import me.ayra.vgmstream.VgmSettings

val decoder = VgmDecoderFactory.open(
    path = "/sdcard/Music/song.brstm",
    settings = VgmSettings(loopCount = 1.0)
)

val pcm = ShortArray(2048 * decoder.channels)
val framesRead = decoder.readPcm(pcm)

decoder.seek(15_000L)
decoder.close()
```

PCM output is signed 16-bit interleaved audio.

## Loop Info

When the decoder is backed by native vgmstream, loop metadata can be read from `VgmDecoder`:

```kotlin
val decoder = VgmDecoderFactory.open("/sdcard/Music/song.brstm")
if (decoder is me.ayra.vgmstream.VgmDecoder) {
    val loopInfo = decoder.loopInfo
}
```

`LoopInfo` includes:

- `hasLoop`
- `startMs`
- `endMs`
- `startSample`
- `endSample`

## Sample App

The repository includes a sample app under `sample/`.

From the sample project:

```powershell
.\gradlew.bat :app:assembleDebug
```

The sample app demonstrates:

- Android file picker
- Play/pause/seek
- Duration and current position
- Loop settings
- Fade settings
- Downmix
- Channel output selection

## Format Support

Core vgmstream formats backed by built-in decoders are available. This project currently disables several external codec dependencies in the native build:

- FFmpeg: off
- MPEG/mpg123: off
- Vorbis/Ogg: off
- G.719: off
- ATRAC9: off
- CELT: off
- Speex: off

Formats that require those external dependencies may fail until those codecs are enabled in a future build.

## ProGuard / R8

The library includes consumer rules for the public package:

```proguard
-keep class me.ayra.vgmstream.** { *; }
```

## Non-Goals

This library does not provide:

- Playlist management
- Media library scanning
- Cover art extraction
- Metadata database
- Android MediaSession integration
- Streaming playback (HLS/DASH)
- Compose UI components

## License

Apache License 2.0

This project bundles and wraps vgmstream.
See [LICENSE.md](https://github.com/AyraHikari/vgmstream-android/blob/master/LICENSE) for upstream licenses.
