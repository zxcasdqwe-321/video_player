#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "VideoDemuxer.h"
#include "VideoDecoder.h"
#include "VideoRenderer.h"
#include "AVClock.h"

#define LOG_TAG "NativeMediaPlayerJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Keep static global references simulating native instance handles or linked instances
static VideoDemuxer* gDemuxer = nullptr;
static VideoDecoder* gDecoder = nullptr;
static VideoRenderer* gRenderer = nullptr;
static AVClock* gClock = nullptr;

static SafeQueue<AVPacket*>* gVideoPacketQueue = nullptr;
static SafeQueue<VideoFrame*>* gVideoFrameQueue = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeInit(JNIEnv* env, jobject thiz) {
    LOGD("nativeInit: Allocating native player registers...");
    
    gClock = new AVClock();
    gDemuxer = new VideoDemuxer();
    gDecoder = new VideoDecoder();
    gRenderer = new VideoRenderer();

    // Configure intermediate high-speed frame-buffers (producer-consumer pattern)
    gVideoPacketQueue = new SafeQueue<AVPacket*>(100);
    gVideoFrameQueue = new SafeQueue<VideoFrame*>(5);

    gDemuxer->setQueues(gVideoPacketQueue, nullptr);
    gDecoder->setQueues(gVideoPacketQueue, gVideoFrameQueue);
    gDecoder->setClock(gClock);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_NativeMediaPlayer_nativeSetDataSource(JNIEnv* env, jobject thiz, jstring path) {
    const char* nativePath = env->GetStringUTFChars(path, nullptr);
    LOGD("nativeSetDataSource: Preparing resource: %s", nativePath);
    
    bool loadStatus = gDemuxer->setDataSource(nativePath);
    
    env->ReleaseStringUTFChars(path, nativePath);
    return loadStatus ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativePlay(JNIEnv* env, jobject thiz) {
    LOGD("nativePlay: Starting threads loop...");
    if (gClock) gClock->resume();
    if (gDecoder) gDecoder->start();
    if (gDemuxer) gDemuxer->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativePause(JNIEnv* env, jobject thiz) {
    LOGD("nativePause: Pausing player threads...");
    if (gClock) gClock->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeSeekTo(JNIEnv* env, jobject thiz, jdouble seconds) {
    LOGD("nativeSeekTo: Seeking to seconds: %.2f", seconds);
    if (gDemuxer) gDemuxer->seekTo(seconds);
    if (gDecoder) gDecoder->flush();
    if (gClock) gClock->setPts(seconds);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeSetSpeed(JNIEnv* env, jobject thiz, jdouble speed) {
    LOGD("nativeSetSpeed: Changing audio/video playback speed to: %.2fx", speed);
    if (gClock) gClock->setSpeed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeSetScaleMode(JNIEnv* env, jobject thiz, jint mode) {
    LOGD("nativeSetScaleMode: Aspect Ratio mode update: %d", mode);
    if (gRenderer) gRenderer->setScaleMode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeOnSurfaceCreated(JNIEnv* env, jobject thiz) {
    LOGD("nativeOnSurfaceCreated: Initializing GPU engine context...");
    if (gRenderer) gRenderer->init();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeOnSurfaceChanged(JNIEnv* env, jobject thiz, jint width, jint height) {
    LOGD("nativeOnSurfaceChanged: Setting dimensions W: %d, H: %d", width, height);
    if (gRenderer) gRenderer->onSurfaceChanged(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeOnDrawFrame(JNIEnv* env, jobject thiz) {
    // Renders the next decoded video frame if available synchronized with master clock
    if (!gVideoFrameQueue || !gRenderer || !gClock) return;
    
    VideoFrame* frame = nullptr;
    if (gVideoFrameQueue->pop(frame, false)) { // Non-blocking check
        double masterPts = gClock->getPts();
        
        // Late frame drop thresholds (stuttering prevention)
        if (frame->pts < masterPts - 0.1) {
            LOGD("nativeOnDrawFrame: Frame too late (%.2f vs %.2f). Dropping frame.", frame->pts, masterPts);
            delete frame;
            return;
        }
        
        gRenderer->render(frame);
        delete frame;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeSelectAudioStream(JNIEnv* env, jobject thiz, jint index) {
    LOGD("nativeSelectAudioStream: Choosing dynamic language audio index: %d", index);
    if (gDemuxer) gDemuxer->selectAudioStream(index);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_NativeMediaPlayer_nativeGetDuration(JNIEnv* env, jobject thiz) {
    if (gDemuxer) return gDemuxer->getDuration();
    return 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_NativeMediaPlayer_nativeGetCurrentPosition(JNIEnv* env, jobject thiz) {
    if (gClock) return gClock->getPts();
    return 0.0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_NativeMediaPlayer_nativeRelease(JNIEnv* env, jobject thiz) {
    LOGD("nativeRelease: Disposing native structures...");
    if (gDemuxer) {
        gDemuxer->stop();
        delete gDemuxer;
        gDemuxer = nullptr;
    }
    if (gDecoder) {
        gDecoder->stop();
        delete gDecoder;
        gDecoder = nullptr;
    }
    if (gRenderer) {
        gRenderer->release();
        delete gRenderer;
        gRenderer = nullptr;
    }
    if (gClock) {
        delete gClock;
        gClock = nullptr;
    }
    
    delete gVideoPacketQueue;
    gVideoPacketQueue = nullptr;
    
    delete gVideoFrameQueue;
    gVideoFrameQueue = nullptr;
}
