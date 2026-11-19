cmake_minimum_required(VERSION 3.22)
project(retrieve_native VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Platform-independent engine code. Built for Android (via the NDK) and for desktop (tests, CLI).
add_library(retrieve_core STATIC
    core/version.cpp
    index/tokenizer.cpp
    index/builder.cpp
    index/index.cpp
)
target_include_directories(retrieve_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(retrieve_core PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(retrieve_core PRIVATE RETRIEVE_VERSION="${PROJECT_VERSION}")

if(ANDROID)
    # The shared library the app loads with System.loadLibrary("retrieve").
    add_library(retrieve SHARED jni/retrieve_jni.cpp)
    target_link_libraries(retrieve PRIVATE retrieve_core log)
    target_compile_options(retrieve PRIVATE -Wall -Wextra)
else()
    option(RETRIEVE_BUILD_TESTS "Build GoogleTest unit tests" ON)
    if(RETRIEVE_BUILD_TESTS)
        include(FetchContent)
        FetchContent_Declare(googletest
            URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.zip
            DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
        FetchContent_MakeAvailable(googletest)
        enable_testing()

        add_executable(retrieve_tests
            tests/version_test.cpp
            tests/tokenizer_test.cpp
            tests/index_test.cpp
        )
        target_link_libraries(retrieve_tests PRIVATE retrieve_core GTest::gtest_main)
        include(GoogleTest)
        gtest_discover_tests(retrieve_tests)
    endif()
endif()
