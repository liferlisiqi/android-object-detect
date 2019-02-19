//
// Created by lsq on 19-2-16.
//
#include <jni.h>
#include <opencv2/opencv.hpp>
#include <opencv2/imgproc.hpp>
#include <android/bitmap.h>

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_qy_detect_OpencvHelper_Laplace(JNIEnv *env, jobject obj, jobject input){

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, input, &info);
    int width = info.width;
    int height = info.height;
    void* indata;
    AndroidBitmap_lockPixels(env, input, &indata);
    cv::Mat* mat = new cv::Mat(width, height, CV_8UC1, indata);
    cv::Mat* lap = new cv::Mat(width, height, CV_8UC1);

    cv::Laplacian(*mat, *lap, CV_8U);
    cv::Mat mean(1,4,CV_64F),std(1,4,CV_64F);
    cv::meanStdDev(*lap, mean, std);
    AndroidBitmap_unlockPixels(env, input);
    mat->release();
    lap->release();
    float res = std.at<double_t >(0) * std.at<double_t >(0);
    mean.release();
    std.release();
    return (jfloat)res;

}

}

