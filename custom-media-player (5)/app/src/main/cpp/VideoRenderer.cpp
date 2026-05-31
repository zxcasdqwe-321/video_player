#include "VideoRenderer.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "VideoRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Vertex shader projecting normalized coordinates and applying MVP matrix
const char* VERTEX_SHADER_SRC = R"(
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    uniform mat4 uMvpMatrix;
    void main() {
        gl_Position = uMvpMatrix * aPosition;
        vTexCoord = aTexCoord;
    }
)";

// Fragment shader converting standard digital composite YUV YCbCr values
// to RGB colors utilizing the standard BT.601 conversion matrix.
const char* FRAGMENT_SHADER_SRC = R"(
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D yTexture;
    uniform sampler2D uTexture;
    uniform sampler2D vTexture;
    void main() {
        float y = texture2D(yTexture, vTexCoord).r;
        float u = texture2D(uTexture, vTexCoord).r - 0.5;
        float v = texture2D(vTexture, vTexCoord).r - 0.5;
        
        // BT.601 Color space correction conversion formula
        float r = y + 1.402 * v;
        float g = y - 0.3441 * u - 0.7141 * v;
        float b = y + 1.772 * u;
        
        gl_FragColor = vec4(r, g, b, 1.0);
    }
)";

VideoRenderer::VideoRenderer() {
    // Set identity matrix originally
    for(int i = 0; i < 16; i++) {
        m_mvpMatrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    }
}

VideoRenderer::~VideoRenderer() {
    release();
}

GLuint VideoRenderer::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE("Error compiling shader:\n%s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool VideoRenderer::init() {
    std::lock_guard<std::mutex> lock(m_mutex);
    LOGD("Initializing OpenGL ES Shader assets pipeline...");
    
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);
    
    if (vertexShader == 0 || fragmentShader == 0) {
        LOGE("Failed to load pipeline shaders.");
        return false;
    }
    
    m_program = glCreateProgram();
    glAttachShader(m_program, vertexShader);
    glAttachShader(m_program, fragmentShader);
    glLinkProgram(m_program);
    
    GLint linked = 0;
    glGetProgramiv(m_program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Failed to link shader shaders.");
        return false;
    }
    
    m_positionLoc = glGetAttribLocation(m_program, "aPosition");
    m_texCoordLoc = glGetAttribLocation(m_program, "aTexCoord");
    m_ySamplerLoc = glGetUniformLocation(m_program, "yTexture");
    m_uSamplerLoc = glGetUniformLocation(m_program, "uTexture");
    m_vSamplerLoc = glGetUniformLocation(m_program, "vTexture");
    m_mvpMatrixLoc = glGetUniformLocation(m_program, "uMvpMatrix");
    
    // Gen YUV Textures
    glGenTextures(3, m_textures);
    for(int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, m_textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    
    return true;
}

void VideoRenderer::onSurfaceChanged(size_t width, size_t height) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_surfaceWidth = width;
    m_surfaceHeight = height;
    glViewport(0, 0, width, height);
    updateMvpMatrix();
}

void VideoRenderer::setScaleMode(int mode) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_scaleMode = mode;
    updateMvpMatrix();
}

void VideoRenderer::updateMvpMatrix() {
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    
    if (m_videoWidth <= 0 || m_videoHeight <= 0 || m_surfaceWidth <= 0 || m_surfaceHeight <= 0) {
        return;
    }
    
    float videoAspect = static_cast<float>(m_videoWidth) / m_videoHeight;
    float surfaceAspect = static_cast<float>(m_surfaceWidth) / m_surfaceHeight;
    
    switch (m_scaleMode) {
        case 0: // Fit to Screen (Maintain Aspect Ratio without cropped bounds)
            if (videoAspect > surfaceAspect) {
                scaleY = surfaceAspect / videoAspect;
            } else {
                scaleX = videoAspect / surfaceAspect;
            }
            break;
            
        case 1: // Stretch full screen
            scaleX = 1.0f;
            scaleY = 1.0f;
            break;
            
        case 2: // 16:9 Cinema Wide
            videoAspect = 16.0f / 9.0f;
            if (videoAspect > surfaceAspect) {
                scaleY = surfaceAspect / videoAspect;
            } else {
                scaleX = videoAspect / surfaceAspect;
            }
            break;
            
        case 3: // 4:3 Standard
            videoAspect = 4.0f / 3.0f;
            if (videoAspect > surfaceAspect) {
                scaleY = surfaceAspect / videoAspect;
            } else {
                scaleX = videoAspect / surfaceAspect;
            }
            break;
            
        case 4: // Aspect Crop / Zoom
            if (videoAspect > surfaceAspect) {
                scaleX = videoAspect / surfaceAspect;
            } else {
                scaleY = surfaceAspect / videoAspect;
            }
            break;
            
        default:
            break;
    }
    
    // Write scaled elements back into the 1-dim matrix
    for(int i = 0; i < 16; i++) {
        m_mvpMatrix[i] = 0.0f;
    }
    m_mvpMatrix[0] = scaleX;
    m_mvpMatrix[5] = scaleY;
    m_mvpMatrix[10] = 1.0f;
    m_mvpMatrix[15] = 1.0f;
}

void VideoRenderer::render(VideoFrame* frame) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!frame || m_program == 0) return;
    
    if (m_videoWidth != frame->width || m_videoHeight != frame->height) {
        m_videoWidth = frame->width;
        m_videoHeight = frame->height;
        updateMvpMatrix();
    }
    
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    
    glUseProgram(m_program);
    
    // Bind Y texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, m_textures[0]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, frame->width, frame->height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, frame->yData);
    glUniform1i(m_ySamplerLoc, 0);
    
    // Bind U texture (Half horizontal/vertical chroma resolution)
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, m_textures[1]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, frame->width / 2, frame->height / 2, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, frame->uData);
    glUniform1i(m_uSamplerLoc, 1);
    
    // Bind V texture
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, m_textures[2]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, frame->width / 2, frame->height / 2, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, frame->vData);
    glUniform1i(m_vSamplerLoc, 2);
    
    // Upload coordinate matrix
    glUniformMatrix4fv(m_mvpMatrixLoc, 1, GL_FALSE, m_mvpMatrix);
    
    // Vertex parameters definitions
    static const GLfloat vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    glVertexAttribPointer(m_positionLoc, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(m_positionLoc);
    
    static const GLfloat texCoords[] = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };
    glVertexAttribPointer(m_texCoordLoc, 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glEnableVertexAttribArray(m_texCoordLoc);
    
    // Core render call
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void VideoRenderer::release() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_textures[0] != 0) {
        glDeleteTextures(3, m_textures);
        m_textures[0] = 0;
        m_textures[1] = 0;
        m_textures[2] = 0;
    }
    if (m_program != 0) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
}
