cmake_minimum_required(VERSION 3.22.1)
project("lokillama" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# Configure llama.cpp options
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: openmp" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: llamafile" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build static" FORCE)

add_subdirectory(llama.cpp)

add_library(lokillama SHARED
    loki_llama_bridge.cpp
)

target_compile_options(lokillama PRIVATE -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16)

target_include_directories(lokillama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
    llama.cpp/vendor
)

target_link_libraries(lokillama PRIVATE
    llama
    llama-common
    ggml
    android
    log
)
