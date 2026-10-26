cmake_minimum_required(VERSION 3.22.1)

project(memo_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp b11460, CPU-only, static, linked into our JNI shared library.
# Pinned by tag and SHA-256 (see docs/ENGINEERING_LOG.md D-018).
include(FetchContent)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# arm64 CPU feature path. GGML_CPU_ALL_VARIANTS needs GGML_BACKEND_DL, which needs shared
# backends, so it cannot be used with this static build. GGML_CPU_ARM_ARCH is supported
# at b11460 (ggml/src/ggml-cpu/CMakeLists.txt) and gives one fixed -march for the whole
# library: dotprod and fp16 (ARMv8.2-A). Devices without that baseline would fault, so the
# ABI check keeps x86_64 (emulator) on the generic build. See docs/ENGINEERING_LOG.md D-026.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    message(STATUS "memo_llama: arm64 CPU arch set to armv8.2-a+dotprod+fp16")
else()
    message(STATUS "memo_llama: generic CPU build for ABI ${ANDROID_ABI}")
endif()

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11460.tar.gz
    URL_HASH SHA256=80c50949a5f4c9b63fb216bd362289f4e8ae64e47625eb7a87e8381c0f88b36a
)
FetchContent_MakeAvailable(llama_cpp)

add_library(memo_llama SHARED memo_llama.cpp)
target_include_directories(memo_llama PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_link_libraries(memo_llama llama ggml log android)
