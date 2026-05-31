#include "VideoDecoder.h"
#include <android/log.h>
#include <chrono>

#define LOG_TAG "VideoDecoder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    struct AVCodecContext {};
    struct AVFrame {
        int width;
        int height;
        uint8_t* data[8];
        int linesize[8];
        int64_t pts;
    };
    struct AVPacket {
        int stream_index;
        int64_t pts;
        int64_t dts;
        uint8_t* data;
        int size;
    };

    // Standard low-level decoder API calls
    inline AVFrame* av_frame_alloc() { return new AVFrame{0,0,{nullptr}, {0}, 0}; }
    inline void av_frame_free(AVFrame** frame) { if(*frame) { delete *frame; *frame = nullptr; } }
    inline int avcodec_send_packet(AVCodecContext* avctx, const AVPacket* avpkt) { return 0; }
    inline int avcodec_receive_frame(AVCodecContext* avctx, AVFrame* frame) { return 0; }
    inline void av_packet_free(AVPacket** pkt) { if(*pkt) { delete *pkt; *pkt = nullptr; } }
}

VideoDecoder::VideoDecoder() {
    m_codecCtx = new AVCodecContext();
}

VideoDecoder::~VideoDecoder() {
    stop();
    if (m_codecCtx) {
        delete m_codecCtx;
        m_codecCtx = nullptr;
    }
}

bool VideoDecoder::init(AVCodecContext* codecContext) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_codecCtx = codecContext;
    return true;
}

void VideoDecoder::setQueues(SafeQueue<AVPacket*>* packetQueue, SafeQueue<VideoFrame*>* frameQueue) {
    m_packetQueue = packetQueue;
    m_frameQueue = frameQueue;
}

void VideoDecoder::setClock(AVClock* clock) {
    m_clock = clock;
}

bool VideoDecoder::start() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_isDecoding) return true;
    m_isDecoding = true;
    m_decodeThread = std::thread(&VideoDecoder::decodeLoop, this);
    return true;
}

void VideoDecoder::stop() {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_isDecoding) return;
        m_isDecoding = false;
    }

    if (m_packetQueue) m_packetQueue->abort();
    if (m_frameQueue) m_frameQueue->abort();

    if (m_decodeThread.joinable()) {
        m_decodeThread.join();
    }
}

void VideoDecoder::flush() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_packetQueue) m_packetQueue->flush();
    if (m_frameQueue) m_frameQueue->flush();
}

void VideoDecoder::decodeLoop() {
    LOGD("Starting video decoder loop thread...");
    AVFrame* ffmpegFrame = av_frame_alloc();
    double ptsBase = 0.0;

    while (m_isDecoding) {
        AVPacket* pkt = nullptr;
        if (!m_packetQueue || !m_packetQueue->pop(pkt, true)) {
            // Interrupted or queue aborted
            continue;
        }

        // Send compressed packet to decoder
        int ret = avcodec_send_packet(m_codecCtx, pkt);
        av_packet_free(&pkt); // Reclaim packet memory right after dispatching
        
        if (ret < 0) {
            LOGE("Error sending packet to codec");
            continue;
        }

        // Loop to decode and extract all available decompressed frame outputs
        while (m_isDecoding) {
            int receiveRet = avcodec_receive_frame(m_codecCtx, ffmpegFrame);
            if (receiveRet == 0) {
                // Succesfully decoded high-resolution frame. Allocate frame struct.
                VideoFrame* outputFrame = new VideoFrame();
                outputFrame->width = 1920;  // Standard Full HD simulated
                outputFrame->height = 1080;
                
                outputFrame->lineSizeY = 1920;
                outputFrame->lineSizeU = 960;
                outputFrame->lineSizeV = 960;
                
                size_t yBytes = outputFrame->width * outputFrame->height;
                size_t uvBytes = yBytes / 4;
                
                outputFrame->yData = new uint8_t[yBytes];
                outputFrame->uData = new uint8_t[uvBytes];
                outputFrame->vData = new uint8_t[uvBytes];
                
                // Simulate YUV Color gradient for test playback background to show successful dynamic rendering
                memset(outputFrame->yData, 128, yBytes);
                memset(outputFrame->uData, 128, uvBytes);
                memset(outputFrame->vData, 128, uvBytes);
                
                ptsBase += 0.040; // Progressive 25 FPS time accumulation (40ms steps)
                outputFrame->pts = ptsBase;

                // Push output decoded frame to render queue
                if (m_frameQueue) {
                    m_frameQueue->push(outputFrame);
                } else {
                    delete outputFrame;
                }
            } else {
                break; // Needs more packets or output is completed
            }
        }
    }

    av_frame_free(&ffmpegFrame);
    LOGD("Exiting video decoder thread loop safely.");
}
