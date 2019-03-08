package com.qy.detect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.List;

import static com.qy.detect.CameraActivity.TAG;

public class KeepLiveService extends Service implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final int NOTIFICATION_ID=0x11;
    private static final int DESIRED_PREVIEW_WIDTH = 1920;
    private static final int DESIRED_PREVIEW_HEIGHT = 1080;

    private Camera mCamera;
    private byte[] cameraBuffer = null;
    Camera.Size cameraSize = null;


    public KeepLiveService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //API 18以下，直接发送Notification并将其置为前台
        if (Build.VERSION.SDK_INT <Build.VERSION_CODES.JELLY_BEAN_MR2) {
            startForeground(NOTIFICATION_ID, new Notification());
        } else {
            //API 18以上，发送Notification并将其置为前台后，启动InnerService
            Notification.Builder builder = new Notification.Builder(this);
            builder.setSmallIcon(R.mipmap.ic_launcher);
            startForeground(NOTIFICATION_ID, builder.build());
            startService(new Intent(this, InnerService.class));
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d("KeepLiveService", "onStartCommand");
            }
        }).start();
        return super.onStartCommand(intent, flags, startId);
    }

    public  class  InnerService extends Service{
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
        @Override
        public void onCreate() {
            super.onCreate();
            //发送与KeepLiveService中ID相同的Notification，然后将其取消并取消自己的前台显示
            Notification.Builder builder = new Notification.Builder(this);
            builder.setSmallIcon(R.mipmap.ic_launcher);
            startForeground(NOTIFICATION_ID, builder.build());
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopForeground(true);
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    manager.cancel(NOTIFICATION_ID);
                    stopSelf();
                }
            },100);
        }
        @Override
        public int onStartCommand(final Intent intent, int flags, int startId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d("InnerService", "onStartCommand");
                }
            }).start();
            return super.onStartCommand(intent, flags, startId);
        }
    }

    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        // 后视镜的摄像头捕获图像尺寸未找到手动调整的办法，只能使用默认分辨率1080*1920，
        // 所以bytes大小为1080*1920*1.5，解析的时候必须按照该尺寸
        Log.d("KeepLiveService","onPreviewFrame");

    };


    private static Camera getCameraInstance() {
        Camera camera = null;
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.d(TAG, "camera is not available");
        }
        return camera;
    }

    private void configureCamera() {
        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        cameraSize = Util.chooseOptimalSize(previewSizes, DESIRED_PREVIEW_WIDTH, DESIRED_PREVIEW_HEIGHT);
        parameters.setPreviewSize(cameraSize.width, cameraSize.height);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        parameters.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(parameters);
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        mCamera = getCameraInstance();
        try {
            configureCamera();
            mCamera.setPreviewDisplay(surfaceHolder);

            if (cameraBuffer == null) {
                int bufferSize = Util.getYUVByteSize(cameraSize.height, cameraSize.width);
                cameraBuffer = new byte[bufferSize];
            }

            mCamera.addCallbackBuffer(cameraBuffer);
            mCamera.setPreviewCallbackWithBuffer(this);

            mCamera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Could not preview the image.", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        if (surfaceHolder.getSurface() == null) {
            return;
        }
        // Try to stop the current preview:
        try {
            mCamera.stopPreview();

            if (cameraBuffer == null) {
                int bufferSize = Util.getYUVByteSize(cameraSize.height, cameraSize.width);
                cameraBuffer = new byte[bufferSize];
            }

            mCamera.addCallbackBuffer(cameraBuffer);
            mCamera.setPreviewCallbackWithBuffer(this);

            mCamera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Could not preview the image.", e);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        mCamera.setPreviewCallback(null);
        mCamera.setErrorCallback(null);
        mCamera.release();
        mCamera = null;
    }


}
