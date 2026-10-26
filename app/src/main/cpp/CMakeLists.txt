cmake_minimum_required(VERSION 3.22.1)

project(memo_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp b11460, CPU-only, static, linked into our JNI shared libraries.
# Pinned by tag and SHA-256 (see docs/ENGINEERING_LOG.md D-018 and D-028).
include(FetchContent)
include(ExternalProject)

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11460.tar.gz
    URL_HASH SHA256=80c50949a5f4c9b63fb216bd362289f4e8ae64e47625eb7a87e8381c0f88b36a
)
# Download and unpack only. Each llama build below runs as its own CMake project, so its
# targets (llama, ggml) cannot collide with each other.
FetchContent_Populate(llama_cpp)
set(LLAMA_SRC "${llama_cpp_SOURCE_DIR}")

# Options forwarded to each nested llama.cpp build: static, CPU-only, no extras.
set(LLAMA_FLAGS
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=OFF
    -DLLAMA_BUILD_TOOLS=OFF
    -DLLAMA_BUILD_COMMON=OFF
    -DLLAMA_BUILD_APP=OFF
    -DLLAMA_BUILD_UI=OFF
    -DLLAMA_USE_PREBUILT_UI=OFF
    -DLLAMA_BUILD_MTMD=OFF
    -DLLAMA_CURL=OFF
    -DLLAMA_OPENSSL=OFF
    -DGGML_NATIVE=OFF
    -DGGML_OPENMP=OFF
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_BUILD_TYPE=Release
)

# Android toolchain variables are forwarded so that each nested build uses the same NDK,
# ABI, platform and STL as this project.
set(ANDROID_FORWARD "")
foreach(v ANDROID_NDK ANDROID_ABI ANDROID_PLATFORM ANDROID_STL ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES)
    if(DEFINED ${v})
        list(APPEND ANDROID_FORWARD "-D${v}=${${v}}")
    endif()
endforeach()

# Builds llama.cpp into its own binary dir with the given ARM CPU arch (empty = no arch flag),
# and returns the static archives to link (llama, ggml, ggml-base, ggml-cpu).
#   NAME    ep name and binary-dir suffix
#   ARCH    value for GGML_CPU_ARM_ARCH, or "" to keep the compiler default
#   OUT     name of the variable that receives the library list
function(memo_build_llama NAME ARCH OUT)
    set(bin "${CMAKE_BINARY_DIR}/llama-${NAME}")
    set(arch_args "")
    if(NOT ARCH STREQUAL "")
        set(arch_args "-DGGML_CPU_ARM_ARCH=${ARCH}")
    endif()
    set(libs
        "${bin}/src/libllama.a"
        "${bin}/ggml/src/libggml.a"
        "${bin}/ggml/src/libggml-base.a"
        "${bin}/ggml/src/libggml-cpu.a")

    ExternalProject_Add(ep_llama_${NAME}
        PREFIX "${CMAKE_BINARY_DIR}/ep-${NAME}"
        SOURCE_DIR "${LLAMA_SRC}"
        BINARY_DIR "${bin}"
        DOWNLOAD_COMMAND ""
        UPDATE_COMMAND ""
        INSTALL_COMMAND ""
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            ${ANDROID_FORWARD}
            ${LLAMA_FLAGS}
            ${arch_args}
        BUILD_BYPRODUCTS ${libs}
        BUILD_ALWAYS OFF
    )
    set(${OUT} "${libs}" PARENT_SCOPE)
endfunction()

set(MEMO_LLAMA_INCLUDES
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Two variants of the same JNI sources, selected at load time by LlamaNative from
    # /proc/cpuinfo. Generic (ARMv8.0) runs on every arm64 phone. Dotprod (ARMv8.2-A with dotprod
    # and fp16) runs only where the CPU reports asimddp and fphp.
    memo_build_llama(generic "armv8-a" GENERIC_LIBS)
    memo_build_llama(dotprod "armv8.2-a+dotprod+fp16" DOTPROD_LIBS)

    add_library(memo_llama SHARED memo_llama.cpp)
    target_include_directories(memo_llama PRIVATE ${MEMO_LLAMA_INCLUDES})
    add_dependencies(memo_llama ep_llama_generic)
    target_link_libraries(memo_llama PRIVATE -Wl,--start-group ${GENERIC_LIBS} -Wl,--end-group log android)

    add_library(memo_llama_dp SHARED memo_llama.cpp)
    target_include_directories(memo_llama_dp PRIVATE ${MEMO_LLAMA_INCLUDES})
    add_dependencies(memo_llama_dp ep_llama_dotprod)
    target_link_libraries(memo_llama_dp PRIVATE -Wl,--start-group ${DOTPROD_LIBS} -Wl,--end-group log android)
else()
    # x86_64 (emulator) and any other ABI: one generic build, no ARM flags.
    memo_build_llama(generic "" GENERIC_LIBS)

    add_library(memo_llama SHARED memo_llama.cpp)
    target_include_directories(memo_llama PRIVATE ${MEMO_LLAMA_INCLUDES})
    add_dependencies(memo_llama ep_llama_generic)
    target_link_libraries(memo_llama PRIVATE -Wl,--start-group ${GENERIC_LIBS} -Wl,--end-group log android)
endif()
