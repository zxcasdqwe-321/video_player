# Minimally required CMake compiler version
cmake_minimum_required(VERSION 3.18.1)

# Declare player native project
project("nativeplayer")

# Find Android specific platform libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv2)

# Set the C++ Standard version
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ------------------------------------------------------------------------------
# FFmpeg Linking Reference - High-performance Native decoders
# Detailed setup showing prebuilt static or shared libraries inclusions
# ------------------------------------------------------------------------------
# add_library(avcodec SHARED IMPORTED)
# set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libavcodec.so)
#
# add_library(avformat SHARED IMPORTED)
# set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libavformat.so)
# ------------------------------------------------------------------------------

# Compile custom player media source modules
add_library(
    # Dynamic libraries output naming
    nativeplayer
    SHARED
    
    # Core Engine Components
    native-lib.cpp
    AVClock.cpp
    VideoDemuxer.cpp
    VideoDecoder.cpp
    VideoRenderer.cpp
)

# Specify Include folders
target_include_directories(
    nativeplayer
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    # Prebuilt FFmpeg headers path would go here:
    # ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Inject dependency links
target_link_libraries(
    nativeplayer
    
    # Android Platform libraries
    ${log-lib}
    ${android-lib}
    ${gles-lib}
    
    # Static link pointers for FFmpeg would be added here:
    # avformat
    # avcodec
    # avutil
)
