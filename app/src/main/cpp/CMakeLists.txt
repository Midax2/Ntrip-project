cmake_minimum_required(VERSION 3.22.1)
project("rtklib")

# =============================================================================
# RTK LIBRARY NATIVE IMPLEMENTATION
# =============================================================================
#
# CURRENT STATUS: STUB IMPLEMENTATION
#
# This CMake file builds a JNI wrapper for RTK positioning functionality.
# The current implementation in rtklib_jni.cpp is a STUB that returns dummy
# data for testing and development purposes.
#
# PRODUCTION IMPLEMENTATION REQUIREMENTS:
#
# To make this a production-ready RTK solution, you need to:
#
# 1. Obtain RTKLIB source code from:
#    https://github.com/tomojitakasu/RTKLIB
#
# 2. Add RTKLIB core files to this CMakeLists.txt:
#    - rtklib/rtkpos.c    (RTK positioning)
#    - rtklib/rtkcmn.c    (Common functions)
#    - rtklib/ephemeris.c (Satellite ephemeris)
#    - rtklib/preceph.c   (Precise ephemeris)
#    - rtklib/rinex.c     (RINEX file handling)
#    - rtklib/rtcm.c      (RTCM message decoding)
#    - rtklib/rtcm2.c     (RTCM v2)
#    - rtklib/rtcm3.c     (RTCM v3)
#    - rtklib/ionex.c     (Ionosphere models)
#    - etc.
#
# 3. Implement actual RTK processing in rtklib_jni.cpp:
#    - Convert Android GNSS measurements to RTKLIB format
#    - Call RTKLIB positioning functions
#    - Handle RTCM corrections properly
#
# 4. Add compiler flags for optimization and warnings:
#    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -O2")
#
# =============================================================================

# Build shared library with JNI wrapper
add_library(rtklib SHARED
    rtklib_jni.cpp

    # TODO: Add RTKLIB core source files here when integrating real RTKLIB:
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rtkpos.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rtkcmn.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/ephemeris.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/preceph.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rinex.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rtcm.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rtcm2.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/rtcm3.c
    # ${CMAKE_CURRENT_SOURCE_DIR}/rtklib/ionex.c
)

# Find Android logging library
find_library(log-lib log)

# Link libraries
target_link_libraries(rtklib ${log-lib})

# TODO: When adding real RTKLIB, include header directories:
# target_include_directories(rtklib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/rtklib)

