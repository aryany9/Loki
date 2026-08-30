cmake_minimum_required(VERSION 3.22.1)
project("lokiwhisper" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "whisper: build tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "whisper: build examples" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "whisper: build server" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build static" FORCE)

add_subdirectory(whisper.cpp)

add_library(lokiwhisper SHARED
    loki_whisper_bridge.cpp
)

target_compile_options(lokiwhisper PRIVATE -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16)

target_include_directories(lokiwhisper PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include
)

target_link_libraries(lokiwhisper PRIVATE
    whisper
    ggml
    android
    log
)
