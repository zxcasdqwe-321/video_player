#ifndef AV_CLOCK_H
#define AV_CLOCK_H

#include <chrono>
#include <mutex>

/**
 * High-precision Master Synchronization Clock.
 * Corrects presentation drift and adjusts timing dynamically
 * based on playback speed (0.5x - 2.0x).
 */
class AVClock {
private:
    double m_pts = 0.0; // Current presentation time stamp in seconds
    double m_speed = 1.0; // Variable playback speed (e.g., 0.5x to 2.0x)
    bool m_paused = true;
    std::chrono::steady_clock::time_point m_lastUpdate;
    std::mutex m_mutex;

public:
    AVClock();
    ~AVClock() = default;

    void setPts(double pts);
    double getPts();
    
    void setSpeed(double speed);
    double getSpeed();

    void pause();
    void resume();
    void reset();
    void adjustPts(double delta);
    
    // Updates internal clock state and computes absolute elapsed time
    double updateTime();
};

#endif // AV_CLOCK_H
