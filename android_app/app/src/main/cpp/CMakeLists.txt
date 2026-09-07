cmake_minimum_required(VERSION 3.22.1)
project("sparkshield_qnn_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    sparkshield_qnn_jni
    SHARED
    qnn_inference_jni.cpp
)

target_include_directories(
    sparkshield_qnn_jni
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    sparkshield_qnn_jni
    log
    android
)
