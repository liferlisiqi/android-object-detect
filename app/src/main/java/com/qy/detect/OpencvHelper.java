package com.qy.detect;

import android.graphics.Bitmap;

public class OpencvHelper {

    public static native float Laplace(Bitmap input);

    static {
        System.loadLibrary("opencv_java3");
        System.loadLibrary("OpencvHelper");
    }
}
