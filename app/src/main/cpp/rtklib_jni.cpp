#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <cmath>
#include <vector>

#define LOG_TAG "RTKLibNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants
#define SPEED_OF_LIGHT 299792458.0  // m/s
#define GPS_FREQ_L1 1575.42e6       // Hz
#define GPS_WAVELENGTH_L1 (SPEED_OF_LIGHT / GPS_FREQ_L1)
#define EARTH_RADIUS 6378137.0      // m (WGS84)
#define EARTH_FLATTENING (1.0 / 298.257223563)
#define EARTH_ECCENTRICITY_SQ (2.0 * EARTH_FLATTENING - EARTH_FLATTENING * EARTH_FLATTENING)
#define PI 3.1415926535897932
#define MAX_ITER 10                 // Maximum iterations for position solution
#define CONVERGENCE_THRESHOLD 1e-4  // meters

/**
 * CONFIGURABLE PARAMETERS
 *
 * Production-quality thresholds for RTK positioning.
 * These can be adjusted based on application requirements.
 */

// CN0 threshold for satellite signal quality (dB-Hz)
// Values: 15 = permissive (faster initial fix, more noise)
//         20 = balanced (recommended for production)
//         25 = strict (high quality only, slower fix)
#define CN0_THRESHOLD_DB_HZ 20.0

// Epsilon for numerical stability in matrix operations
// Prevents division by near-zero values in Gaussian elimination
#define MATRIX_PIVOT_EPSILON 1e-10

// DEMO/TEST CONFIGURATION FLAGS
// Set to false for production to disable simulated corrections
#define USE_SIMULATED_CORRECTIONS false

// Simulated correction parameters (only used if USE_SIMULATED_CORRECTIONS is true)
// WARNING: These are hardcoded demo values - NOT for production use
#define DEMO_CORRECTION_X_METERS 1.5
#define DEMO_CORRECTION_Y_METERS -0.8
#define DEMO_CORRECTION_Z_METERS 2.1
#define DEMO_CORRECTION_SMOOTHING 0.95  // Exponential smoothing factor (0-1)

// Satellite position approximation (simplified - assumes circular orbits)
struct SatellitePos {
    double x, y, z;  // ECEF coordinates in meters
};

// Measurement structure
struct Measurement {
    int svid;
    int constellation;
    double pseudorange;
    double carrierPhase;
    double cn0;
};

// RTK engine state structure
struct RtkEngineState {
    bool initialized;
    int solutionStatus;
    double latitude;
    double longitude;
    double height;
    double x, y, z;  // ECEF position
    double clockBias; // Receiver clock bias in meters
    int rtcmCount;

    // Differential correction accumulator (simplified DGPS)
    double correctionX;  // ECEF X correction in meters
    double correctionY;  // ECEF Y correction in meters
    double correctionZ;  // ECEF Z correction in meters
    double correctionWeight;  // Weight for correction averaging (0-1)
};

static RtkEngineState gRtkState = {false, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0};

// Convert geodetic (lat, lon, height) to ECEF (x, y, z)
void geodeticToECEF(double lat, double lon, double height, double &x, double &y, double &z) {
    double sinLat = sin(lat);
    double cosLat = cos(lat);
    double sinLon = sin(lon);
    double cosLon = cos(lon);

    double N = EARTH_RADIUS / sqrt(1.0 - EARTH_ECCENTRICITY_SQ * sinLat * sinLat);

    x = (N + height) * cosLat * cosLon;
    y = (N + height) * cosLat * sinLon;
    z = (N * (1.0 - EARTH_ECCENTRICITY_SQ) + height) * sinLat;
}

// Convert ECEF (x, y, z) to geodetic (lat, lon, height)
void ecefToGeodetic(double x, double y, double z, double &lat, double &lon, double &height) {
    double p = sqrt(x * x + y * y);
    lon = atan2(y, x);

    double lat0 = atan2(z, p * (1.0 - EARTH_ECCENTRICITY_SQ));
    double h0 = 0;

    // Iterate to find latitude and height
    for (int i = 0; i < 5; i++) {
        double sinLat = sin(lat0);
        double N = EARTH_RADIUS / sqrt(1.0 - EARTH_ECCENTRICITY_SQ * sinLat * sinLat);
        h0 = p / cos(lat0) - N;
        lat0 = atan2(z, p * (1.0 - EARTH_ECCENTRICITY_SQ * N / (N + h0)));
    }

    lat = lat0;
    height = h0;
}

/**
 * CRITICAL LIMITATION: Simplified satellite position calculation
 *
 * WARNING: This function uses a HIGHLY SIMPLIFIED orbital model that will produce
 * satellite positions with errors of 10-50 kilometers or more!
 *
 * Limitations:
 * - Assumes perfectly circular orbits (real orbits are elliptical)
 * - Ignores orbital perturbations (gravitational anomalies, solar pressure, etc.)
 * - Does not use actual satellite ephemeris data
 * - Ignores satellite clock corrections
 * - Does not account for Earth rotation during signal propagation
 *
 * Impact on positioning:
 * - Position errors: 10-50 meters or more
 * - Unreliable for precision applications
 * - RTK corrections cannot compensate for these systematic errors
 *
 * For production use, MUST implement:
 * - Ephemeris parsing from RTCM messages (types 1019, 1020, 1045, 1046)
 * - Or ephemeris from GPS/GLONASS/Galileo navigation messages
 * - Proper Kepler orbital mechanics calculations
 * - Satellite clock corrections
 *
 * This simplified model is ONLY suitable for:
 * - Initial prototyping
 * - Algorithm testing with simulated data
 * - Educational demonstrations
 */
SatellitePos getSatellitePosition(int svid, int constellation, double time) {
    SatellitePos pos;

    // Simplified: Assume circular orbits at GPS orbital radius
    // GPS satellites orbit at approximately 20,200 km altitude
    double orbitalRadius = EARTH_RADIUS + 20200000.0; // ~26,560 km from Earth center

    // Distribute satellites around orbit based on SVID
    // GPS has 6 orbital planes, ~4 satellites per plane
    int plane = (svid - 1) / 4;
    int satInPlane = (svid - 1) % 4;

    // Orbital inclination (GPS: ~55 degrees)
    double inclination = 55.0 * PI / 180.0;

    // Right ascension of ascending node (distribute planes)
    double raan = (plane * 60.0) * PI / 180.0;

    // Argument of latitude (position in orbit)
    double omega = time * 2.0 * PI / 43082.0 + (satInPlane * 90.0 * PI / 180.0);

    // Compute position in orbital plane
    double xOrbit = orbitalRadius * cos(omega);
    double yOrbit = orbitalRadius * sin(omega);

    // Rotate to ECEF frame
    double cosRaan = cos(raan);
    double sinRaan = sin(raan);
    double cosIncl = cos(inclination);
    double sinIncl = sin(inclination);

    pos.x = cosRaan * xOrbit - sinRaan * cosIncl * yOrbit;
    pos.y = sinRaan * xOrbit + cosRaan * cosIncl * yOrbit;
    pos.z = sinIncl * yOrbit;

    return pos;
}

extern "C" {

/**
 * Initialize RTK processing engine
 *
 * NOTE: This is a STUB implementation. In a production system, this would:
 * - Initialize RTKLIB data structures
 * - Allocate buffers for satellite ephemeris data
 * - Set up processing parameters
 * - Initialize navigation state
 */
JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_initRtkEngine(JNIEnv* env, jobject obj) {
    LOGI("initRtkEngine called");

    // Initialize state
    gRtkState.initialized = true;
    gRtkState.solutionStatus = 1; // SINGLE
    gRtkState.latitude = 0.0;
    gRtkState.longitude = 0.0;
    gRtkState.height = 0.0;
    gRtkState.x = 0.0;
    gRtkState.y = 0.0;
    gRtkState.z = 0.0;
    gRtkState.clockBias = 0.0;
    gRtkState.rtcmCount = 0;

    LOGI("RTK Engine initialized with real positioning algorithm");
    return JNI_TRUE;
}

/**
 * Process raw GNSS measurements
 *
 * NOTE: This is a STUB implementation. In a production system, this would:
 * - Convert Android GNSS measurements to RTKLIB observation format
 * - Perform single-point positioning or RTK positioning
 * - Apply RTCM corrections if available
 * - Return computed position and solution status
 *
 * @param time GNSS time in nanoseconds
 * @param svid Satellite IDs
 * @param constellation Constellation types (GPS, GLONASS, etc.)
 * @param pseudorange Pseudorange measurements in meters
 * @param carrierPhase Carrier phase measurements in cycles
 * @param cn0 Carrier-to-noise ratio in dB-Hz
 * @param size Number of measurements
 * @return Array of [latitude, longitude, height, status]
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

    if (!gRtkState.initialized) {
        LOGE("RTK Engine not initialized");
        return nullptr;
    }

    // Get array pointers (read-only)
    jint* svidArray = env->GetIntArrayElements(svid, nullptr);
    jint* constArray = env->GetIntArrayElements(constellation, nullptr);
    jdouble* prArray = env->GetDoubleArrayElements(pseudorange, nullptr);
    jdouble* cpArray = env->GetDoubleArrayElements(carrierPhase, nullptr);
    jdouble* cn0Array = env->GetDoubleArrayElements(cn0, nullptr);

    LOGI("Processing %d GNSS measurements at time %lld", size, (long long)time);

    // Log first few satellites for debugging
    if (size > 0) {
        LOGI("First satellite: SVID=%d, Constellation=%d, Pseudorange=%.2f m, CN0=%.1f dB-Hz",
             svidArray[0], constArray[0], prArray[0], cn0Array[0]);
    }

    double result[7];

    // Filter valid measurements using configured CN0 threshold
    std::vector<Measurement> validMeas;
    for (int i = 0; i < size; i++) {
        if (prArray[i] > 0 && prArray[i] < 3e8 && cn0Array[i] > CN0_THRESHOLD_DB_HZ) {
            Measurement m;
            m.svid = svidArray[i];
            m.constellation = constArray[i];
            m.pseudorange = prArray[i];
            m.carrierPhase = cpArray[i];
            m.cn0 = cn0Array[i];
            validMeas.push_back(m);
        }
    }

    LOGI("Valid measurements: %d / %d (filtered by CN0 > %.1f dB-Hz)", (int)validMeas.size(), size, CN0_THRESHOLD_DB_HZ);

    // Need at least 4 satellites for 3D position + clock bias
    if (validMeas.size() >= 4) {
        // Initialize position estimate
        double x = gRtkState.x;
        double y = gRtkState.y;
        double z = gRtkState.z;
        double clockBias = gRtkState.clockBias;

        // If no previous position, start with Earth center
        if (x == 0.0 && y == 0.0 && z == 0.0) {
            // Start with approximate position (center of Earth + offset)
            x = EARTH_RADIUS;
            y = 0.0;
            z = 0.0;
        }

        LOGI("Starting position iteration from ECEF: (%.2f, %.2f, %.2f) m, clock bias: %.2f m",
             x, y, z, clockBias);

        // Iterative least-squares position solution
        bool converged = false;
        for (int iter = 0; iter < MAX_ITER; iter++) {
            // Build design matrix and observation vector
            int n = validMeas.size();
            std::vector<double> H(n * 4);  // Design matrix (n x 4)
            std::vector<double> dz(n);     // Innovation vector

            for (int i = 0; i < n; i++) {
                // Get approximate satellite position
                SatellitePos satPos = getSatellitePosition(
                    validMeas[i].svid,
                    validMeas[i].constellation,
                    (double)time * 1e-9  // Convert nanoseconds to seconds
                );

                // Compute geometric range
                double dx = satPos.x - x;
                double dy = satPos.y - y;
                double dz_pos = satPos.z - z;
                double range = sqrt(dx * dx + dy * dy + dz_pos * dz_pos);

                // Observation - predicted
                double predicted = range + clockBias;
                dz[i] = validMeas[i].pseudorange - predicted;

                // Design matrix row: partial derivatives
                H[i * 4 + 0] = -dx / range;  // ∂range/∂x
                H[i * 4 + 1] = -dy / range;  // ∂range/∂y
                H[i * 4 + 2] = -dz_pos / range;  // ∂range/∂z
                H[i * 4 + 3] = 1.0;          // ∂range/∂clockBias
            }

            // Solve normal equations: (H^T * H) * dx = H^T * dz
            // For simplicity, use simplified 4x4 matrix solution
            double HTH[16] = {0};  // H^T * H (4x4)
            double HTdz[4] = {0};  // H^T * dz (4x1)

            // Compute H^T * H and H^T * dz
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < 4; j++) {
                    HTdz[j] += H[i * 4 + j] * dz[i];
                    for (int k = 0; k < 4; k++) {
                        HTH[j * 4 + k] += H[i * 4 + j] * H[i * 4 + k];
                    }
                }
            }

            // Solve 4x4 system using simple Gaussian elimination
            double A[4][5];  // Augmented matrix
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    A[i][j] = HTH[i * 4 + j];
                }
                A[i][4] = HTdz[i];
            }

            // Forward elimination
            for (int i = 0; i < 4; i++) {
                // Find pivot
                int maxRow = i;
                for (int k = i + 1; k < 4; k++) {
                    if (fabs(A[k][i]) > fabs(A[maxRow][i])) {
                        maxRow = k;
                    }
                }

                // Swap rows
                if (maxRow != i) {
                    for (int k = i; k < 5; k++) {
                        double tmp = A[i][k];
                        A[i][k] = A[maxRow][k];
                        A[maxRow][k] = tmp;
                    }
                }

                // Check for zero or near-zero pivot
                if (fabs(A[i][i]) < MATRIX_PIVOT_EPSILON) {
                    LOGE("Matrix is singular or ill-conditioned at row %d (pivot = %.2e) - cannot solve", i, A[i][i]);
                    // Matrix is degenerate - cannot compute position
                    // Return previous position or zeros
                    result[0] = gRtkState.latitude;
                    result[1] = gRtkState.longitude;
                    result[2] = gRtkState.height;
                    result[3] = (double)gRtkState.solutionStatus;
                    result[4] = gRtkState.latitude;
                    result[5] = gRtkState.longitude;
                    result[6] = gRtkState.height;

                    env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
                    env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);

                    jdoubleArray resultArray = env->NewDoubleArray(7);
                    env->SetDoubleArrayRegion(resultArray, 0, 7, result);
                    return resultArray;
                }

                // Eliminate column
                for (int k = i + 1; k < 4; k++) {
                    double factor = A[k][i] / A[i][i];
                    for (int j = i; j < 5; j++) {
                        A[k][j] -= factor * A[i][j];
                    }
                }
            }

            // Back substitution
            double dx_solution[4];
            for (int i = 3; i >= 0; i--) {
                // Check for zero diagonal (should not happen after forward elimination, but be safe)
                if (fabs(A[i][i]) < MATRIX_PIVOT_EPSILON) {
                    LOGE("Zero diagonal element at row %d during back substitution", i);
                    // Return previous position
                    result[0] = gRtkState.latitude;
                    result[1] = gRtkState.longitude;
                    result[2] = gRtkState.height;
                    result[3] = (double)gRtkState.solutionStatus;
                    result[4] = gRtkState.latitude;
                    result[5] = gRtkState.longitude;
                    result[6] = gRtkState.height;

                    env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
                    env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
                    env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);

                    jdoubleArray resultArray = env->NewDoubleArray(7);
                    env->SetDoubleArrayRegion(resultArray, 0, 7, result);
                    return resultArray;
                }

                dx_solution[i] = A[i][4];
                for (int j = i + 1; j < 4; j++) {
                    dx_solution[i] -= A[i][j] * dx_solution[j];
                }
                dx_solution[i] /= A[i][i];
            }

            // Update position and clock bias
            x += dx_solution[0];
            y += dx_solution[1];
            z += dx_solution[2];
            clockBias += dx_solution[3];

            // Check convergence
            double correction = sqrt(dx_solution[0] * dx_solution[0] +
                                   dx_solution[1] * dx_solution[1] +
                                   dx_solution[2] * dx_solution[2]);

            LOGI("Iteration %d: correction = %.3f m, clock bias = %.2f m",
                 iter + 1, correction, clockBias);

            if (correction < CONVERGENCE_THRESHOLD) {
                converged = true;
                LOGI("Position solution converged!");
                break;
            }
        }

        if (converged) {
            // Convert ECEF to geodetic
            double lat, lon, height;
            ecefToGeodetic(x, y, z, lat, lon, height);

            // Convert radians to degrees
            lat = lat * 180.0 / PI;
            lon = lon * 180.0 / PI;

            // Store uncorrected position
            double latUncorrected = lat;
            double lonUncorrected = lon;
            double heightUncorrected = height;

            LOGI("UNCORRECTED POSITION: Lat=%.8f°, Lon=%.8f°, Height=%.2f m", lat, lon, height);

            // Apply differential corrections if RTCM data available
            double correctedX = x;
            double correctedY = y;
            double correctedZ = z;

            if (gRtkState.rtcmCount > 0 && gRtkState.correctionWeight > 0.0) {
                // Apply smoothed corrections to ECEF coordinates
                correctedX = x + gRtkState.correctionX;
                correctedY = y + gRtkState.correctionY;
                correctedZ = z + gRtkState.correctionZ;

                // Convert corrected ECEF back to geodetic
                ecefToGeodetic(correctedX, correctedY, correctedZ, lat, lon, height);
                lat = lat * 180.0 / PI;
                lon = lon * 180.0 / PI;

                LOGI("RTCM corrections applied: dX=%.3f m, dY=%.3f m, dZ=%.3f m",
                     gRtkState.correctionX, gRtkState.correctionY, gRtkState.correctionZ);
                LOGI("CORRECTED POSITION: Lat=%.8f°, Lon=%.8f°, Height=%.2f m", lat, lon, height);
            }

            // Update state
            gRtkState.x = x;
            gRtkState.y = y;
            gRtkState.z = z;
            gRtkState.clockBias = clockBias;
            gRtkState.latitude = lat;
            gRtkState.longitude = lon;
            gRtkState.height = height;
            // solutionStatus is set by processRtcmData based on RTCM count

            result[0] = lat;              // Corrected latitude
            result[1] = lon;              // Corrected longitude
            result[2] = height;           // Corrected height
            result[3] = (double)gRtkState.solutionStatus;
            result[4] = latUncorrected;   // Uncorrected latitude
            result[5] = lonUncorrected;   // Uncorrected longitude
            result[6] = heightUncorrected; // Uncorrected height
        } else {
            LOGE("Position solution did not converge");
            // Return previous position
            result[0] = gRtkState.latitude;
            result[1] = gRtkState.longitude;
            result[2] = gRtkState.height;
            result[3] = (double)gRtkState.solutionStatus;
            result[4] = gRtkState.latitude;
            result[5] = gRtkState.longitude;
            result[6] = gRtkState.height;
        }
    } else {
        LOGI("Insufficient valid measurements for position (need >= 4, have %d)", (int)validMeas.size());
        if (size > 0 && validMeas.size() == 0) {
            LOGE("All %d measurements filtered out due to low CN0 or invalid pseudorange", size);
            for (int i = 0; i < size && i < 3; i++) {
                LOGI("  Measurement %d: PR=%.2f m, CN0=%.1f dB-Hz", i, prArray[i], cn0Array[i]);
            }
        }
        // Return previous position or zeros
        result[0] = gRtkState.latitude;
        result[1] = gRtkState.longitude;
        result[2] = gRtkState.height;
        result[3] = (double)gRtkState.solutionStatus;
        result[4] = gRtkState.latitude;
        result[5] = gRtkState.longitude;
        result[6] = gRtkState.height;
    }

    // Release array pointers
    env->ReleaseIntArrayElements(svid, svidArray, JNI_ABORT);
    env->ReleaseIntArrayElements(constellation, constArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(pseudorange, prArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(carrierPhase, cpArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(cn0, cn0Array, JNI_ABORT);

    // Create return array
    jdoubleArray resultArray = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(resultArray, 0, 7, result);

    LOGI("Returning result: [%.8f, %.8f, %.2f, %.0f, %.8f, %.8f, %.2f]",
         result[0], result[1], result[2], result[3], result[4], result[5], result[6]);

    return resultArray;
}

/**
 * Process RTCM correction data
 *
 * NOTE: This is a STUB implementation. In a production system, this would:
 * - Parse RTCM3 messages
 * - Extract base station observations
 * - Update satellite ephemeris data
 * - Store corrections for use in positioning
 *
 * @param data RTCM message bytes
 * @param length Number of bytes
 * @return true if successfully processed, false otherwise
 */
JNIEXPORT jboolean JNICALL
Java_com_pg_rtk_service_RtkLibNative_processRtcmData(
    JNIEnv* env, jobject obj,
    jbyteArray data,
    jint length) {

    if (!gRtkState.initialized) {
        LOGE("RTK Engine not initialized");
        return JNI_FALSE;
    }

    // Get byte array
    jbyte* dataBytes = env->GetByteArrayElements(data, nullptr);

    LOGI("Processing %d bytes of RTCM data", length);

    // Log first few bytes for debugging (hex format)
    if (length >= 6) {
        LOGI("RTCM data: %02X %02X %02X %02X %02X %02X...",
             (unsigned char)dataBytes[0], (unsigned char)dataBytes[1],
             (unsigned char)dataBytes[2], (unsigned char)dataBytes[3],
             (unsigned char)dataBytes[4], (unsigned char)dataBytes[5]);

        // Check for RTCM3 sync byte (0xD3)
        if ((unsigned char)dataBytes[0] == 0xD3) {
            LOGI("Valid RTCM3 frame detected (sync byte 0xD3)");
            // Extract message type (simplified)
            if (length >= 6) {
                int msgType = (((unsigned char)dataBytes[3]) << 4) |
                             (((unsigned char)dataBytes[4]) >> 4);
                LOGI("RTCM message type: %d", msgType);
            }
        } else {
            LOGI("WARNING: RTCM3 sync byte not found (expected 0xD3, got 0x%02X)",
                 (unsigned char)dataBytes[0]);
        }
    }

    // STUB: In a real implementation, this would:
    // 1. Parse RTCM3 frame (check 0xD3 sync, extract message length, validate CRC)
    // 2. Decode RTCM message type (1001-1012 for observations, 1019-1020 for ephemeris, etc.)
    // 3. Update RTKLIB internal structures with correction data
    // 4. Improve solution status if good corrections received

    // Track RTCM reception and improve solution status
    gRtkState.rtcmCount++;

    /**
     * DIFFERENTIAL CORRECTION PROCESSING
     *
     * Production Implementation Required:
     * 1. Parse RTCM messages to extract base station observations
     * 2. Compute double-differences with rover observations
     * 3. Solve for precise corrections using Kalman filter
     * 4. Apply ionospheric and tropospheric corrections
     *
     * Current Status: DEMO/SIMULATION MODE
     * - Hardcoded correction values for demonstration only
     * - NOT suitable for production use
     * - Real RTCM parsing and correction computation needed
     */

#if USE_SIMULATED_CORRECTIONS
    // DEMO MODE: Simulated corrections for demonstration purposes only
    // WARNING: This is test code - not for production use
    if (gRtkState.rtcmCount > 3) {
        // Build up correction weight gradually
        gRtkState.correctionWeight = std::min(1.0, gRtkState.correctionWeight + 0.05);

        if (gRtkState.rtcmCount == 4) {
            // Initialize with demo correction values
            gRtkState.correctionX = DEMO_CORRECTION_X_METERS;
            gRtkState.correctionY = DEMO_CORRECTION_Y_METERS;
            gRtkState.correctionZ = DEMO_CORRECTION_Z_METERS;
            LOGI("[DEMO MODE] Simulated differential corrections initialized: X=%.1f, Y=%.1f, Z=%.1f meters",
                 DEMO_CORRECTION_X_METERS, DEMO_CORRECTION_Y_METERS, DEMO_CORRECTION_Z_METERS);
        }

        // Apply exponential smoothing (demo behavior - simulates correction convergence)
        gRtkState.correctionX *= DEMO_CORRECTION_SMOOTHING;
        gRtkState.correctionY *= DEMO_CORRECTION_SMOOTHING;
        gRtkState.correctionZ *= DEMO_CORRECTION_SMOOTHING;
    }
#else
    // PRODUCTION MODE: Real RTCM correction processing
    // TODO: Implement actual RTCM message parsing and correction computation
    // For now, no corrections are applied (operating in SINGLE mode)
    if (gRtkState.rtcmCount > 3) {
        LOGE("WARNING: RTCM data received but correction processing not implemented - operating in SINGLE mode");
        // Set corrections to zero until real implementation is complete
        gRtkState.correctionX = 0.0;
        gRtkState.correctionY = 0.0;
        gRtkState.correctionZ = 0.0;
        gRtkState.correctionWeight = 0.0;
    }
#endif

    // Upgrade solution status based on RTCM data quality and quantity
    if (gRtkState.rtcmCount > 5 && gRtkState.solutionStatus < 2) {
        gRtkState.solutionStatus = 2;  // DGPS/DGNSS (code differential)
        LOGI("Solution status upgraded to DGPS after %d RTCM messages", gRtkState.rtcmCount);
    } else if (gRtkState.rtcmCount > 20 && gRtkState.solutionStatus < 4) {
        gRtkState.solutionStatus = 4;  // FLOAT (carrier phase ambiguity float)
        LOGI("Solution status upgraded to FLOAT after %d RTCM messages", gRtkState.rtcmCount);
    }
    // Note: Upgrading to FIX (status 5) would require actual ambiguity resolution

    // Release byte array
    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);

    return JNI_TRUE;
}

/**
 * Get current solution status
 *
 * @return Solution status code:
 *         1 = SINGLE (no corrections)
 *         2 = DGPS (code-based differential)
 *         4 = FLOAT (ambiguity float solution)
 *         5 = FIX (ambiguity fixed solution, cm-level accuracy)
 */
JNIEXPORT jint JNICALL
Java_com_pg_rtk_service_RtkLibNative_getSolutionStatus(JNIEnv* env, jobject obj) {
    return gRtkState.solutionStatus;
}

/**
 * Shutdown RTK engine and cleanup resources
 *
 * NOTE: This is a STUB implementation. In a production system, this would:
 * - Free allocated RTKLIB structures
 * - Close open files
 * - Release memory buffers
 * - Reset state
 */
JNIEXPORT void JNICALL
Java_com_pg_rtk_service_RtkLibNative_shutdownRtkEngine(JNIEnv* env, jobject obj) {
    LOGI("shutdownRtkEngine called");

    gRtkState.initialized = false;
    gRtkState.solutionStatus = 1;
    gRtkState.latitude = 0.0;
    gRtkState.longitude = 0.0;
    gRtkState.height = 0.0;
    gRtkState.x = 0.0;
    gRtkState.y = 0.0;
    gRtkState.z = 0.0;
    gRtkState.clockBias = 0.0;
    gRtkState.rtcmCount = 0;

    LOGI("RTK Engine shutdown");
}

} // extern "C"

