cmake_minimum_required(VERSION 3.22.1)
project(MineGAndroidBridge LANGUAGES C CXX)

set(MINEG_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../core")
add_subdirectory("${MINEG_CORE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mineg-core")
target_sources(mineg_core PRIVATE native_bridge.cpp)
target_include_directories(mineg_core PRIVATE "${MINEG_CORE_DIR}/src")
