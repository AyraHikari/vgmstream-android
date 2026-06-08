cmake_minimum_required(VERSION 3.22.1)
project(vgmstream_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/vgmstream
    ${CMAKE_CURRENT_BINARY_DIR}/vgmstream
)

add_library(vgmstream_jni SHARED vgmstream_jni.cpp)

target_include_directories(vgmstream_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/vgmstream/src
)

target_link_libraries(vgmstream_jni
    PRIVATE
    libvgmstream
    log
    m
)
