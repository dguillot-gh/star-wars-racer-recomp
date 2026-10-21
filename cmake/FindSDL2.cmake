# SDL2 is built from the project's pinned source submodule on Android. This
# module lets RT64's CMake find that in-tree target without requiring a second
# SDL2 package installation.

if (NOT TARGET SDL2::SDL2)
    if (TARGET SDL2-static)
        add_library(SDL2::SDL2 ALIAS SDL2-static)
    elseif (TARGET SDL2)
        add_library(SDL2::SDL2 ALIAS SDL2)
    else()
        set(SDL2_FOUND FALSE)
        set(SDL2_NOT_FOUND_MESSAGE "SDL2 must be added before RT64 on Android")
        return()
    endif()
endif()

set(SDL2_FOUND TRUE)
set(SDL2_LIBRARIES SDL2::SDL2)
set(SDL2_INCLUDE_DIRS "${CMAKE_SOURCE_DIR}/lib/SDL2/include")
