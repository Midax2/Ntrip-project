cmake_minimum_required(VERSION 3.22.1)
project("rtklib")

# Add RTKLIB include directory
include_directories(${CMAKE_SOURCE_DIR}/rtklib)

# Manually specify only the RTKLIB files we need (excluding problematic ones for Android)
set(RTKLIB_CORE_SOURCES
    ${CMAKE_SOURCE_DIR}/rtklib/datum.c
    ${CMAKE_SOURCE_DIR}/rtklib/ephemeris.c
    ${CMAKE_SOURCE_DIR}/rtklib/geoid.c
    ${CMAKE_SOURCE_DIR}/rtklib/ionex.c
    ${CMAKE_SOURCE_DIR}/rtklib/lambda.c
    ${CMAKE_SOURCE_DIR}/rtklib/options.c
    ${CMAKE_SOURCE_DIR}/rtklib/pntpos.c
    ${CMAKE_SOURCE_DIR}/rtklib/ppp.c
    ${CMAKE_SOURCE_DIR}/rtklib/ppp_ar.c
    ${CMAKE_SOURCE_DIR}/rtklib/preceph.c
    ${CMAKE_SOURCE_DIR}/rtklib/qzslex.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcvraw.c
    ${CMAKE_SOURCE_DIR}/rtklib/rinex.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtcm.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtcm2.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtcm3.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtcm3e.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtkcmn.c
    ${CMAKE_SOURCE_DIR}/rtklib/rtkpos.c
    ${CMAKE_SOURCE_DIR}/rtklib/sbas.c
    ${CMAKE_SOURCE_DIR}/rtklib/solution.c
    ${CMAKE_SOURCE_DIR}/rtklib/stream.c
    ${CMAKE_SOURCE_DIR}/rtklib/tle.c
)

# Add RTKLIB receiver-specific source files
set(RTKLIB_RCV_SOURCES
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/binex.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/crescent.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/gw10.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/javad.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/novatel.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/nvs.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/skytraq.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/ss2.c
    ${CMAKE_SOURCE_DIR}/rtklib/rcv/ublox.c
)

# Combine all sources
set(ALL_RTKLIB_SOURCES ${RTKLIB_CORE_SOURCES} ${RTKLIB_RCV_SOURCES})

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

