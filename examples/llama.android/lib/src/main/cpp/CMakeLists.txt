cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library - Simplified for cross-platform build
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(GGML_SYSTEM_ARCH "ARM")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
    else()
        message(WARNING "Unknown ABI: ${ANDROID_ABI}, using ARM defaults")
        set(GGML_SYSTEM_ARCH "ARM")
    endif()
endif()

# Disable problematic features - passed from Gradle but set here as fallback
if(NOT DEFINED GGML_CPU_KLEIDIAI)
    set(GGML_CPU_KLEIDIAI OFF)
endif()
if(NOT DEFINED GGML_OPENMP)
    set(GGML_OPENMP OFF)
endif()

# 16 KB page sizes. Devices with 16 KB pages refuse to System.loadLibrary() a .so whose LOAD
# segments are aligned to 4 KB, which is what NDK r27 and lower produce by default (1.4.2 and
# earlier shipped p_align 0x1000). max-page-size aligns the LOAD segments, common-page-size pads
# the end of GNU_RELRO to 16 KB; https://developer.android.com/guide/practices/page-sizes asks
# for both below r28. add_link_options() covers every target created after it in this directory
# and below, so it must stay ahead of add_subdirectory(): ggml, ggml-base, ggml-cpu and llama are
# created there, and they ship in the AAR next to ai-chat. scripts/verify-aar-native.sh checks
# the result in publish-aar.yml.
if(ANDROID)
    add_link_options(
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384")
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)
