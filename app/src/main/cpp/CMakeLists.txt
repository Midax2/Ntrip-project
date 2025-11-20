cmake_minimum_required(VERSION 3.22.1)
project("rtklib")

# Add RTKLIB source files here (you need to obtain RTKLIB source code)
# This is a placeholder - you'll need to add actual RTKLIB .c files
add_library(rtklib SHARED
    rtklib_jni.cpp
    # Add RTKLIB core files:
    # rtklib/rtkpos.c
    # rtklib/rtkcmn.c
    # rtklib/ephemeris.c
    # etc.
)

find_library(log-lib log)

target_link_libraries(rtklib ${log-lib})
