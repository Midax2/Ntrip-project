# RTK Native Library Implementation

## Current Status: STUB IMPLEMENTATION ⚠️

This directory contains a **stub implementation** of the RTK (Real-Time Kinematic) positioning native library. The current code provides a functional JNI bridge that allows the Android app to compile and run, but it returns dummy positioning data.

## What Works Now

✅ **JNI Bridge Complete:** All native methods are implemented
✅ **App Compiles:** CMake builds successfully
✅ **App Runs:** No crashes, shows dummy coordinates
✅ **RTCM Handling:** Accepts RTCM data (but doesn't process it)
✅ **State Management:** Tracks solution status

## What's Missing

❌ **Real RTKLIB Integration:** No actual RTK positioning calculations
❌ **GNSS Processing:** Measurements are logged but not used
❌ **RTCM Parsing:** Corrections are ignored
❌ **Accurate Positioning:** Returns hardcoded coordinates

## File Overview

### `rtklib_jni.cpp`
JNI wrapper that bridges Kotlin code to native C++ implementation.

**Current Implementation:**
- Returns dummy coordinates (Gdansk/Warsaw area)
- Simulates solution status improvements
- Logs all function calls for debugging
- Properly handles JNI array marshalling

**Functions Implemented:**
1. `initRtkEngine()` - Initializes stub state
2. `processGnssMeasurements()` - Returns dummy position
3. `processRtcmData()` - Accepts but doesn't process RTCM
4. `getSolutionStatus()` - Returns simulated status
5. `shutdownRtkEngine()` - Cleanup stub state

### `CMakeLists.txt`
CMake build configuration for the native library.

**Current Configuration:**
- Builds `rtklib` shared library
- Links Android logging library
- Includes comprehensive documentation

## Integrating Real RTKLIB

To convert this stub into a production RTK solution:

### Step 1: Obtain RTKLIB Source Code

Download RTKLIB from the official repository:
```
https://github.com/tomojitakasu/RTKLIB
```

Extract the core source files to a subdirectory, e.g., `rtklib/`

### Step 2: Update CMakeLists.txt

Uncomment and update the source file list:
```cmake
add_library(rtklib SHARED
    rtklib_jni.cpp
    rtklib/rtkpos.c
    rtklib/rtkcmn.c
    rtklib/ephemeris.c
    rtklib/preceph.c
    rtklib/rinex.c
    rtklib/rtcm.c
    rtklib/rtcm2.c
    rtklib/rtcm3.c
    rtklib/ionex.c
    # ... add all required RTKLIB source files
)

target_include_directories(rtklib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/rtklib)
```

### Step 3: Implement Real Processing in rtklib_jni.cpp

Replace stub implementations with actual RTKLIB calls:

#### `initRtkEngine()`
```cpp
#include "rtklib.h"

static rtk_t rtk;
static nav_t nav;

JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_initRtkEngine(JNIEnv* env, jobject obj) {
    // Initialize RTK state
    rtkinit(&rtk, &prcopt_default);
    
    // Initialize navigation data
    nav.n = 0;
    nav.nmax = 0;
    nav.eph = NULL;
    
    return JNI_TRUE;
}
```

#### `processGnssMeasurements()`
```cpp
JNIEXPORT jdoubleArray JNICALL
Java_com_pg_rtk_service_RtkLibNative_processGnssMeasurements(...) {
    // Convert Android measurements to RTKLIB obs_t structure
    obs_t obs[MAXOBS];
    int nobs = convertToRtkLibObs(svidArray, prArray, cpArray, cn0Array, size, obs);
    
    // Perform positioning
    sol_t sol;
    char msg[128];
    int status = rtkpos(&rtk, obs, nobs, &nav);
    
    // Extract solution
    double result[7];
    result[0] = sol.rr[0] * R2D; // latitude
    result[1] = sol.rr[1] * R2D; // longitude
    result[2] = sol.rr[2];       // height
    result[3] = (double)sol.stat; // solution status
    // ... populate uncorrected position
    
    return resultArray;
}
```

#### `processRtcmData()`
```cpp
JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_processRtcmData(...) {
    // Parse RTCM3 messages
    rtcm_t rtcm;
    rtcminit(&rtcm);
    
    for (int i = 0; i < length; i++) {
        int ret = input_rtcm3(&rtcm, dataBytes[i]);
        if (ret > 0) {
            // Update navigation data with RTCM observations
            updateNavData(&nav, &rtcm);
        }
    }
    
    rtcmfree(&rtcm);
    return JNI_TRUE;
}
```

### Step 4: Handle Data Conversion

Implement helper functions to convert between Android and RTKLIB formats:

```cpp
int convertToRtkLibObs(
    jint* svid,
    jdouble* pseudorange,
    jdouble* carrierPhase,
    jdouble* cn0,
    int size,
    obs_t* obs) {
    
    for (int i = 0; i < size; i++) {
        obs[i].sat = svid[i];
        obs[i].P[0] = pseudorange[i];
        obs[i].L[0] = carrierPhase[i];
        obs[i].SNR[0] = (unsigned char)(cn0[i] * 4.0);
        // Set other observation fields...
    }
    
    return size;
}
```

### Step 5: Configure RTK Processing Options

Set appropriate processing options for your use case:

```cpp
prcopt_t prcopt = prcopt_default;
prcopt.mode = PMODE_KINEMA;  // Kinematic RTK
prcopt.navsys = SYS_GPS | SYS_GLO | SYS_GAL;  // GPS + GLONASS + Galileo
prcopt.refpos = 1;  // Base station position from RTCM
prcopt.glomodear = 1;  // GLONASS AR mode
```

## Testing the Implementation

### With Stub (Current):
The app will:
- ✅ Connect to NTRIP caster
- ✅ Receive RTCM corrections
- ✅ Process GNSS measurements
- ⚠️ Show dummy coordinates (hardcoded)
- ⚠️ Simulate solution status improvements

### With Real RTKLIB:
The app should:
- ✅ Connect to NTRIP caster
- ✅ Receive RTCM corrections
- ✅ Process GNSS measurements
- ✅ Compute actual RTK position
- ✅ Show real coordinates with cm-level accuracy
- ✅ Display true solution status (FLOAT/FIX)

## Performance Considerations

### Memory
- RTKLIB allocates memory for satellite ephemeris
- Navigation data structures can grow large
- Consider memory limits on mobile devices

### CPU
- RTK processing is computationally intensive
- Run processing on background thread (already done in Kotlin layer)
- Consider power consumption on battery devices

### Accuracy
- RTK requires good GNSS signal (open sky)
- Base station must be within ~20-30 km
- Network corrections must be recent (age < 30s)

## Debugging Tips

### Enable Verbose Logging
Uncomment debug logs in rtklib_jni.cpp:
```cpp
#define DEBUG_VERBOSE
#ifdef DEBUG_VERBOSE
LOGI("Measurement %d: SV=%d, PR=%.3f, CP=%.3f", i, svid[i], pr[i], cp[i]);
#endif
```

### Check Logcat Output
```bash
adb logcat | grep RTKLibNative
```

### Monitor Solution Status
Watch for status transitions:
- SINGLE (1) → No corrections yet
- FLOAT (4) → Corrections applied, ambiguities not fixed
- FIX (5) → Ambiguities fixed, cm-level accuracy

## References

- **RTKLIB Manual:** http://www.rtklib.com/rtklib.htm
- **RTKLIB GitHub:** https://github.com/tomojitakasu/RTKLIB
- **RTCM Standard:** https://www.rtcm.org/
- **Android GNSS API:** https://developer.android.com/guide/topics/sensors/gnss

## License Considerations

⚠️ **Important:** RTKLIB is licensed under BSD 2-Clause License. Ensure compliance when integrating.

## Support

For questions about:
- **Stub implementation:** Check this README and comments in source files
- **RTKLIB integration:** Refer to RTKLIB documentation
- **Android GNSS API:** Check Android developer documentation

---

**Last Updated:** December 2025
**Status:** Stub implementation - ready for RTKLIB integration

