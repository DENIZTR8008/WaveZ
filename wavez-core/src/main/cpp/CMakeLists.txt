cmake_minimum_required(VERSION 3.22.1)
project(wavez LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)

# Fetch official Xiph libFLAC 1.5.0
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
set(HAVE_FSEEKO 1 CACHE INTERNAL "" FORCE)

FetchContent_Declare(
    flac
    GIT_REPOSITORY https://github.com/xiph/flac.git
    GIT_TAG 1.5.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(flac)

if (TARGET FLAC)
    target_compile_definitions(FLAC PRIVATE HAVE_FSEEKO=1)
endif()

# Fetch SoundTouch for independent BPM/Tempo & Pitch shift
FetchContent_Declare(
    soundtouch
    GIT_REPOSITORY https://github.com/stenzek/soundtouch.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(soundtouch)
if (NOT soundtouch_POPULATED)
    FetchContent_Populate(soundtouch)
endif()

add_library(SoundTouch STATIC
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/AAFilter.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/BPMDetect.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/cpu_detect_x86.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/FIFOSampleBuffer.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/FIRFilter.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/InterpolateCubic.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/InterpolateLinear.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/InterpolateShannon.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/mmx_optimized.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/PeakFinder.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/RateTransposer.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/SoundTouch.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/sse_optimized.cpp
    ${soundtouch_SOURCE_DIR}/source/SoundTouch/TDStretch.cpp
)
target_include_directories(SoundTouch PUBLIC
    ${soundtouch_SOURCE_DIR}/include
    ${soundtouch_SOURCE_DIR}/include/soundtouch
)
target_compile_definitions(SoundTouch PUBLIC SOUNDTOUCH_FLOAT_SAMPLES)
set_target_properties(SoundTouch PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(wavez SHARED
    EditDecisionList.cpp
    WaveZDsp.cpp
    WaveZPlayer.cpp
    WaveZExporter.cpp
    wavez_jni.cpp
)

target_include_directories(wavez PRIVATE
    ${flac_SOURCE_DIR}/include
    ${soundtouch_SOURCE_DIR}/include
    ${soundtouch_SOURCE_DIR}/include/soundtouch
)

# Support 16 KB page sizes on Android 15+ devices
target_link_options(wavez PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(wavez
    oboe::oboe
    FLAC
    SoundTouch
    log
    android
)
