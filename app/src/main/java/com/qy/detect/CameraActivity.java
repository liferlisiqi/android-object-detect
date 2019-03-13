package com.qy.detect;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.MemoryFile;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

//alarm
//
public class CameraActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback, LocationListener {

    public static final String TAG = CameraActivity.class.getSimpleName();
    private static final int PERMISSIONS_REQUEST = 1;
    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final String PERMISSION_FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String PERMISSION_COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final int DESIRED_PREVIEW_WIDTH = 1920;
    private static final int DESIRED_PREVIEW_HEIGHT = 1080;
    private static final int NN_INPUT_SIZE = 227;
    private static final int BUFFER_SIZE = 2;
    private static final int BlUR_THRESHHOLD = 5000;
    private static final int START_HOUR = 8;
    private static final int END_HOUR = 18;

    private Camera mCamera;
    private byte[] cameraBuffer = null;
    Camera.Size cameraSize = null;
    private DecimalFormat gpsFormat = new DecimalFormat(".000000");

    // We need the phone orientation to correctly draw the overlay:
    private int mOrientation;
    private int mOrientationCompensation;
    private OrientationEventListener mOrientationEventListener;

    // Let's keep track of the display rotation and orientation also:
    private int mDisplayRotation;
    private int mDisplayOrientation;

    // The surface view for the camera data
    private SurfaceView mSurfaceView;
    private OverlayView mOverlayView;

    //
    private Runnable postInferenceCallback;
    private Runnable converteImage;
    private Runnable putImage;
    private Runnable resultPoster;
    private Handler handler;
    private HandlerThread handlerThread;

    // captured frame
    private byte[] yuvBytes = null;
    private int[] yBytes = null;
    private int[] yCopy = null;
    private Bitmap originBitmap = null;
    private Bitmap croppedBitmap = Bitmap.createBitmap(NN_INPUT_SIZE, NN_INPUT_SIZE, Bitmap.Config.ARGB_8888);
    private LinkedBlockingQueue<int[]> yBuffer = new LinkedBlockingQueue<>(BUFFER_SIZE);
    private LinkedBlockingQueue<byte[]> yuvBuffer = new LinkedBlockingQueue<>(BUFFER_SIZE);

    // mobilessd
    private List<String> synset_words = new ArrayList<>();
    private MobileNetssd mobileNetssd = new MobileNetssd();

    // detected results
    private float[] results = null;
    private List<DetectObject> objects = new ArrayList<>();
    private float minimumConfidence = 0.5f;
    private float blurSTD;

    //
    private boolean isProcessingFrame = false;
    private boolean isComputingDetection = false;
    private boolean isDay;
    private boolean hasNetwork;

    // json
    private String SN = android.os.Build.SERIAL;
    private byte[] yuvCopy = null;
    private int hasPerson = 0;
    private int hasCar = 0;
    private String time;
    private double latitude;
    private double longitude;
    private double speed;
    private JSONObject jsonObject = new JSONObject();
    byte[] nv21Bytes;
    byte[] nv21CompressBytes;
    ByteArrayOutputStream nv21Stream = new ByteArrayOutputStream();

    //
    LocationManager locationManager;
    MqttHelper mqttHelper;

    //debug
    private Bitmap rgbBitmap = null;
    private long detectStartTime;
    private long detectTime;
    private long convertTime;
    private long lapTime;
    private int[] rgb;
    private String path = Environment.getExternalStorageDirectory().toString();
    private File file;

    private MemoryFile mMemoryFile;
    BufferBean mBufferBean;

    /**
     * app初始化
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestPermission();

        //显示摄像头捕获到的图像
        mSurfaceView = new SurfaceView(this);
        setContentView(mSurfaceView);
        //显示检测结果
        mOverlayView = new OverlayView(this);
        addContentView(mOverlayView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        //悬浮窗
        /*WindowManager mWindowManager = (WindowManager)getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        LayoutParams params = createWindowParams();
        mWindowManager.addView(mSurfaceView, params);*/

        Intent keepLiveIntent = new Intent(this, KeepLiveService.class);
        startService(keepLiveIntent);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, PERMISSION_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, PERMISSION_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,0, 0, this);

        mqttHelper = new MqttHelper();
        mqttHelper.init();

        // Create and Start the OrientationListener:
        mOrientationEventListener = new SimpleOrientationEventListener(this);
        mOrientationEventListener.enable();

        try {
            loadMobileSSD();
            loadLabel();
        } catch (IOException e) {
            Log.e("DetectActivity", "loadMobileSSD error");
        }
    }

    /**
     * 相机回调函数
     * @param bytes 实时帧数据，YV12格式
     * @param camera 相机实例
     */
    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        // 后视镜的摄像头捕获图像尺寸未找到手动调整的办法，只能使用默认分辨率1080*1920，
        // 所以bytes大小为1080*1920*1.5，解析的时候必须按照该尺寸

        try {
            if (yBytes == null) {
                yuvBytes = new byte[bytes.length];
                yBytes = new int[cameraSize.width * cameraSize.height];
                rgb = new int[cameraSize.width * cameraSize.height];
                originBitmap = Bitmap.createBitmap(cameraSize.width, cameraSize.height, Bitmap.Config.ARGB_8888);
                rgbBitmap = Bitmap.createBitmap(cameraSize.width, cameraSize.height, Bitmap.Config.ARGB_8888);
            }
        } catch (final Exception e) {
            Log.e(TAG, "Exception!");
            return;
        }

        converteImage = new Runnable() {
            @Override
            public void run() {
                long startTime = SystemClock.uptimeMillis();
                System.arraycopy(bytes, 0, yuvBytes, 0, bytes.length);
                Util.convertYUV420SPToY888(bytes, cameraSize.width, cameraSize.height, yBytes);
                convertTime = SystemClock.uptimeMillis() - startTime;
            }
        };

        putImage = new Runnable() {
            @Override
            public void run() {
                long startTime = SystemClock.uptimeMillis();
                boolean isUseful = judgeImage();
                //模糊检测，有效帧存入buffer
                lapTime = SystemClock.uptimeMillis() - startTime;
                if (yBuffer.remainingCapacity() > 0 && isUseful) {
                    try {
                        yuvBuffer.put(yuvBytes);
                        yBuffer.put(yBytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        resultPoster = new Runnable() {
            @Override
            public void run() {
                if(hasNetwork){
                    if(nv21Bytes == null)
                        nv21Bytes = new byte[yuvCopy.length];
                    Util.YV12toNV21(yuvCopy, nv21Bytes, cameraSize.width, cameraSize.height);
                    YuvImage yuv = new YuvImage(nv21Bytes, ImageFormat.NV21, cameraSize.width, cameraSize.height, null);
                    yuv.compressToJpeg(new Rect(0, 0,cameraSize.width, cameraSize.height), 50, nv21Stream);
                    byte[] nv21CompressBytes = nv21Stream.toByteArray();
                    try{
                        jsonObject.put("SN", SN);
                        jsonObject.put("image", new String(Base64.decode(nv21CompressBytes, Base64.DEFAULT), StandardCharsets.UTF_8));
                        jsonObject.put("person", hasPerson + "");
                        jsonObject.put("car", hasCar + "");
                        jsonObject.put("time", time);
                        jsonObject.put("location", latitude + "_" + longitude);
                        jsonObject.put("speed", speed+"");
                    }catch (Exception e) {
                        e.printStackTrace();
                        // nullptr problem
                        Log.d(TAG, "run:");
                    }
                    String jsonStr = jsonObject.toString();
                    mqttHelper.publish(jsonStr, false, 2);
                }
            }
        };

        postInferenceCallback = new Runnable() {
            @Override
            public void run() {
                mCamera.addCallbackBuffer(bytes);
                isProcessingFrame = false;
            }
        };

        isDay = Util.getHour() >= START_HOUR && Util.getHour() <= END_HOUR;
        hasNetwork = Util.isNetworkAvailable(this);
        //buffer ? 2
        //putImage.run();
        convertImage();

        // || !isDay
        if (isProcessingFrame || !isDay || !hasNetwork) {
            mCamera.addCallbackBuffer(bytes);
            Log.e(TAG, "Dropping frame!");
            return;
        }

        isProcessingFrame = true;

        processImage();
    }

    /**
     * 检测车辆和行人
     */
    protected void processImage() {
        readyForNextImage();

        if (isComputingDetection) {
            if(SystemClock.uptimeMillis() - detectStartTime > 1000)
                isComputingDetection = false;
            return;
        }
        isComputingDetection = true;

        //convertImage();

        if (!yBuffer.isEmpty()) {
            try {
                yuvCopy = yuvBuffer.take();
                yCopy = yBuffer.take();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            return;
        }

        //
        originBitmap.setPixels(yCopy, 0, cameraSize.width, 0, 0, cameraSize.width, cameraSize.height);
        croppedBitmap = Bitmap.createScaledBitmap(originBitmap, 227, 227, false);

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        detectStartTime = SystemClock.uptimeMillis();
                        //检测行人和车辆，
                        results = mobileNetssd.Detect(croppedBitmap);
                        detectTime = SystemClock.uptimeMillis() - detectStartTime;
                        time = Util.getTime();
                        objects.clear();
                        hasPerson = 0;
                        hasCar = 0;

                        for (int i = 0; i < results.length / 6; i++) {
                            if (results[i * 6 + 1] < minimumConfidence)
                                continue;
                            for (int j = 2; j < 6; j++) {
                                if (results[i * 6 + j] < 0) results[i * 6 + j] = 0;
                                if (results[i * 6 + j] > 1) results[i * 6 + j] = 1;
                            }
                            int ID = (int) results[i * 6];
                            if (ID == 7 || ID == 15) { //car or person
                                String title = synset_words.get((int) results[i * 6]);
                                Float confidence = results[i * 6 + 1];
                                RectF location = new RectF(results[i * 6 + 2],
                                        results[i * 6 + 3],
                                        results[i * 6 + 4],
                                        results[i * 6 + 5]);
                                objects.add(new DetectObject(ID + "", title, confidence, location));
                                if(ID == 7) hasCar = 1;
                                if(ID == 15) hasPerson = 1;
                            }
                        }

                        if(hasPerson == 1 || hasCar == 1){
                            //resultPoster.run();
                            Log.d(TAG, hasPerson + " " + hasCar);
                        }

                        int remain = yBuffer.remainingCapacity();
                        mOverlayView.drawResults(objects, detectTime, remain, blurSTD, convertTime, lapTime,
                                gpsFormat.format(latitude), gpsFormat.format(longitude));
                        mOverlayView.postInvalidate();
                        isComputingDetection = false;
                    }
                });
    }

    /**
     * 悬浮窗代码，与onCreate()中的WindowManager对应
     * @return
     */
    private LayoutParams createWindowParams() {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        // 设置为始终顶层
        layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        // 设置弹出的Window不持有焦点
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        // 大小
        layoutParams.width = 100;
        layoutParams.height = 100;
        // 位置
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        // 设置背景透明
        layoutParams.format = PixelFormat.TRANSLUCENT;
        return layoutParams;
    }

    /**
     * 在mSurfaceView预览相机数据设定
     * @param savedInstanceState
     */
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(this);
    }

    /**
     * app生命周期之一
     */
    @Override
    protected synchronized void onResume() {
        super.onResume();
        mOrientationEventListener.enable();
        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    /**
     * app生命周期之一
     */
    @Override
    protected synchronized void onPause() {
        mOrientationEventListener.disable();
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            Log.e(TAG, "Exception!");
        }
        super.onPause();
    }

    /**
     * 获取相机实例
     * @return
     */
    private static Camera getCameraInstance() {
        Camera camera = null;
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.d(TAG, "camera is not available");
        }
        return camera;
    }

    /**
     * 预览surfaceholder初始化及相机初始化，对应SurfaceHolder.Callback
     * @param surfaceHolder
     */
    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        mCamera = getCameraInstance();
        configureCamera();
        setDisplayOrientation();
        try {
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

    /**
     * 对应SurfaceHolder.Callback
     * @param surfaceHolder
     * @param format
     * @param width
     * @param height
     */
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

    /**
     * 预览结束，释放camera
     * @param surfaceHolder
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        mCamera.setPreviewCallback(null);
        mCamera.setErrorCallback(null);
        mCamera.release();
        mCamera = null;
    }

    /**
     * 相机预览方向设定
     */
    private void setDisplayOrientation() {
        // Now set the display orientation:
        mDisplayRotation = Util.getDisplayRotation(CameraActivity.this);
        mDisplayOrientation = Util.getDisplayOrientation(mDisplayRotation, 0);

        mCamera.setDisplayOrientation(mDisplayOrientation);
    }

    /**
     * 相机初始化设定
     */
    private void configureCamera() {
        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        cameraSize = Util.chooseOptimalSize(previewSizes, DESIRED_PREVIEW_WIDTH, DESIRED_PREVIEW_HEIGHT);
        parameters.setPreviewSize(cameraSize.width, cameraSize.height);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        //parameters.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(parameters);
    }

    /**
     * 方向变换代码，可以不看
     */
    private class SimpleOrientationEventListener extends OrientationEventListener {

        public SimpleOrientationEventListener(Context context) {
            super(context, SensorManager.SENSOR_DELAY_NORMAL);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            // We keep the last known orientation. So if the user first orient
            // the camera then point the camera to floor or sky, we still have
            // the correct orientation.
            if (orientation == ORIENTATION_UNKNOWN) return;
            mOrientation = Util.roundOrientation(orientation, mOrientation);
            // When the screen is unlocked, display rotation may change. Always
            // calculate the up-to-date orientationCompensation.
            int orientationCompensation = mOrientation
                    + Util.getDisplayRotation(CameraActivity.this);
            if (mOrientationCompensation != orientationCompensation) {
                mOrientationCompensation = orientationCompensation;
                mOverlayView.setOrientation(mOrientationCompensation);
            }
        }
    }

    /**
     * 申请权限
     */
    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE) ||
                    shouldShowRequestPermissionRationale(PERMISSION_FINE_LOCATION) ||
                    shouldShowRequestPermissionRationale(PERMISSION_COARSE_LOCATION)) {
                Toast.makeText(CameraActivity.this,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[]{PERMISSION_CAMERA, PERMISSION_STORAGE, PERMISSION_FINE_LOCATION, PERMISSION_COARSE_LOCATION}, PERMISSIONS_REQUEST);
        }
    }

    /**
     * 加载mobilenetSSD模型
     * @throws IOException
     */
    private void loadMobileSSD() throws IOException {
        byte[] param = null;
        byte[] bin = null;
        byte[] words = null;

        {
            InputStream assetsInputStream = getAssets().open("mobilessd_int8.param.bin");
            int available = assetsInputStream.available();
            param = new byte[available];
            int byteCode = assetsInputStream.read(param);
            assetsInputStream.close();
        }
        {
            InputStream assetsInputStream = getAssets().open("mobilessd_int8.bin");
            int available = assetsInputStream.available();
            bin = new byte[available];
            int byteCode = assetsInputStream.read(bin);
            assetsInputStream.close();
        }
        {
            InputStream assetsInputStream = getAssets().open("words.txt");
            int available = assetsInputStream.available();
            words = new byte[available];
            int byteCode = assetsInputStream.read(words);
            assetsInputStream.close();
        }
        Log.e("DetectActivity", "initMobileSSD ok");
        mobileNetssd.Init(param, bin, words);
    }

    /**
     * 加载 label
     */
    private void loadLabel() {
        try {
            AssetManager assetManager = getApplicationContext().getAssets();
            BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open("words.txt")));//这里是label的文件
            String readLine = null;
            while ((readLine = reader.readLine()) != null) {
                synset_words.add(readLine);
            }
            reader.close();
        } catch (Exception e) {
            Log.e("labelCache", "error " + e);
        }
    }

    /**
     * yuv提取y通道，buffer填充
     */
    protected void convertImage() {
        converteImage.run();
        putImage.run();
    }

    /**
     * camera帧更新
     */
    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    /**
     * json上传至服务器
     */
    protected void postResults(){
        resultPoster.run();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    /**
     * 图像模糊程度检测
     * @return
     */
    protected boolean judgeImage() {
        Bitmap grayBmp = Bitmap.createBitmap(cameraSize.width, cameraSize.height, Bitmap.Config.ARGB_8888);
        grayBmp.setPixels(yBytes, 0, cameraSize.width, 0, 0, cameraSize.width, cameraSize.height);
        blurSTD = OpencvHelper.Laplace(grayBmp);
        return blurSTD > BlUR_THRESHHOLD;
    }

    /**
     * gps获取位置和速度
     * @param location
     */
    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        speed = location.getSpeed();
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.d("Latitude","disable");
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.d("Latitude","enable");
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d("Latitude","status");
    }

}
