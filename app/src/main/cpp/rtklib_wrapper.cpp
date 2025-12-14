#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>

extern "C" {
#include "rtklib/rtklib.h"
}

#define LOG_TAG "RTKLibNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global RTKLIB structures
static rtcm_t gRtcm = {0};
static rtk_t gRtk = {0};
static nav_t gNav = {0};
static obs_t gObs = {0};
static sol_t gSol = {0};
static prcopt_t gPrcOpt = {0};
static bool gInitialized = false;

// Default processing options for RTK
void setDefaultPrcOpt(prcopt_t *opt) {
    memset(opt, 0, sizeof(prcopt_t));

    opt->mode = PMODE_SINGLE;       // Single point positioning (can be upgraded to RTK)
    opt->soltype = 0;                // Forward solution
    opt->nf = 2;                     // Number of frequencies (L1, L2)
    opt->navsys = SYS_GPS | SYS_GLO | SYS_GAL | SYS_QZS | SYS_CMP; // All systems
    opt->elmin = 15.0 * D2R;        // Elevation mask (15 degrees)
    opt->sateph = EPHOPT_BRDC;      // Broadcast ephemeris
    opt->modear = 1;                 // AR mode (0:off, 1:continuous, 2:instantaneous)
    opt->glomodear = 1;             // GLONASS AR mode
    opt->maxout = 5;                 // Max outage count
    opt->minlock = 0;                // Min lock count for ambiguity resolution
    opt->minfix = 10;                // Min fix count for ambiguity resolution
    opt->ionoopt = IONOOPT_BRDC;    // Ionosphere option (broadcast model)
    opt->tropopt = TROPOPT_SAAS;    // Troposphere option (Saastamoinen model)
    opt->dynamics = 0;               // Dynamics model (0:none, 1:on)
    opt->tidecorr = 0;               // Tide correction (0:off)
    opt->niter = 1;                  // Number of iterations
    opt->eratio[0] = 100.0;          // Code/phase error ratio
    opt->eratio[1] = 100.0;
    opt->err[1] = 0.003;             // Carrier phase error (m)
    opt->err[2] = 0.003;
    opt->std[0] = 30.0;              // Initial state std (position) (m)
    opt->prn[0] = 1E-4;              // Process noise std (position) (m/sqrt(s))
    opt->sclkstab = 5E-12;           // Satellite clock stability (s/s)
    opt->thresar[0] = 3.0;           // AR validation threshold
    opt->elmaskar = 15.0 * D2R;      // Elevation mask for AR
    opt->elmaskhold = 15.0 * D2R;    // Elevation mask for hold
    opt->thresslip = 0.05;           // Slip threshold
    opt->maxtdiff = 30.0;            // Max time difference for corrections (s)
    opt->maxinno = 30.0;             // Max innovation for rejecting obs (m)
    opt->maxgdop = 30.0;             // Max GDOP
    opt->posopt[0] = 0;              // Position options
    opt->posopt[1] = 0;
    opt->posopt[2] = 0;
    opt->posopt[3] = 0;
    opt->posopt[4] = 0;
}

extern "C" {

/**
 * Initialize RTK processing engine with real RTKLIB
 */
JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_initRtkEngine(JNIEnv* env, jobject obj) {
    (void)env; (void)obj; // Unused parameters
    LOGI("Initializing RTK Engine with RTKLIB %s", VER_RTKLIB);

    if (gInitialized) {
        LOGI("RTK Engine already initialized, reinitializing...");
        rtkfree(&gRtk);
    }

    // Initialize RTCM decoder
    if (!init_rtcm(&gRtcm)) {
        LOGE("Failed to initialize RTCM decoder");
        return JNI_FALSE;
    }

    // Set default processing options
    setDefaultPrcOpt(&gPrcOpt);

    // Initialize RTK structure
    rtkinit(&gRtk, &gPrcOpt);

    // Initialize navigation data
    memset(&gNav, 0, sizeof(nav_t));

    // Initialize observation data
    memset(&gObs, 0, sizeof(obs_t));

    gInitialized = true;
    LOGI("RTK Engine initialized successfully");
    LOGI("Supported systems (requested in prcopt): GPS=%d GLO=%d GAL=%d QZS=%d BDS=%d",
         (gPrcOpt.navsys & SYS_GPS) != 0,
         (gPrcOpt.navsys & SYS_GLO) != 0,
         (gPrcOpt.navsys & SYS_GAL) != 0,
         (gPrcOpt.navsys & SYS_QZS) != 0,
         (gPrcOpt.navsys & SYS_CMP) != 0);

    // Also log which systems are actually compiled into this RTKLIB build
#if (NSATGAL>0)
    LOGI("RTKLIB build: Galileo support enabled (NSATGAL=%d)", NSATGAL);
#else
    LOGI("RTKLIB build: Galileo support NOT enabled");
#endif
#if (NSATCMP>0)
    LOGI("RTKLIB build: BeiDou support enabled (NSATCMP=%d)", NSATCMP);
#else
    LOGI("RTKLIB build: BeiDou support NOT enabled");
#endif
#if (NSATQZS>0)
    LOGI("RTKLIB build: QZSS support enabled (NSATQZS=%d)", NSATQZS);
#else
    LOGI("RTKLIB build: QZSS support NOT enabled");
#endif

    return JNI_TRUE;
}

/**
 * Convert Android constellation ID to RTKLIB system ID
 */
static int constellation2sys(int constellation) {
    switch (constellation) {
        case 1: return SYS_GPS;     // GPS
        case 3: return SYS_GLO;     // GLONASS
#if (NSATGAL>0)
        case 6: return SYS_GAL;     // Galileo
#else
        case 6: return SYS_NONE;    // Galileo not compiled in
#endif
        // Android GnssMeasurement.constellationType values:
        // 1=GPS, 2=SBAS, 3=GLONASS, 4=QZSS, 5=BEIDOU, 6=GALILEO
#if (NSATQZS>0)
        case 4: return SYS_QZS;     // QZSS
#else
        case 4: return SYS_NONE;    // QZSS not compiled in
#endif
#if (NSATCMP>0)
        case 5: return SYS_CMP;     // BeiDou
#else
        case 5: return SYS_NONE;    // BeiDou not compiled in
#endif
        default: return SYS_NONE;
    }
}

/**
 * Convert constellation + SVID to RTKLIB satellite number
 */
static int svid2satno(int constellation, int svid) {
    int sys = constellation2sys(constellation);

    if (sys == SYS_NONE) {
        return 0;
    }

    // Use RTKLIB's satno conversion to respect PRN ranges and offsets
    int sat = satno(sys, svid);
    if (sat == 0 || sat > MAXSAT) {
        // If direct mapping failed, log and return 0
        LOGD("svid2satno: failed mapping for constellation=%d svid=%d -> sat=%d", constellation, svid, sat);
        return 0;
    }
    return sat;
}

/**
 * Process raw GNSS measurements using RTKLIB
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_pg_rtk_service_RtkLibNative_processGnssMeasurements(
    JNIEnv* env, jobject obj,
    jlong time,
    jintArray svid,
    jintArray constellation,
    jdoubleArray pseudorange,
    jdoubleArray carrierPhase,
    jdoubleArray cn0,
    jint size) {

    (void)obj; // Unused parameter

    if (!gInitialized) {
        LOGE("RTK Engine not initialized");
        return nullptr;
    }

    // Get array pointers
    jint* svidArray = env->GetIntArrayElements(svid, nullptr);
    jint* constArray = env->GetIntArrayElements(constellation, nullptr);
    jdouble* prArray = env->GetDoubleArrayElements(pseudorange, nullptr);
    jdouble* cpArray = env->GetDoubleArrayElements(carrierPhase, nullptr);
    jdouble* cn0Array = env->GetDoubleArrayElements(cn0, nullptr);

    LOGD("Processing %d GNSS measurements", size);

    // Convert Android measurements to RTKLIB observation format
    gObs.n = 0;
    if (gObs.nmax < size) {
        gObs.data = (obsd_t*)realloc(gObs.data, sizeof(obsd_t) * size);
        gObs.nmax = size;
    }

    // Convert GPS time from nanoseconds
    gtime_t obsTime;
    obsTime.time = (time_t)(time / 1000000000LL);
    obsTime.sec = (double)(time % 1000000000LL) / 1000000000.0;

    int validCount = 0;
    for (int i = 0; i < size; i++) {
        // Convert to satellite number
        int satno = svid2satno(constArray[i], svidArray[i]);
        if (satno == 0 || satno > MAXSAT) {
            LOGD("Invalid satellite: constellation=%d, svid=%d", constArray[i], svidArray[i]);
            continue;
        }

        // Check if measurement is valid
        if (prArray[i] <= 0 || prArray[i] > 3e8) {
            LOGD("Invalid pseudorange for sat %d: %.2f", satno, prArray[i]);
            continue;
        }

        if (cn0Array[i] < 10.0) { // Filter weak signals
            LOGD("Weak signal for sat %d: CN0=%.1f dB-Hz", satno, cn0Array[i]);
            continue;
        }

        // Fill observation data
        obsd_t* obs = &gObs.data[validCount];
        memset(obs, 0, sizeof(obsd_t));

        obs->time = obsTime;
        obs->sat = (unsigned char)satno;
        obs->rcv = 1; // Receiver 1

        // L1 frequency data (index 0)
        obs->P[0] = prArray[i];                    // Pseudorange (m)
        // RTKLIB expects carrier phase in cycles (L), but Android supplies ADR in meters.
        // Converting meters->cycles requires carrier wavelength (depends on system/frequency).
        // If carrier phase cycles are not provided by the Java wrapper, set to 0 to avoid
        // feeding incorrect large values into RTKLIB.
        obs->L[0] = 0.0;                           // Carrier phase (cycles) - unknown
        obs->SNR[0] = (unsigned char)(cn0Array[i] * 4.0); // SNR (0.25 dB-Hz units)
        obs->LLI[0] = 0;                           // Loss of lock indicator
        obs->code[0] = CODE_L1C;                   // Code type (L1 C/A)

        validCount++;
    }
    gObs.n = validCount;

    LOGI("Valid measurements: %d/%d", validCount, size);

    // Release Java arrays
    env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
    env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);

    // Prepare result array
    double result[7] = {0};

    if (validCount >= 4) {
        // Perform positioning
        int status;

        if (gPrcOpt.mode == PMODE_SINGLE || gRtcm.obs.n == 0) {
            // Single point positioning
            char msg[128] = {0};
            double azel[MAXSAT * 2] = {0};
            ssat_t ssat[MAXSAT] = {0};

            status = pntpos(gObs.data, gObs.n, &gNav, &gPrcOpt, &gSol, azel, ssat, msg);

            if (status) {
                LOGI("Single point solution: status=%d, ns=%d", gSol.stat, gSol.ns);
            } else {
                LOGE("Single point positioning failed: %s", msg);
            }
        } else {
            // RTK positioning with base station corrections
            // RTKLIB expects observations to contain rover (rcv==1) followed by
            // base/reference observations (rcv==2) in a single array. The RTCM
            // decoder stores base station observations in gRtcm.obs. We need to
            // merge them with the current rover observations before calling rtkpos().
            int totalObs = gObs.n + (gRtcm.obs.n > 0 ? gRtcm.obs.n : 0);
            obsd_t *combined = nullptr;

            if (totalObs > 0) {
                combined = (obsd_t*)malloc(sizeof(obsd_t) * totalObs);
                if (!combined) {
                    LOGE("Failed to allocate combined observation array (%d entries)", totalObs);
                    status = 0;
                } else {
                    // Copy rover observations (rcv = 1)
                    for (int i = 0; i < gObs.n; i++) {
                        combined[i] = gObs.data[i];
                        combined[i].rcv = 1;
                    }

                    // Append base/reference observations from rtcm (rcv = 2)
                    for (int j = 0; j < gRtcm.obs.n; j++) {
                        combined[gObs.n + j] = gRtcm.obs.data[j];
                        combined[gObs.n + j].rcv = 2;
                    }

                    // Note: rtkpos expects observations sorted by receiver (rover then base)
                    status = rtkpos(&gRtk, combined, totalObs, &gNav);

                    free(combined);
                }
            } else {
                LOGE("No observations available for RTK positioning");
                status = 0;
            }

            if (status) {
                gSol = gRtk.sol;
                LOGI("RTK solution: status=%d (1=FIX,2=FLOAT,4=DGPS,5=SINGLE), ns=%d, ratio=%.2f",
                     gSol.stat, gSol.ns, gSol.ratio);
            } else {
                LOGE("RTK positioning failed");
            }
        }

        if (status && gSol.stat > 0) {
            // Convert ECEF to geodetic (lat/lon/height)
            double pos[3];
            ecef2pos(gSol.rr, pos);

            result[0] = pos[0] * R2D;  // Latitude (degrees)
            result[1] = pos[1] * R2D;  // Longitude (degrees)
            result[2] = pos[2];         // Height (m)
            result[3] = (double)gSol.stat; // Solution status

            // Return same position for uncorrected (for now)
            result[4] = result[0];
            result[5] = result[1];
            result[6] = result[2];

            LOGI("Position: %.8f° N, %.8f° E, %.2f m, Status=%d, Satellites=%d",
                 result[0], result[1], result[2], (int)result[3], gSol.ns);
        } else {
            LOGE("Positioning failed or invalid solution");
            result[3] = 0; // Invalid status
        }
    } else {
        LOGE("Insufficient valid measurements: %d < 4", validCount);
        result[3] = 0;
    }

    // Create return array
    jdoubleArray resultArray = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(resultArray, 0, 7, result);

    return resultArray;
}

/**
 * Process RTCM correction data using RTKLIB
 */
JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_processRtcmData(
    JNIEnv* env, jobject obj,
    jbyteArray data,
    jint length) {

    (void)obj; // Unused parameter

    if (!gInitialized) {
        LOGE("RTK Engine not initialized");
        return JNI_FALSE;
    }

    jbyte* dataBytes = env->GetByteArrayElements(data, nullptr);

    LOGD("Processing %d bytes of RTCM data", length);

    int processed = 0;
    for (int i = 0; i < length; i++) {
        int ret = input_rtcm3(&gRtcm, (unsigned char)dataBytes[i]);

        if (ret > 0) {
            // Any positive return indicates a complete RTCM message (type returned by decoder)
            processed++;
            LOGI("RTCM3 message processed: ret=%d outtype=%d staid=%d",
                 ret, gRtcm.outtype, gRtcm.staid);

            // Copy ephemeris data to navigation structure
            if (gRtcm.ephsat > 0) {
                // Ephemeris updated - copy nav structure shallowly to make data available
                gNav = gRtcm.nav;
                LOGI("Ephemeris updated for satellite %d", gRtcm.ephsat);
            }

            // Copy observation data for differential corrections
            if (gRtcm.obsflag) {
                // Observation data complete
                LOGI("Base station observations received: %d satellites", gRtcm.obs.n);

                // Upgrade to DGPS/RTK mode if we have base observations
                if (gPrcOpt.mode == PMODE_SINGLE && gRtcm.obs.n > 0) {
                    gPrcOpt.mode = PMODE_DGPS; // Differential mode
                    rtkinit(&gRtk, &gPrcOpt);
                    LOGI("Upgraded to DGPS mode");
                }
            }
        } else if (ret == -1) {
            LOGD("RTCM3 parity error at byte %d", i);
        }
    }

    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);

    if (processed > 0) {
        LOGI("Successfully processed %d RTCM messages", processed);
        return JNI_TRUE;
    } else {
        LOGD("No complete RTCM messages in this data block");
        return JNI_FALSE;
    }
}

/**
 * Get current solution status
 */
JNIEXPORT jint JNICALL
Java_com_pg_rtk_service_RtkLibNative_getSolutionStatus(JNIEnv* env, jobject obj) {
    (void)env; (void)obj; // Unused parameters
    if (!gInitialized) {
        return 0;
    }
    return gSol.stat;
}

/**
 * Shutdown RTK engine
 */
JNIEXPORT void JNICALL
Java_com_pg_rtk_service_RtkLibNative_shutdownRtkEngine(JNIEnv* env, jobject obj) {
    (void)env; (void)obj; // Unused parameters
    LOGI("Shutting down RTK Engine");

    if (gInitialized) {
        rtkfree(&gRtk);

        if (gObs.data) {
            free(gObs.data);
            gObs.data = nullptr;
            gObs.n = 0;
            gObs.nmax = 0;
        }

        if (gNav.eph) free(gNav.eph);
        if (gNav.geph) free(gNav.geph);
        if (gNav.seph) free(gNav.seph);

        memset(&gNav, 0, sizeof(nav_t));
        memset(&gRtcm, 0, sizeof(rtcm_t));

        gInitialized = false;
    }

    LOGI("RTK Engine shutdown complete");
}

} // extern "C"
