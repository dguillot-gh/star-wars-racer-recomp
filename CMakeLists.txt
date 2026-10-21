cmake_minimum_required(VERSION 3.20)

project(SWE1Racer VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# =============================================================================
# Options
# =============================================================================
set(N64_MODERN_RUNTIME_DIR "${CMAKE_SOURCE_DIR}/lib/N64ModernRuntime" CACHE PATH
    "Path to N64ModernRuntime")
set(RT64_DIR "${CMAKE_SOURCE_DIR}/lib/rt64" CACHE PATH
    "Path to RT64 renderer")

# =============================================================================
# SDL2
# =============================================================================
if (ANDROID)
    set(SDL_SHARED ON CACHE BOOL "Build SDL2 for the Android shared-library loader" FORCE)
    set(SDL_STATIC OFF CACHE BOOL "Build SDL2 as a shared library" FORCE)
    set(SDL_TEST OFF CACHE BOOL "Don't build SDL2 tests" FORCE)
    add_subdirectory(${CMAKE_SOURCE_DIR}/lib/SDL2 ${CMAKE_BINARY_DIR}/SDL2)
    list(PREPEND CMAKE_MODULE_PATH "${CMAKE_SOURCE_DIR}/cmake")
    find_package(SDL2 REQUIRED MODULE)
else()
    find_package(SDL2 CONFIG REQUIRED)
endif()

# =============================================================================
# RT64 Renderer
# =============================================================================
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "Don't build Zstandard command line tools" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "Don't build Zstandard tests" FORCE)
set(ZSTD_BUILD_DICTBUILDER OFF CACHE BOOL "Don't build Zstandard dictionary tooling" FORCE)
set(RT64_STATIC ON CACHE BOOL "Build RT64 as static library" FORCE)
if (ANDROID)
    set(RT64_SDL_WINDOW_VULKAN ON CACHE BOOL "Use SDL Vulkan windows" FORCE)
endif()
add_subdirectory(${RT64_DIR} ${CMAKE_BINARY_DIR}/rt64)

# =============================================================================
# N64ModernRuntime (librecomp + ultramodern)
# =============================================================================
add_subdirectory(${N64_MODERN_RUNTIME_DIR} ${CMAKE_BINARY_DIR}/N64ModernRuntime)

# =============================================================================
# Recompiled Functions Library
# =============================================================================
file(GLOB RECOMP_SOURCES "RecompiledFuncs/*.c")

add_library(RecompiledFuncs STATIC ${RECOMP_SOURCES})

target_include_directories(RecompiledFuncs PRIVATE
    ${N64_MODERN_RUNTIME_DIR}/N64Recomp/include  # recomp.h
    ${CMAKE_SOURCE_DIR}/RecompiledFuncs           # funcs.h
)

if(MSVC)
    target_compile_options(RecompiledFuncs PRIVATE "/FI${CMAKE_SOURCE_DIR}/include/recomp_hooks.h")
else()
    target_compile_options(RecompiledFuncs PRIVATE "-include${CMAKE_SOURCE_DIR}/include/recomp_hooks.h")
endif()

# Disable strict aliasing for recompiled code (MIPS memory access patterns)
if(MSVC)
    # MSVC doesn't have strict aliasing by default
else()
    target_compile_options(RecompiledFuncs PRIVATE -fno-strict-aliasing)
endif()

# =============================================================================
# Main Executable
# =============================================================================
set(SWE1RACER_HOST_SOURCES
    src/main.cpp
    src/section_table.cpp
    src/rt64_render_context.cpp
    src/audio.cpp
    rsp/aspMain.cpp     # RSPRecomp output from aspMain.toml (made from your ROM, not committed)
)

if (ANDROID)
    add_library(SWE1Racer SHARED
        ${SWE1RACER_HOST_SOURCES}
    )
    set_target_properties(SWE1Racer PROPERTIES OUTPUT_NAME main)
else()
    add_executable(SWE1Racer
        ${SWE1RACER_HOST_SOURCES}
    )
endif()

target_include_directories(SWE1Racer PRIVATE
    ${N64_MODERN_RUNTIME_DIR}/N64Recomp/include     # recomp.h
    ${N64_MODERN_RUNTIME_DIR}/librecomp/include      # librecomp headers
    ${N64_MODERN_RUNTIME_DIR}/ultramodern/include    # ultramodern headers
    ${RT64_DIR}/src                                   # RT64 headers
    ${RT64_DIR}/src/contrib                           # RT64 contrib (json, im3d, etc.)
    ${RT64_DIR}/src/contrib/hlslpp/include            # hlsl++
    ${RT64_DIR}/src/contrib/plume                     # plume render interface
    ${RT64_DIR}/src/contrib/imgui                     # imgui
    ${RT64_DIR}/src/contrib/mupen64plus-core/src/api  # mupen64plus API types
    ${CMAKE_SOURCE_DIR}/RecompiledFuncs              # funcs.h
    ${CMAKE_SOURCE_DIR}/include
)

# RT64 requires these defines for its headers (normally set at directory scope)
target_compile_definitions(SWE1Racer PRIVATE NOMINMAX HLSL_CPU FFX_GCC)

target_link_libraries(SWE1Racer PRIVATE
    RecompiledFuncs
    librecomp
    ultramodern
    rt64
    SDL2::SDL2
)

if(WIN32)
    target_link_libraries(SWE1Racer PRIVATE SDL2::SDL2main)
    target_link_libraries(SWE1Racer PRIVATE dxgi d3d12 d3dcompiler dbghelp)
    target_link_options(SWE1Racer PRIVATE /MAP)
elseif(ANDROID)
    target_link_libraries(SWE1Racer PRIVATE android log vulkan)
endif()
