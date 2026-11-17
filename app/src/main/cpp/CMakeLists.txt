cmake_minimum_required(VERSION 3.22.1)
project(nexus-llama LANGUAGES C CXX)

include(FetchContent)

# Keep the native runtime pinned so CI and local builds use the same llama.cpp API.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ad9d9e8f50e6ff9a6bfbbdd3aef6d74a8428cf4e
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(nexus-llama SHARED native-lib.cpp)
target_compile_features(nexus-llama PRIVATE cxx_std_17)
target_include_directories(nexus-llama PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_link_libraries(nexus-llama PRIVATE llama android log)
