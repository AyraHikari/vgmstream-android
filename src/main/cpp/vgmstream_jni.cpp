#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>

#include <android/log.h>

extern "C" {
#include "libvgmstream.h"
#include "libvgmstream_streamfile.h"
}

namespace {

constexpr const char* kLogTag = "vgmstream-jni";
constexpr int kLoopModeNormal = 0;
constexpr int kLoopModeForever = 1;
constexpr int kLoopModeIgnoreLoop = 2;

struct NativeDecoder {
    libvgmstream_t* decoder = nullptr;

    ~NativeDecoder() {
        if (decoder) {
            libvgmstream_free(decoder);
        }
    }
};

NativeDecoder* fromHandle(jlong handle) {
    return reinterpret_cast<NativeDecoder*>(handle);
}

jlong msToSample(jlong positionMs, int sampleRate) {
    if (positionMs <= 0 || sampleRate <= 0) return 0;
    return (positionMs * sampleRate) / 1000;
}

jlong sampleToMs(int64_t sample, int sampleRate) {
    if (sample <= 0 || sampleRate <= 0) return 0;
    return (sample * 1000) / sampleRate;
}

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz) env->ThrowNew(clazz, message);
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz) env->ThrowNew(clazz, message);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_github_vgmstream_android_VgmNative_open(
        JNIEnv* env,
        jobject,
        jstring path_,
        jdouble loopCount,
        jlong fadeLengthMs,
        jint loopMode,
        jlong fadeDelayMs,
        jboolean disableSubsongs,
        jint downmixChannels) {
    if (!path_) {
        throwIllegalArgument(env, "path is null");
        return 0;
    }

    const char* path = env->GetStringUTFChars(path_, nullptr);
    if (!path) return 0;

    libstreamfile_t* sf = libstreamfile_open_from_stdio(path);
    if (!sf) {
        env->ReleaseStringUTFChars(path_, path);
        throwIllegalArgument(env, "failed to open stream file");
        return 0;
    }

    libvgmstream_config_t config{};
    config.force_sfmt = LIBVGMSTREAM_SFMT_PCM16;
    config.loop_count = loopCount;
    config.fade_time = fadeLengthMs > 0 ? static_cast<double>(fadeLengthMs) / 1000.0 : 0.0;
    config.fade_delay = fadeDelayMs > 0 ? static_cast<double>(fadeDelayMs) / 1000.0 : 0.0;
    config.play_forever = loopMode == kLoopModeForever;
    config.ignore_loop = loopMode == kLoopModeIgnoreLoop;
    config.allow_play_forever = loopMode == kLoopModeForever;
    config.auto_downmix_channels = downmixChannels > 0 ? downmixChannels : 0;

    libvgmstream_t* decoder = libvgmstream_create(sf, 0, &config);
    libstreamfile_close(sf);
    env->ReleaseStringUTFChars(path_, path);

    if (!decoder || !decoder->format) {
        if (decoder) libvgmstream_free(decoder);
        throwIllegalArgument(env, "unsupported or invalid vgmstream file");
        return 0;
    }

    if (disableSubsongs && decoder->format->subsong_count > 1) {
        libvgmstream_free(decoder);
        throwIllegalArgument(env, "file has multiple subsongs and subsongs are disabled");
        return 0;
    }

    auto* native = new NativeDecoder();
    native->decoder = decoder;
    return reinterpret_cast<jlong>(native);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_vgmstream_android_VgmNative_close(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_vgmstream_android_VgmNative_readPcm(
        JNIEnv* env,
        jobject,
        jlong handle,
        jshortArray buffer_,
        jint requestedFrames) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder) {
        throwIllegalState(env, "decoder is closed");
        return 0;
    }
    if (!buffer_ || requestedFrames <= 0) return 0;

    const auto* format = native->decoder->format;
    const int channels = format ? format->channels : 0;
    if (channels <= 0) return 0;

    const jsize shortsCapacity = env->GetArrayLength(buffer_);
    const int framesCapacity = shortsCapacity / channels;
    const int framesToRead = std::min<int>(requestedFrames, framesCapacity);
    if (framesToRead <= 0) return 0;

    jshort* buffer = env->GetShortArrayElements(buffer_, nullptr);
    if (!buffer) return 0;

    int result = libvgmstream_fill(native->decoder, buffer, framesToRead);
    int framesRead = 0;
    if (result >= 0 && native->decoder->decoder) {
        framesRead = native->decoder->decoder->buf_samples;
    } else {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "libvgmstream_fill failed: %d", result);
    }

    env->ReleaseShortArrayElements(buffer_, buffer, 0);
    return framesRead;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_vgmstream_android_VgmNative_seek(JNIEnv* env, jobject, jlong handle, jlong positionMs) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder || !native->decoder->format) {
        throwIllegalState(env, "decoder is closed");
        return;
    }
    libvgmstream_seek(native->decoder, msToSample(positionMs, native->decoder->format->sample_rate));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_github_vgmstream_android_VgmNative_getDuration(JNIEnv*, jobject, jlong handle) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder || !native->decoder->format) return 0;
    return sampleToMs(native->decoder->format->play_samples, native->decoder->format->sample_rate);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_github_vgmstream_android_VgmNative_getPosition(JNIEnv*, jobject, jlong handle) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder || !native->decoder->format) return 0;
    int64_t sample = libvgmstream_get_play_position(native->decoder);
    return sampleToMs(sample, native->decoder->format->sample_rate);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_vgmstream_android_VgmNative_getSampleRate(JNIEnv*, jobject, jlong handle) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder || !native->decoder->format) return 0;
    return native->decoder->format->sample_rate;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_vgmstream_android_VgmNative_getChannels(JNIEnv*, jobject, jlong handle) {
    NativeDecoder* native = fromHandle(handle);
    if (!native || !native->decoder || !native->decoder->format) return 0;
    return native->decoder->format->channels;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_github_vgmstream_android_VgmNative_getLoopInfo(JNIEnv* env, jobject, jlong handle) {
    jlong values[5] = {0, 0, 0, 0, 0};
    NativeDecoder* native = fromHandle(handle);
    if (native && native->decoder && native->decoder->format) {
        const auto* format = native->decoder->format;
        values[0] = format->loop_flag ? 1 : 0;
        values[1] = sampleToMs(format->loop_start, format->sample_rate);
        values[2] = sampleToMs(format->loop_end, format->sample_rate);
        values[3] = format->loop_start;
        values[4] = format->loop_end;
    }
    jlongArray array = env->NewLongArray(5);
    if (array) env->SetLongArrayRegion(array, 0, 5, values);
    return array;
}
