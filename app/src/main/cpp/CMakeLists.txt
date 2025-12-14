cmake_minimum_required(VERSION 3.22.1)
project("rtklib")

# Add RTKLIB include directory
include_directories(${CMAKE_SOURCE_DIR}/rtklib)

# Add all RTKLIB source files (main directory)
file(GLOB RTKLIB_SOURCES "${CMAKE_SOURCE_DIR}/rtklib/*.c")

# Add RTKLIB receiver-specific source files
file(GLOB RTKLIB_RCV_SOURCES "${CMAKE_SOURCE_DIR}/rtklib/rcv/*.c")

# Combine all sources
set(ALL_RTKLIB_SOURCES ${RTKLIB_SOURCES} ${RTKLIB_RCV_SOURCES})

# Create the shared library
add_library(rtklib SHARED
        rtklib_wrapper.cpp
        ${ALL_RTKLIB_SOURCES}
)

# Link required libraries
target_link_libraries(rtklib
        android
        log
        m  # Math library needed by RTKLIB
)

# Add compiler flags for RTKLIB
target_compile_definitions(rtklib PRIVATE
        ENAGLO      # Enable GLONASS
        ENAGAL      # Enable Galileo
        ENAQZS      # Enable QZSS
        ENACMP      # Enable BeiDou
)

# Add warning suppressions for RTKLIB legacy code
target_compile_options(rtklib PRIVATE
        -Wno-format
        -Wno-unused-variable
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-implicit-function-declaration
)

