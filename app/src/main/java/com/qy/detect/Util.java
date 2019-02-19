// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.qy.detect;

import android.app.Activity;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.text.TextUtils;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImageNativeLibrary;

/**
 * This class uses Utility functions written for the Camera module of Android.
 *
 * These snippets have been taken from:
 *
 *      https://android.googlesource.com/platform/packages/apps/Camera/
 *
 *  Android code is released under terms of the Apache 2.0 license. You can obtain the copy in
 *  the assets folder coming with this project.
 *
 *  Copyright (C) 2011 The Android Open Source Project
 *
 */
public class Util {

    private static final String TAG = "Util";

    static final int kMaxChannelValue = 262143;
    // Orientation hysteresis amount used in rounding, in degrees
    private static final int ORIENTATION_HYSTERESIS = 5;
    private static final int MINIMUM_PREVIEW_SIZE = 240;

    /**
     * Gets the current display rotation in angles.
     *
     * @param activity
     * @return
     */
    public static int getDisplayRotation(Activity activity) {
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        switch (rotation) {
            case Surface.ROTATION_0: return 0;
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
        }
        return 0;
    }

    public static int getDisplayOrientation(int degrees, int cameraId) {
        // See android.hardware.Camera.setDisplayOrientation for
        // documentation.
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public static void prepareMatrix(Matrix matrix, boolean mirror, int displayOrientation,
                                     int viewWidth, int viewHeight) {
        // Need mirror for front camera.
        matrix.setScale(mirror ? -1 : 1, 1);
        // This is the value for android.hardware.Camera.setDisplayOrientation.
        matrix.postRotate(displayOrientation);
        // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
        // UI coordinates range from (0, 0) to (width, height).
        matrix.postScale(viewWidth / 2000f, viewHeight / 2000f);
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f);
    }

    public static int roundOrientation(int orientation, int orientationHistory) {
        boolean changeOrientation = false;
        if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
            changeOrientation = true;
        } else {
            int dist = Math.abs(orientation - orientationHistory);
            dist = Math.min( dist, 360 - dist );
            changeOrientation = ( dist >= 45 + ORIENTATION_HYSTERESIS );
        }
        if (changeOrientation) {
            return ((orientation + 45) / 90 * 90) % 360;
        }
        return orientationHistory;
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.width * lhs.height - (long) rhs.width * rhs.height);
        }
    }

    protected static Size chooseOptimalSize(final List<Size> choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = choices.get(0);
        desiredSize.width = width;
        desiredSize.height = height;

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.height >= minSize && option.width >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        Log.w(TAG, "Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
        Log.w(TAG, "Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
        Log.w(TAG, "Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

        if (exactSizeFound) {
            Log.w(TAG, "Exact size match found.");
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            Log.w(TAG, "Chosen size: " + chosenSize.width + "x" + chosenSize.height);
            return chosenSize;
        } else {
            Log.w(TAG, "Couldn't find any suitable preview size");
            return choices.get(0);
        }
    }

    public static int getYUVByteSize(final int width, final int height) {
        // The luminance plane requires 1 byte per pixel.
        final int ySize = width * height;

        // The UV plane works on 2x2 blocks, so dimensions with odd size must be rounded up.
        // Each 2x2 block takes 2 bytes to encode, one each for U and V.
        final int uvSize = ((width + 1) / 2) * ((height + 1) / 2) * 2;

        return ySize + uvSize;
    }

    public static void convertYUV420SPToY888(byte[] input, int width, int height, int[] output){
        int p;
        int size = width*height;
        for(int i = 0; i < size; i++) {
            p = input[i] & 0xFF;
            output[i] = 0xff000000 | p<<16 | p<<8 | p;
        }
    }

    public static void convertYUV420SPToARGB8888(byte[] input, int width, int height, int[] output){
        GPUImageNativeLibrary.YUVtoRBGA(input, width, height, output);
    }

}