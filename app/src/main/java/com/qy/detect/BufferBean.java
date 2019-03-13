package com.qy.detect;

import android.util.Log;

public class BufferBean {
    public byte[] isCanRead; // 1 yes 0 no
    public byte[] mBuffer; // adas buffer
    final String TAG = "BufferBean";

    public BufferBean(int bufferSize) {
        Log.d(TAG, "bufferSize:" + bufferSize);
        // init data
        isCanRead = new byte[1];

        if (bufferSize > 0) {
            mBuffer = new byte[bufferSize];
        }

        for (int i = 0; i < mBuffer.length; i++) {
            mBuffer[i] = 0;
        }
    }
}