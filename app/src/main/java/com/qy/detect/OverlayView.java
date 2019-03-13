// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.qy.detect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is a simple View to display the faces.
 */
public class OverlayView extends View {

    private Paint mPaint;
    private Paint mTextPaint;
    private int mDisplayOrientation;
    private int mOrientation;
    private List<DetectObject> mResults = new ArrayList<>();
    private long mDetectTime;

    private int mRemainBuffer;
    private double mBlurDev;
    private double mYtime;
    private double mLapTime;
    private String mLatitude;
    private String mLongitude;

    private DecimalFormat decimalFormat = new DecimalFormat(".00");

    public OverlayView(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        // We want a green box around the face:
        mPaint = new Paint();
        mPaint.setColor(Color.GREEN);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(5.0f);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeMiter(100);

        mTextPaint = new Paint();
        //mTextPaint.setAntiAlias(true);
        //mTextPaint.setDither(true);
        mTextPaint.setTextSize(30);
        mTextPaint.setColor(Color.GREEN);
        mTextPaint.setStyle(Paint.Style.FILL);
    }

    public void drawResults(List<DetectObject> results, long detectTime, int remainBuffer,
                            double blurDEV, double yTime, double lapTime, String latitude, String longitude) {
        mResults = results;
        mDetectTime = detectTime;
        mRemainBuffer = remainBuffer;
        mBlurDev = blurDEV;
        mYtime = yTime;
        mLapTime = lapTime;
        mLatitude = latitude;
        mLongitude = longitude;
    }

    public void setOrientation(int orientation) {
        mOrientation = orientation;
    }

    public void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();

        canvas.drawText("detect time:" + mDetectTime,0, 30, mTextPaint);
        canvas.drawText("blur dev:" + decimalFormat.format(mBlurDev),300, 30, mTextPaint);
        canvas.drawText("remain buffer:" + mRemainBuffer,600, 30, mTextPaint);
        canvas.drawText("yuv2y time:" + mYtime,0, 60, mTextPaint);
        canvas.drawText("laplace time:" + mLapTime,300, 60, mTextPaint);
        canvas.drawText("latitude:" + mLatitude,0, 90, mTextPaint);
        canvas.drawText("longitude:" + mLongitude,300, 90, mTextPaint);

        if (!mResults.isEmpty()) {
            canvas.save();
            RectF rectF = new RectF();

            for(int i=0; i<mResults.size(); i++){
                rectF.set(mResults.get(i).location.left * canvasWidth,
                        mResults.get(i).location.top * canvasHeight,
                        mResults.get(i).location.right * canvasWidth,
                        mResults.get(i).location.bottom * canvasHeight);
                canvas.drawRect(rectF, mPaint);
                canvas.drawText(mResults.get(i).title + ":" + mResults.get(i).confidence,rectF.left, rectF.top+20, mTextPaint);
            }
            mResults.clear();
            canvas.restore();
        }
    }
}