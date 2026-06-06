# VGMStream Android Library

Android library wrapper around [vgmstream](https://github.com/vgmstream/vgmstream).

## Current Scope

- Builds `libvgmstream_jni.so` for:
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86_64`
- Links upstream `libvgmstream` statically into the Android JNI library.
- Exposes native operations through `VgmNative`:
  - `open`
  - `close`
  - `readPcm`
  - `seek`
  - `getDuration`
  - `getPosition`
  - `getSampleRate`
  - `getChannels`
  - `getLoopInfo`
- Provides Kotlin APIs:
  - `VgmDecoder`
  - `VgmDecoderFactory`
  - `VgmPlayer`
  - `AudioTrackVgmPlayer`
  - loop settings via `LoopMode`

## Build Notes

This first pass disables external codec dependencies in CMake:

- MPEG/mpg123: off
- Vorbis/Ogg: off
- FFmpeg: off
- G.719: off
- ATRAC9: off
- CELT: off
- Speex: off

Core vgmstream formats backed by built-in decoders are available. OGG, MP3, and FFmpeg-backed formats need a later dependency pass.

The local Android SDK path is expected in `local.properties`; this file is intentionally gitignored.
