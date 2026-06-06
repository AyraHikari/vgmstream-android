plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.github.vgmstream.android"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        minSdk = 23

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_CLI=OFF",
                    "-DBUILD_V123=OFF",
                    "-DBUILD_AUDACIOUS=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DUSE_MPEG=OFF",
                    "-DUSE_VORBIS=OFF",
                    "-DUSE_FFMPEG=OFF",
                    "-DUSE_G719=OFF",
                    "-DUSE_ATRAC9=OFF",
                    "-DUSE_CELT=OFF",
                    "-DUSE_SPEEX=OFF"
                )
                targets += "vgmstream_jni"
            }

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.2.13676358"
}
