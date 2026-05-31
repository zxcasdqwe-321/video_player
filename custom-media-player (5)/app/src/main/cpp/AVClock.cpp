#include "AVClock.h"

AVClock::AVClock() {
    reset();
}

void AVClock::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_pts = 0.0;
    m_speed = 1.0;
    m_paused = true;
    m_lastUpdate = std::chrono::steady_clock::now();
}

void AVClock::setPts(double pts) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_pts = pts;
    m_lastUpdate = std::chrono::steady_clock::now();
}

double AVClock::getPts() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_paused) {
        return m_pts;
    }
    
    // Smooth time extrapolation between decoder frames
    auto now = std::chrono::steady_clock::now();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - m_lastUpdate).count();
    m_pts += (elapsedMs / 1000.0) * m_speed;
    m_lastUpdate = now;
    return m_pts;
}

void AVClock::setSpeed(double speed) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (speed >= 0.5 && speed <= 2.0) {
        m_speed = speed;
    }
}

double AVClock::getSpeed() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_speed;
}

void AVClock::pause() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_paused) {
        updateTime();
        m_paused = true;
    }
}

void AVClock::resume() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_paused) {
        m_lastUpdate = std::chrono::steady_clock::now();
        m_paused = false;
    }
}

void AVClock::adjustPts(double delta) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_pts += delta;
}

double AVClock::updateTime() {
    auto now = std::chrono::steady_clock::now();
    auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - m_lastUpdate).count();
    m_pts += (elapsedMs / 1000.0) * m_speed;
    m_lastUpdate = now;
    return m_pts;
}
