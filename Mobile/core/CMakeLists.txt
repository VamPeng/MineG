cmake_minimum_required(VERSION 3.18)
project(MineGCore VERSION 1.0.0 LANGUAGES C CXX)

if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW)
endif()

option(MINEG_BUILD_TESTS "Build host core tests" ON)

include(FetchContent)
FetchContent_Declare(
  mineg_sqlite_source
  URL https://www.sqlite.org/2026/sqlite-amalgamation-3510300.zip
  URL_HASH SHA256=acb1e6f5d832484bf6d32b681e858c38add8b2acdfd42ac5df24b8afb46552b4
)
FetchContent_GetProperties(mineg_sqlite_source)
if(NOT mineg_sqlite_source_POPULATED)
  FetchContent_Populate(mineg_sqlite_source)
endif()
add_library(mineg_sqlite STATIC "${mineg_sqlite_source_SOURCE_DIR}/sqlite3.c")
set_target_properties(mineg_sqlite PROPERTIES
  C_VISIBILITY_PRESET hidden
  POSITION_INDEPENDENT_CODE ON
)
target_compile_definitions(mineg_sqlite PRIVATE
  SQLITE_DQS=0
  SQLITE_DEFAULT_FOREIGN_KEYS=1
  SQLITE_OMIT_LOAD_EXTENSION=1
  SQLITE_THREADSAFE=1
)

add_library(mineg_core SHARED
  src/c_api.cpp
  src/core.cpp
)
target_compile_features(mineg_core PUBLIC cxx_std_17)
target_include_directories(mineg_core
  PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_compile_options(mineg_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
set_target_properties(mineg_core PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)

if(ANDROID)
  if(NOT DEFINED MINEG_SODIUM_ROOT)
    message(FATAL_ERROR "MINEG_SODIUM_ROOT is required for Android")
  endif()
  add_library(mineg_sodium SHARED IMPORTED GLOBAL)
  set_target_properties(mineg_sodium PROPERTIES
    IMPORTED_LOCATION "${MINEG_SODIUM_ROOT}/jni/${ANDROID_ABI}/libsodium.so"
  )
  find_library(MINEG_LOG_LIBRARY log REQUIRED)
  target_link_libraries(mineg_core PRIVATE mineg_sodium mineg_sqlite ${MINEG_LOG_LIBRARY})
else()
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(MINEG_SODIUM REQUIRED IMPORTED_TARGET libsodium)
  target_link_libraries(mineg_core PRIVATE mineg_sqlite PkgConfig::MINEG_SODIUM)

  if(MINEG_BUILD_TESTS)
    enable_testing()
    add_executable(mineg_core_tests tests/core_test.cpp)
    target_compile_features(mineg_core_tests PRIVATE cxx_std_17)
    target_compile_definitions(mineg_core_tests PRIVATE
      MINEG_CORE_SOURCE_DIR="${CMAKE_CURRENT_SOURCE_DIR}"
    )
    target_include_directories(mineg_core_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    target_compile_options(mineg_core_tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(mineg_core_tests PRIVATE mineg_core PkgConfig::MINEG_SODIUM)
    add_test(NAME mineg_core_contract COMMAND mineg_core_tests)
  endif()
endif()
