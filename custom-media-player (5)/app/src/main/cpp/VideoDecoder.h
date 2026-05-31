#ifndef VIDEO_DECODER_H
#define VIDEO_DECODER_H

#include <thread>
#include <mutex>
#include "frame.h"
#include "AVClock.h"

// Forward declare FFmpeg codec structures
struct AVCodecContext;
struct AVPacket;

/**
 * Custom High-Performance Video Decoder.
 * Consumes H.264/HEVC native packets from VideoDemuxer and feeds Frame Buffer queues.
 */
class VideoDecoder {
private:
    AVCodecContext* m_codecCtx = nullptr;
    SafeQueue<AVPacket*>* m_packetQueue = nullptr;
    SafeQueue<VideoFrame*>* m_frameQueue = nullptr;
    AVClock* m_clock = nullptr;

    bool m_isDecoding = false;
    std::thread m_decodeThread;
    std::mutex m_mutex;

    void decodeLoop();

public:
    VideoDecoder();
    ~VideoDecoder();

    bool init(AVCodecContext* codecContext);
    void setQueues(SafeQueue<AVPacket*>* packetQueue, SafeQueue<VideoFrame*>* frameQueue);
    void setClock(AVClock* clock);

    bool start();
    void stop();
    void flush();
};

#endif // VIDEO_DECODER_H
