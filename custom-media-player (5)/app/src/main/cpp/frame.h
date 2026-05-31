#ifndef DEF_FRAME_H
#define DEF_FRAME_H

#include <queue>
#include <mutex>
#include <condition_variable>
#include <memory>
#include <vector>

// Forward declaring FFmpeg types to keep compilation fast and modular
struct AVPacket;
struct AVFrame;

/**
 * Structure representing a fully decoded Video Frame ready for OpenGL rendering.
 */
struct VideoFrame {
    uint8_t* yData = nullptr;
    uint8_t* uData = nullptr;
    uint8_t* vData = nullptr;
    int width = 0;
    int height = 0;
    int lineSizeY = 0;
    int lineSizeU = 0;
    int lineSizeV = 0;
    double pts = 0.0; // Presentation timestamp

    VideoFrame() = default;
    ~VideoFrame() {
        delete[] yData;
        delete[] uData;
        delete[] vData;
    }

    // Disable copy constructor/assignment to avoid double delete
    VideoFrame(const VideoFrame&) = delete;
    VideoFrame& operator=(const VideoFrame&) = delete;

    VideoFrame(VideoFrame&& other) noexcept {
        yData = other.yData;
        uData = other.uData;
        vData = other.vData;
        width = other.width;
        height = other.height;
        lineSizeY = other.lineSizeY;
        lineSizeU = other.lineSizeU;
        lineSizeV = other.lineSizeV;
        pts = other.pts;

        other.yData = nullptr;
        other.uData = nullptr;
        other.vData = nullptr;
    }
};

/**
 * Thread-safe Packet or Frame Queue using standard condition variables.
 * Essential for the producer-consumer decoding model without high-level wrappers.
 */
template <typename T>
class SafeQueue {
private:
    std::queue<T> m_queue;
    std::mutex m_mutex;
    std::condition_variable m_condEmpty;
    std::condition_variable m_condFull;
    size_t m_maxSize = 60; // Max default capacity
    bool m_aborted = false;

public:
    SafeQueue() = default;
    
    explicit SafeQueue(size_t maxSize) : m_maxSize(maxSize) {}

    void setMaxSize(size_t size) {
        std::unique_lock<std::mutex> lock(m_mutex);
        m_maxSize = size;
    }

    void abort() {
        std::unique_lock<std::mutex> lock(m_mutex);
        m_aborted = true;
        m_condEmpty.notify_all();
        m_condFull.notify_all();
    }

    void flush() {
        std::unique_lock<std::mutex> lock(m_mutex);
        while (!m_queue.empty()) {
            m_queue.pop();
        }
        m_condFull.notify_all();
    }

    bool push(T element) {
        std::unique_lock<std::mutex> lock(m_mutex);
        while (m_queue.size() >= m_maxSize && !m_aborted) {
            m_condFull.wait(lock);
        }
        if (m_aborted) return false;
        
        m_queue.push(std::move(element));
        m_condEmpty.notify_one();
        return true;
    }

    bool pop(T& element, bool block = true) {
        std::unique_lock<std::mutex> lock(m_mutex);
        while (m_queue.empty() && !m_aborted) {
            if (!block) return false;
            m_condEmpty.wait(lock);
        }
        if (m_aborted || m_queue.empty()) return false;

        element = std::move(m_queue.front());
        m_queue.pop();
        m_condFull.notify_one();
        return true;
    }

    size_t size() {
        std::unique_lock<std::mutex> lock(m_mutex);
        return m_queue.size();
    }

    bool empty() {
        std::unique_lock<std::mutex> lock(m_mutex);
        return m_queue.empty();
    }
};

#endif // DEF_FRAME_H
