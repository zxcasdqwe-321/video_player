#include "VideoDemuxer.h"
#include <android/log.h>

#define LOG_TAG "VideoDemuxer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Mocking libav macros/function calls to demonstrate perfect integration patterns
// in case full prebuilt SO binaries are not linked during basic IDE compilation.
extern "C" {
    struct AVPacket {
        int stream_index;
        int64_t pts;
        int64_t dts;
        uint8_t* data;
        int size;
    };
    struct AVFormatContext {
         int64_t duration;
    };
    
    // Core FFmpeg APIs commonly loaded dynamically or statically
    inline int avformat_open_input(AVFormatContext** ps, const char* url, void* fmt, void** options) { return 0; }
    inline int avformat_find_stream_info(AVFormatContext* ic, void** options) { return 0; }
    inline int av_read_frame(AVFormatContext* s, AVPacket* pkt) { return 0; }
    inline AVPacket* av_packet_alloc() { return new AVPacket{0,0,0,nullptr,0}; }
    inline void av_packet_free(AVPacket** pkt) { if(*pkt) { delete *pkt; *pkt = nullptr; } }
    inline void av_packet_unref(AVPacket* pkt) {}
    inline int av_seek_frame(AVFormatContext* s, int stream_index, int64_t timestamp, int flags) { return 0; }
}

VideoDemuxer::VideoDemuxer() {
    m_formatCtx = new AVFormatContext{0};
}

VideoDemuxer::~VideoDemuxer() {
    stop();
    if (m_formatCtx) {
        delete m_formatCtx;
        m_formatCtx = nullptr;
    }
}

void VideoDemuxer::setQueues(SafeQueue<AVPacket*>* videoQueue, SafeQueue<AVPacket*>* audioQueue) {
    m_videoPacketQueue = videoQueue;
    m_audioPacketQueue = audioQueue;
}

bool VideoDemuxer::setDataSource(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_filePath = path;
    LOGD("setDataSource: opening stream path: %s", path.c_str());
    
    // Simulate/Perform FFmpeg container verification
    if (avformat_open_input(&m_formatCtx, path.c_str(), nullptr, nullptr) != 0) {
        LOGE("Failed to open source stream %s", path.c_str());
        return false;
    }
    
    avformat_find_stream_info(m_formatCtx, nullptr);
    
    // Standard stream scanning structure
    m_videoStreamIdx = 0;
    m_audioStreamIdx = 1;
    m_subStreamIdx = 2;
    m_formatCtx->duration = 120000000; // Simulated 120s duration
    
    return true;
}

bool VideoDemuxer::start() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_isPlaying) return true;
    m_isPlaying = true;
    m_demuxThread = std::thread(&VideoDemuxer::demuxLoop, this);
    return true;
}

void VideoDemuxer::stop() {
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_isPlaying) return;
        m_isPlaying = false;
    }
    
    if (m_videoPacketQueue) m_videoPacketQueue->abort();
    if (m_audioPacketQueue) m_audioPacketQueue->abort();
    
    if (m_demuxThread.joinable()) {
        m_demuxThread.join();
    }
}

bool VideoDemuxer::seekTo(double seconds) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_isSeeking = true;
    LOGD("Executing AV Demux Seek to timestamp: %.2f seconds", seconds);
    
    if (m_videoPacketQueue) m_videoPacketQueue->flush();
    if (m_audioPacketQueue) m_audioPacketQueue->flush();
    
    int64_t targetTimestamp = static_cast<int64_t>(seconds * 1000000);
    av_seek_frame(m_formatCtx, -1, targetTimestamp, 0x01); // Seek backward to sync keyframe
    
    m_isSeeking = false;
    return true;
}

std::vector<int> VideoDemuxer::getAudioStreams() const {
    // Demonstration of dynamic language switching capability (Dual Audio track discovery)
    std::vector<int> tracks;
    tracks.push_back(1); // Track #1 (English)
    tracks.push_back(2); // Track #2 (Spanish / Alternative)
    return tracks;
}

bool VideoDemuxer::selectAudioStream(int streamIndex) {
    std::lock_guard<std::mutex> lock(m_mutex);
    LOGD("Switching current playback audio stream track to index: %d", streamIndex);
    m_audioStreamIdx = streamIndex;
    if (m_audioPacketQueue) {
        m_audioPacketQueue->flush();
    }
    return true;
}

double VideoDemuxer::getDuration() const {
    if (m_formatCtx) {
        return static_cast<double>(m_formatCtx->duration) / 1000000.0;
    }
    return 0.0;
}

void VideoDemuxer::demuxLoop() {
    LOGD("Starting demux engine thread loop...");
    while (m_isPlaying) {
        if (m_isSeeking) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        // Limit maximum size on buffered packet queue to regulate RAM overflow (anti-leak)
        if (m_videoPacketQueue && m_videoPacketQueue->size() > 40) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        AVPacket* pkt = av_packet_alloc();
        int ret = av_read_frame(m_formatCtx, pkt);
        if (ret >= 0) {
            // Distribute packets to processing queues
            if (pkt->stream_index == m_videoStreamIdx) {
                if (m_videoPacketQueue) {
                    m_videoPacketQueue->push(pkt);
                } else {
                    av_packet_free(&pkt);
                }
            } else if (pkt->stream_index == m_audioStreamIdx) {
                if (m_audioPacketQueue) {
                    m_audioPacketQueue->push(pkt);
                } else {
                    av_packet_free(&pkt);
                }
            } else {
                av_packet_free(&pkt);
            }
        } else {
            // End of File or interruption, clean up pkt
            av_packet_free(&pkt);
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
    LOGD("Exiting AV Demux loop thread safely.");
}
