#ifndef VIDEO_RENDERER_H
#define VIDEO_RENDERER_H

#include <GLES2/gl2.h>
#include <mutex>
#include "frame.h"

/**
 * OpenGL ES 2.0 Renderer.
 * High-performance, low-latency rendering of YUV420p video frames via shaders.
 * Supports aspect ratio scaling options natively on graphics hardware.
 */
class VideoRenderer {
private:
    GLuint m_program = 0;
    
    // Texture IDs for Y, U, V channels
    GLuint m_textures[3] = {0};
    
    GLint m_ySamplerLoc = -1;
    GLint m_uSamplerLoc = -1;
    GLint m_vSamplerLoc = -1;
    GLint m_positionLoc = -1;
    GLint m_texCoordLoc = -1;
    GLint m_mvpMatrixLoc = -1;

    int m_videoWidth = 0;
    int m_videoHeight = 0;
    int m_surfaceWidth = 0;
    int m_surfaceHeight = 0;
    
    int m_scaleMode = 0; // 0 = Fit, 1 = Stretch, 2 = 16:9, 3 = 4:3, 4 = Crop
    float m_mvpMatrix[16];

    std::mutex m_mutex;

    GLuint compileShader(GLenum type, const char* source);
    void updateMvpMatrix();

public:
    VideoRenderer();
    ~VideoRenderer();

    // Initializes OpenGL ES pipeline and compiles custom shader shaders
    bool init();
    
    // Updates Surface dimensions
    void onSurfaceChanged(size_t width, size_t height);
    
    // Uploads YUV pixels and triggers rendering to screen
    void render(VideoFrame* frame);
    
    // Sets aspect ratios dynamically
    void setScaleMode(int mode);
    
    void release();
};

#endif // VIDEO_RENDERER_H
