package com.qy.detect;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.MemoryFile;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

public class JavaCameraView2 extends CameraBridgeViewBase implements Camera.PreviewCallback {

    private static final int MAGIC_TEXTURE_ID = 10;
    private static final String TAG = "JavaCameraView";

    private byte mBuffer[];
    private Mat[] mFrameChain;
    private int mChainIdx = 0;
    private Thread mThread;
    private boolean mStopThread;

    protected Camera mCamera;
    protected JavaCameraView.JavaCameraFrame[] mCameraFrame;
    private SurfaceTexture mSurfaceTexture;


    private MemoryFile mMemoryFile;
    BufferBean mBufferBean;

    private  static class JavaCameraSizeAccessor implements ListItemAccessor {

        @Override
        public int getWidth(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.width;
        }

        @Override
        public int getHeight(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.height;
        }
    }

    public JavaCameraView2(Context context, int cameraId) {
        super(context, cameraId);
    }

    public JavaCameraView2(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected boolean initializeCamera(int width, int height) {
        Log.d(TAG, "Initialize java camera");
        boolean result = true;
        synchronized (this) {
            mCamera = null;

            if (mCameraIndex == CAMERA_ID_ANY) {
                Log.d(TAG, "Trying to open camera with old open()");
                try {
                    mCamera = Camera.open();
                }
                catch (Exception e){
                    Log.e(TAG, "Camera is not available (in use or does not exist): " + e.getLocalizedMessage());
                }

                if(mCamera == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    boolean connected = false;
                    for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(camIdx) + ")");
                        try {
                            mCamera = Camera.open(camIdx);
                            connected = true;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + camIdx + "failed to open: " + e.getLocalizedMessage());
                        }
                        if (connected) break;
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    int localCameraIndex = mCameraIndex;
                    if (mCameraIndex == CAMERA_ID_BACK) {
                        Log.i(TAG, "Trying to open back camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    } else if (mCameraIndex == CAMERA_ID_FRONT) {
                        Log.i(TAG, "Trying to open front camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    }
                    if (localCameraIndex == CAMERA_ID_BACK) {
                        Log.e(TAG, "Back camera not found!");
                    } else if (localCameraIndex == CAMERA_ID_FRONT) {
                        Log.e(TAG, "Front camera not found!");
                    } else {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(localCameraIndex) + ")");
                        try {
                            mCamera = Camera.open(localCameraIndex);
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + localCameraIndex + "failed to open: " + e.getLocalizedMessage());
                        }
                    }
                }
            }

            if (mCamera == null)
                return false;

            /* Now set camera parameters */
            try {
                Camera.Parameters params = mCamera.getParameters();
                Log.d(TAG, "getSupportedPreviewSizes()");
                List<Camera.Size> sizes = params.getSupportedPreviewSizes();

                if (sizes != null) {
                    /* Select the size that fits surface considering maximum size allowed */
                    Size frameSize = calculateCameraFrameSize(sizes, new JavaCameraSizeAccessor(), width, height);

                    params.setPreviewFormat(ImageFormat.YV12);
                    Log.d(TAG, "Set preview size to " + Integer.valueOf((int)frameSize.width) + "x" + Integer.valueOf((int)frameSize.height));
                    params.setPreviewSize((int)frameSize.width, (int)frameSize.height);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && !Build.MODEL.equals("GT-I9100"))
                        params.setRecordingHint(true);

                    List<String> FocusModes = params.getSupportedFocusModes();
                    if (FocusModes != null && FocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                    {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    }

                    mCamera.setParameters(params);
                    params = mCamera.getParameters();

                    mFrameWidth = params.getPreviewSize().width;
                    mFrameHeight = params.getPreviewSize().height;

//                    //创建共享内存区-start
                   int BUFFER_SIZE = mFrameWidth* mFrameHeight * 3 / 2;     //FIXME: if preview size is bigger than 960x540, need modify this value
                   int MEMORY_SIZE = BUFFER_SIZE + 1;
                    mBufferBean = new BufferBean(BUFFER_SIZE);
                    try {
                        mMemoryFile = new MemoryFile(TAG, MEMORY_SIZE);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    setMemoryShareFD(mCamera);
//
//                    //创建共享区-end
                    if ((getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT) && (getLayoutParams().height == ViewGroup.LayoutParams.MATCH_PARENT))
                        mScale = Math.min(((float)height)/mFrameHeight, ((float)width)/mFrameWidth);
                    else
                        mScale = 0;

                    if (mFpsMeter != null) {
                        mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
                    }

                    int size = mFrameWidth * mFrameHeight;
                    size  = size * ImageFormat.getBitsPerPixel(params.getPreviewFormat()) / 8;
                    mBuffer = new byte[size];

//                    mCamera.addCallbackBuffer(mBuffer);
//                    mCamera.setPreviewCallbackWithBuffer(this);

                    mFrameChain = new Mat[2];
                    mFrameChain[0] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);
                    mFrameChain[1] = new Mat(mFrameHeight + (mFrameHeight/2), mFrameWidth, CvType.CV_8UC1);

                    AllocateCache();

                    mCameraFrame = new JavaCameraFrame[2];
                    mCameraFrame[0] = new JavaCameraFrame(mFrameChain[0], mFrameWidth, mFrameHeight,ImageFormat.YV12);
                    mCameraFrame[1] = new JavaCameraFrame(mFrameChain[1], mFrameWidth, mFrameHeight,ImageFormat.YV12);
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
//                        mSurfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
//                        mCamera.setPreviewTexture(mSurfaceTexture);
//                    } else
//                        mCamera.setPreviewDisplay(null);

                    /* Finally we are ready to start the preview */
                    Log.d(TAG, "startPreview");
//                    mCamera.startPreview();
                }
                else
                    result = false;
            } catch (Exception e) {
                result = false;
                e.printStackTrace();
            }
        }

        return result;
    }



    public int setMemoryShareFD(Camera camera) {
        int err=-1;
        try {
            if (camera != null) {
                Method method = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
                FileDescriptor fd = (FileDescriptor) method.invoke(mMemoryFile);
                setMemoryFileFileDescriptor(camera, fd);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return err;
    }

    private int setMemoryFileFileDescriptor(Camera camera, FileDescriptor fileDescriptor) {
        try {
            Class<?> classType = Class.forName ("android.hardware.Camera");
            Method meth = classType.getMethod ("setMemoryFileFileDescriptor", new Class[] {FileDescriptor.class});
            if (meth != null) {
                Object retobj = meth.invoke(camera, new Object[] {fileDescriptor});
                return (Integer)retobj;
            } else {
            }
        } catch (Exception e)  {
        }
        return -1;
    }


    protected void releaseCamera() {
        synchronized (this) {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);

                mCamera.release();
            }
            mCamera = null;
            if (mFrameChain != null) {
                mFrameChain[0].release();
                mFrameChain[1].release();
            }
            if (mCameraFrame != null) {
                mCameraFrame[0].release();
                mCameraFrame[1].release();
            }
        }
    }

    private boolean mCameraFrameReady = false;

    @Override
    protected boolean connectCamera(int width, int height) {

        /* 1. We need to instantiate camera
         * 2. We need to start thread which will be getting frames
         */
        /* First step - initialize camera connection */
        Log.d(TAG, "Connecting to camera");
        if (!initializeCamera(width, height))
            return false;

        mCameraFrameReady = false;

        /* now we can start update thread */
        Log.d(TAG, "Starting processing thread");
        mStopThread = false;
        mThread = new Thread(new CameraWorker());
        String name=mThread.getName();
        String id=mThread.getId()+"";
        mThread.start();

        return true;
    }

    @Override
    protected void disconnectCamera() {
        /* 1. We need to stop thread which updating the frames
         * 2. Stop camera and release it
         */
        Log.d(TAG, "Disconnecting from camera");
        try {
            mStopThread = true;
            Log.d(TAG, "Notify thread");
            synchronized (this) {
                this.notify();
            }
            Log.d(TAG, "Wating for thread");
            if (mThread != null)
                mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mThread =  null;
        }

        /* Now release camera */
        releaseCamera();

        mCameraFrameReady = false;
    }

    /**
     * 相机返回数据
     * @param frame
     * @param arg1
     */
    @Override
    public void onPreviewFrame(byte[] frame, Camera arg1) {
        Log.d(TAG, "Preview Frame received. Frame size: " + frame.length);
        synchronized (this) {
//            mFrameChain[mChainIdx].put(0, 0, frame);
//            mCameraFrameReady = true;
//            this.notify();
            writeMemoryByte( frame);
        }
        if (mCamera != null)
            mCamera.addCallbackBuffer(mBuffer);
    }




   public int writeMemoryByte(byte[] frame){
       try {
           if (mMemoryFile != null) {
               mMemoryFile.readBytes(mBufferBean.isCanRead, 0, 0, 1);
               if (mBufferBean.isCanRead[0] == 0) {    //写
                   mMemoryFile.writeBytes(frame, 0, 1, mBufferBean.mBuffer.length);

                   mBufferBean.isCanRead[0] = 1;   //可读
                   mMemoryFile.writeBytes(mBufferBean.isCanRead, 0, 0, 1);
               }
           }
       } catch (Exception e) {
           e.printStackTrace();
           return -1;
       }
       return 0;
   }


   public int  readMemoryByte(){
       try {
           if (mMemoryFile != null) {
               mMemoryFile.readBytes(mBufferBean.isCanRead, 0, 0, 1);
               if (mBufferBean.isCanRead[0] == 1) {    //可读
                   mMemoryFile.readBytes(mBufferBean.mBuffer, 1, 0, mBufferBean.mBuffer.length);
                   if (mBufferBean.mBuffer != null) {
                       mFrameChain[0].put(0, 0, mBufferBean.mBuffer);
                       deliverAndDrawFrame(mCameraFrame[0]);
                   }
                   mBufferBean.isCanRead[0] = 0;   //可写
                   mMemoryFile.writeBytes(mBufferBean.isCanRead, 0, 0, 1);
               }
           }
       } catch (Exception e) {
           return -1;
       }
       return 0;
   }

    private class CameraWorker implements Runnable {
        @Override
        public void run() {
            do {
                readMemoryByte();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (!mStopThread);
            Log.d(TAG, "Finish processing thread");
        }
    }
}
