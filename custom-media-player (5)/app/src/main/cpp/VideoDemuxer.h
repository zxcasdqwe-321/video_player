#ifndef VIDEO_DEMUXER_H
#define VIDEO_DEMUXER_H

#include <string>
#include <thread>
#include <vector>
#include <mutex>
#include "frame.h"

// Forward declare FFmpeg structures to keep the header clean and decoupled
struct AVFormatContext;
struct AVCodecContext;
struct AVPacket;

/**
 * Custom Demuxer Engine utilizing native FFmpeg (avformat/avcodec).
 * Parses containers (.mkv, .mp4, .mov) and dispatches packets to separate channels.
 */
class VideoDemuxer {
private:
    std::string m_filePath;
    AVFormatContext* m_formatCtx = nullptr;
    
    int m_videoStreamIdx = -1;
    int m_audioStreamIdx = -1;
    int m_subStreamIdx = -1;

    bool m_isPlaying = false;
    bool m_isSeeking = false;
    std::thread m_demuxThread;
    std::mutex m_mutex;

    // Buffer queues for high-speed multi-threaded parsing
    SafeQueue<AVPacket*>* m_videoPacketQueue = nullptr;
    SafeQueue<AVPacket*>* m_audioPacketQueue = nullptr;

    void demuxLoop();

public:
    VideoDemuxer();
    ~VideoDemuxer();

    bool setDataSource(const std::string& path);
    bool start();
    void stop();
    bool seekTo(double seconds);
    
    // Track querying & Switching
    int getVideoStreamIndex() const { return m_videoStreamIdx; }
    int getAudioStreamIndex() const { return m_audioStreamIdx; }
    int getSubtitleStreamIndex() const { return m_subStreamIdx; }
    
    std::vector<int> getAudioStreams() const;
    bool selectAudioStream(int streamIndex);
    
    void setQueues(SafeQueue<AVPacket*>* videoQueue, SafeQueue<AVPacket*>* audioQueue);
    
    double getDuration() const;
};

#endif // VIDEO_DEMUXER_H
