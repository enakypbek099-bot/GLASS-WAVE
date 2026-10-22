cmake_minimum_required(VERSION 3.22.1)

project(gw_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OBOE_DIR
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/oboe
)

if(NOT EXISTS ${OBOE_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "Google Oboe табылмады. scripts/bootstrap_oboe.sh орындаңыз."
    )
endif()

set(OBOE_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${OBOE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}/oboe
)

add_library(
    gw_native
    SHARED
    GwAudioEngine.cpp
    JniBridge.cpp
)

target_include_directories(
    gw_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(
    gw_native
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

target_link_libraries(
    gw_native
    PRIVATE
    oboe
    android
    log
)
