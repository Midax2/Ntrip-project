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
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

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
 * Get carrier wavelength (m) for L1 frequency based on satellite system
 * Returns wavelength = speed_of_light / frequency
 */
static double getCarrierWavelength(int sys, int sat) {
    (void)sat; // Reserved for future GLONASS FDMA channel-specific calculation
    switch (sys) {
        case SYS_GPS:
        case SYS_GAL:
        case SYS_QZS:
            return CLIGHT / FREQ1;  // ~0.190 m for L1/E1
        case SYS_GLO: {
            // GLONASS uses FDMA - frequency depends on channel number
            // For now, use base frequency (more accurate would require nav data)
            return CLIGHT / FREQ1_GLO;  // ~0.187 m
        }
        case SYS_CMP:
            return CLIGHT / FREQ2_CMP;  // BeiDou B1 ~0.192 m
        default:
            return 0.0;
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

    // Check if any array allocation failed
    if (!svidArray || !constArray || !prArray || !cpArray || !cn0Array) {
        LOGE("Failed to get JNI array elements");
        // Release any successfully obtained arrays
        if (svidArray) env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
        if (constArray) env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
        if (prArray) env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
        if (cpArray) env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
        if (cn0Array) env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);
        return nullptr;
    }

    LOGD("Processing %d GNSS measurements", size);

    // Convert Android measurements to RTKLIB observation format
    gObs.n = 0;
    if (gObs.nmax < size) {
        auto* newData = (obsd_t*)realloc(gObs.data, sizeof(obsd_t) * size);
        if (!newData) {
            LOGE("Failed to allocate memory for observations (%d entries)", size);
            // Release Java arrays before returning
            env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
            env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
            env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
            env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
            env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);
            return nullptr;
        }
        gObs.data = newData;
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

        // Get satellite system for wavelength calculation
        int sys = constellation2sys(constArray[i]);

        // L1 frequency data (index 0)
        obs->P[0] = prArray[i];                    // Pseudorange (m)

        // Convert carrier phase from Android ADR (Accumulated Delta Range in meters) to cycles
        // RTKLIB expects carrier phase in cycles: L = ADR / wavelength
        if (cpArray[i] != 0.0) {
            double wavelength = getCarrierWavelength(sys, satno);
            if (wavelength > 0.0) {
                obs->L[0] = cpArray[i] / wavelength;  // Convert meters to cycles
                LOGD("Sat %d: ADR=%.3f m, wavelength=%.4f m, L=%.3f cycles",
                     satno, cpArray[i], wavelength, obs->L[0]);
            } else {
                obs->L[0] = 0.0;  // Unknown wavelength
                LOGD("Sat %d: Unknown wavelength for system %d", satno, sys);
            }
        } else {
            obs->L[0] = 0.0;  // No carrier phase data available
        }

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

    // Calculate uncorrected (single point) position first
    sol_t singleSol = {0};
    if (validCount >= 4) {
        char msg[128] = {0};
        double azel[MAXSAT * 2] = {0};
        ssat_t ssat[MAXSAT] = {0};

        int singleStatus = pntpos(gObs.data, gObs.n, &gNav, &gPrcOpt, &singleSol, azel, ssat, msg);

        if (singleStatus && singleSol.stat > 0) {
            // Store uncorrected position
            double uncorrPos[3];
            ecef2pos(singleSol.rr, uncorrPos);
            result[4] = uncorrPos[0] * R2D;  // Uncorrected Latitude (degrees)
            result[5] = uncorrPos[1] * R2D;  // Uncorrected Longitude (degrees)
            result[6] = uncorrPos[2];         // Uncorrected Height (m)
            LOGI("Uncorrected (Single) Position: %.8f° N, %.8f° E, %.2f m, Satellites=%d",
                 result[4], result[5], result[6], singleSol.ns);
        } else {
            LOGD("Single point positioning failed: %s", msg);
        }
    }

    if (validCount >= 4) {
        // Perform positioning for corrected solution
        int status;

        if (gPrcOpt.mode == PMODE_SINGLE || gRtcm.obs.n == 0) {
            // Use single point solution as corrected position (no RTCM corrections)
            gSol = singleSol;
            status = (gSol.stat > 0) ? 1 : 0;
            if (status) {
                LOGI("Single point solution (no RTCM): status=%d, ns=%d", gSol.stat, gSol.ns);
            }
        } else {
            // RTK positioning with base station corrections
            // RTKLIB expects observations to contain rover (rcv==1) followed by
            // base/reference observations (rcv==2) in a single array. The RTCM
            // decoder stores base station observations in gRtcm.obs. We need to
            // merge them with the current rover observations before calling rtkpos().

            // Check if base observations are recent enough (within 30 seconds)
            double timeDiff = timediff(obsTime, gRtcm.obs.data[0].time);
            if (fabs(timeDiff) > gPrcOpt.maxtdiff) {
                LOGW("Base observations too old (%.1f sec difference), using single point solution", timeDiff);
                gSol = singleSol;
                status = (gSol.stat > 0) ? 1 : 0;
            } else {
                int totalObs = gObs.n + (gRtcm.obs.n > 0 ? gRtcm.obs.n : 0);

                if (totalObs > 0) {
                    auto* combined = (obsd_t*)malloc(sizeof(obsd_t) * totalObs);
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

                        LOGI("RTK processing: rover obs=%d, base obs=%d, time diff=%.1f sec",
                             gObs.n, gRtcm.obs.n, timeDiff);

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
                    LOGE("RTK positioning failed, falling back to single point solution");
                    gSol = singleSol;
                    status = (gSol.stat > 0) ? 1 : 0;
                }
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

            // If uncorrected position wasn't calculated (no single solution), use corrected as fallback
            if (result[4] == 0.0 && result[5] == 0.0 && result[6] == 0.0) {
                result[4] = result[0];
                result[5] = result[1];
                result[6] = result[2];
            }

            LOGI("Position: %.8f° N, %.8f° E, %.2f m, Status=%d, Satellites=%d",
                 result[0], result[1], result[2], (int)result[3], gSol.ns);
        } else {
            LOGE("Positioning failed or invalid solution");
            result[3] = 0; // Invalid status
            // Keep uncorrected position if it was calculated
        }
    } else {
        LOGE("Insufficient valid measurements: %d < 4", validCount);
        result[3] = 0;
        // Keep uncorrected position if it was calculated
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
                // Ephemeris updated - copy specific satellite ephemeris
                int sat = gRtcm.ephsat;
                if (satsys(sat, NULL) == SYS_GPS || satsys(sat, NULL) == SYS_GAL ||
                    satsys(sat, NULL) == SYS_QZS || satsys(sat, NULL) == SYS_CMP) {
                    // Allocate ephemeris array if needed
                    if (gNav.n >= gNav.nmax) {
                        gNav.nmax = gNav.nmax <= 0 ? 256 : gNav.nmax * 2;
                        auto* newEph = (eph_t*)realloc(gNav.eph, sizeof(eph_t) * gNav.nmax);
                        if (newEph) {
                            gNav.eph = newEph;
                        } else {
                            LOGE("Failed to allocate memory for ephemeris");
                        }
                    }
                    // Copy the new ephemeris
                    if (gNav.eph && gRtcm.nav.eph) {
                        // Find and update existing ephemeris for this satellite
                        int idx = -1;
                        for (int k = 0; k < gNav.n; k++) {
                            if (gNav.eph[k].sat == sat) {
                                idx = k;
                                break;
                            }
                        }
                        if (idx >= 0) {
                            gNav.eph[idx] = gRtcm.nav.eph[0];  // Update existing
                        } else if (gNav.n < gNav.nmax) {
                            gNav.eph[gNav.n++] = gRtcm.nav.eph[0];  // Add new
                        }
                        LOGI("Ephemeris updated for satellite %d (total: %d)", sat, gNav.n);
                    }
                } else if (satsys(sat, NULL) == SYS_GLO) {
                    // GLONASS ephemeris
                    if (gNav.ng >= gNav.ngmax) {
                        gNav.ngmax = gNav.ngmax <= 0 ? 256 : gNav.ngmax * 2;
                        auto* newGeph = (geph_t*)realloc(gNav.geph, sizeof(geph_t) * gNav.ngmax);
                        if (newGeph) {
                            gNav.geph = newGeph;
                        }
                    }
                    if (gNav.geph && gRtcm.nav.geph) {
                        int idx = -1;
                        for (int k = 0; k < gNav.ng; k++) {
                            if (gNav.geph[k].sat == sat) {
                                idx = k;
                                break;
                            }
                        }
                        if (idx >= 0) {
                            gNav.geph[idx] = gRtcm.nav.geph[0];
                        } else if (gNav.ng < gNav.ngmax) {
                            gNav.geph[gNav.ng++] = gRtcm.nav.geph[0];
                        }
                        LOGI("GLONASS ephemeris updated for satellite %d", sat);
                    }
                }
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
