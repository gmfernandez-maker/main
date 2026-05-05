package org.opencv.android;

import android.content.Context;
import android.util.Log;

/**
 * Minimal stub of AsyncServiceHelper for in-project OpenCV SDK builds.
 * This avoids AIDL and OpenCV Manager dependencies by attempting to load
 * the bundled native library directly and calling the callback with
 * SUCCESS or INIT_FAILED. It is intentionally small and safe for builds.
 */
class AsyncServiceHelper {
    protected static final String TAG = "OpenCVManager/Helper";

    public static boolean initOpenCV(String Version, final Context AppContext,
            final LoaderCallbackInterface Callback) {
        try {
            System.loadLibrary("opencv_java4");
            Callback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "OpenCV native libs not found, init failed");
            Callback.onManagerConnected(LoaderCallbackInterface.INIT_FAILED);
            return false;
        }
    }
}
