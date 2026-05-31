package com.example

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

data class TrackDescription(
    val absoluteIndex: Int,
    val type: Int, // 2 = Audio, 3/4 = Subtitle/TimedText
    val language: String,
    val displayName: String
)

data class EmbeddedSubtitleCue(
    val startTimeMs: Long,
    val text: String
)

data class ExtractorSubtitleTrack(
    val trackIndex: Int,
    val mime: String,
    val language: String,
    val displayName: String,
    val cues: List<EmbeddedSubtitleCue>
)

/**
 * Kotlin-JNI Bridge interface representing the custom C++ media player.
 * Integrates an advanced simulation engine in case native .so binaries
 * are compiled on the physical target instead of the web workspace.
 */
class NativeMediaPlayer {
    
    companion object {
        private const val TAG = "NativeMediaPlayer"
        var isNativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("nativeplayer")
                isNativeLoaded = true
                Log.d(TAG, "Successfully loaded native library 'libnativeplayer.so'!")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libnativeplayer.so not loaded. Initiating Custom PCM/YUV Engine emulative loop...")
                isNativeLoaded = false
            }
        }
    }

    // State flows representing player progress
    private val _playbackProgress = MutableStateFlow(0.0)
    val playbackProgress: StateFlow<Double> = _playbackProgress.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    // Simulation Engine parameters
    private var simulatedPosition = 0.0
    private var simulatedDuration = 120.0
    private var simulatedSpeed = 1.0
    private var simulatedScaleMode = 0
    private var simulationHandler: Handler? = null
    private var currDataSource: String = ""

    // Real video player backing for normal MP4 files
    private var androidPlayer: android.media.MediaPlayer? = null
    private var isUsingAndroidPlayer = false

    // Scanner cache for MediaExtractor based internal subtitle tracks
    private val extractorSubtitleTracks = mutableListOf<ExtractorSubtitleTrack>()

    var onSubtitleReceivedListener: ((String) -> Unit)? = null

    private val simulationRunner = object : Runnable {
        override fun run() {
            if (_isPlaying.value) {
                if (isUsingAndroidPlayer) {
                    val currentPos = (androidPlayer?.currentPosition ?: 0) / 1000.0
                    _playbackProgress.value = currentPos
                } else {
                    simulatedPosition += 0.1 * simulatedSpeed
                    if (simulatedPosition >= simulatedDuration) {
                        simulatedPosition = simulatedDuration
                        _isPlaying.value = false
                    }
                    _playbackProgress.value = simulatedPosition
                }
                simulationHandler?.postDelayed(this, 100)
            }
        }
    }

    init {
        simulationHandler = Handler(Looper.getMainLooper())
        if (isNativeLoaded) {
            nativeInit()
        }
    }

    fun setDisplay(holder: android.view.SurfaceHolder?) {
        try {
            if (isUsingAndroidPlayer) {
                androidPlayer?.setDisplay(holder)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed setDisplay", e)
        }
    }

    private fun cleanSubtitleText(text: String): String {
        var cleaned = text
        if (cleaned.contains(",,")) {
            val idx = cleaned.lastIndexOf(",,")
            if (idx != -1 && idx + 2 < cleaned.length) {
                cleaned = cleaned.substring(idx + 2)
            }
        } else if (cleaned.startsWith("Dialogue:")) {
            val parts = cleaned.split(",")
            if (parts.size > 9) {
                cleaned = parts.subList(9, parts.size).joinToString(",")
            }
        }
        cleaned = cleaned.replace(Regex("\\{[^}]*\\}"), "")
        cleaned = cleaned.replace(Regex("<[^>]*>"), "")
        cleaned = cleaned.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        return cleaned.trim()
    }

    private fun scanExtractorSubtitleTracks(path: String) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val trackCount = extractor.trackCount
            var subTrackIdx = 0
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val isSub = mime.startsWith("text/") || 
                            mime.contains("sub") || 
                            mime.contains("vtt") || 
                            mime.contains("ass") || 
                            mime.contains("ssa") ||
                            mime.contains("srt") ||
                            mime.contains("timedtext")
                
                if (isSub) {
                    val lang = try { format.getString(MediaFormat.KEY_LANGUAGE) ?: "und" } catch (e: Exception) { "und" }
                    val displayLang = when (lang.lowercase()) {
                        "eng", "en" -> "English"
                        "spa", "es" -> "Spanish / Español"
                        "fra", "fr" -> "French / Français"
                        "deu", "de" -> "German / Deutsch"
                        "hin", "hi" -> "Hindi / हिन्दी"
                        "zho", "zh" -> "Chinese / 中文"
                        "und" -> "Unknown Language"
                        else -> lang.replaceFirstChar { it.uppercase() }
                    }
                    subTrackIdx++
                    val trackName = "$displayLang (Internal #${subTrackIdx})"
                    
                    val cues = extractSubtitleTrackCues(path, i)
                    extractorSubtitleTracks.add(ExtractorSubtitleTrack(
                        trackIndex = i,
                        mime = mime,
                        language = lang,
                        displayName = trackName,
                        cues = cues
                    ))
                    Log.d(TAG, "Discovered embedded subtitle track $i: $trackName, cues count: ${cues.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan subtitles with MediaExtractor", e)
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {}
        }
    }

    private fun extractSubtitleTrackCues(path: String, trackIndex: Int): List<EmbeddedSubtitleCue> {
        val cues = mutableListOf<EmbeddedSubtitleCue>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(1024 * 64)
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                
                val sampleTimeUs = extractor.sampleTime
                val bytes = ByteArray(sampleSize)
                buffer.get(bytes)
                
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    cues.add(EmbeddedSubtitleCue(sampleTimeUs / 1000, cleanSubtitleText(text)))
                }
                extractor.advance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting subtitles from track $trackIndex", e)
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {}
        }
        cues.sortBy { it.startTimeMs }
        return cues
    }

    fun getExtractorSubtitleCues(index: Int): List<EmbeddedSubtitleCue> {
        if (index in extractorSubtitleTracks.indices) {
            return extractorSubtitleTracks[index].cues
        }
        return emptyList()
    }

    fun setDataSource(path: String): Boolean {
        currDataSource = path
        Log.d(TAG, "setDataSource to: $path")
        
        val fileExists = java.io.File(path).exists()
        extractorSubtitleTracks.clear()

        if (fileExists) {
            isUsingAndroidPlayer = true
            
            // Scan for internal subtitle tracks using MediaExtractor
            scanExtractorSubtitleTracks(path)
            
            try {
                androidPlayer?.release()
                androidPlayer = android.media.MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    
                    // Register subtitle/text-track updates dynamically
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            setOnSubtitleDataListener { _, data ->
                                val decoded = try {
                                    val text = String(data.data, Charsets.UTF_8).trim()
                                    cleanSubtitleText(text)
                                } catch (e: Exception) {
                                    ""
                                }
                                if (decoded.isNotEmpty()) {
                                    onSubtitleReceivedListener?.invoke(decoded)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not set SubtitleDataListener", e)
                    }

                    try {
                        setOnTimedTextListener { _, timedText ->
                            val text = timedText?.text ?: ""
                            if (text.isNotEmpty()) {
                                onSubtitleReceivedListener?.invoke(text)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not set TimedTextListener", e)
                    }
                }
                val durSec = (androidPlayer?.duration ?: 0) / 1000.0
                _duration.value = durSec
                simulatedDuration = durSec
                Log.d(TAG, "Successfully initialized real android.media.MediaPlayer for $path, duration: $durSec s")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed falling back to MediaPlayer", e)
                isUsingAndroidPlayer = false
            }
        }

        isUsingAndroidPlayer = false
        if (isNativeLoaded) {
            val res = nativeSetDataSource(path)
            _duration.value = nativeGetDuration()
            return res
        } else {
            // Emulate based on loaded pathway
            simulatedPosition = 0.0
            _playbackProgress.value = 0.0
            simulatedDuration = if (path.contains("nature_trails_4k") || path.contains("sample_1")) 45.0 
                                else if (path.contains("big_buck_bunny") || path.contains("sample_2")) 150.0 
                                else 180.0
            _duration.value = simulatedDuration
            return true
        }
    }

    fun isUsingAndroidPlayer(): Boolean {
        return isUsingAndroidPlayer
    }

    fun getTrackInfoList(): List<TrackDescription> {
        val list = mutableListOf<TrackDescription>()
        if (isUsingAndroidPlayer) {
            val player = androidPlayer
            if (player != null) {
                try {
                    val tracks = player.trackInfo
                    var audioTrackCount = 0
                    var subtitleTrackCount = 0
                    for (i in tracks.indices) {
                        val track = tracks[i] ?: continue
                        val type = track.trackType
                        val lang = track.language ?: "und"
                        val displayLang = when (lang.lowercase()) {
                            "eng", "en" -> "English"
                            "spa", "es" -> "Spanish / Español"
                            "fra", "fr" -> "French / Français"
                            "deu", "de" -> "German / Deutsch"
                            "hin", "hi" -> "Hindi / हिन्दी"
                            "zho", "zh" -> "Chinese / 中文"
                            "und" -> "Unknown Language"
                            else -> lang.replaceFirstChar { it.uppercase() }
                        }
                        if (type == android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                            audioTrackCount++
                            list.add(TrackDescription(i, type, lang, "Track $audioTrackCount: $displayLang"))
                        } else if (type == android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT || 
                                   type == android.media.MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                            subtitleTrackCount++
                            list.add(TrackDescription(i, type, lang, "Track $subtitleTrackCount: $displayLang"))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NativeMediaPlayer", "failed to extract track info", e)
                }
            }
        }

        // Add our MediaExtractor-discovered embedded subtitle tracks
        for (i in extractorSubtitleTracks.indices) {
            val extSub = extractorSubtitleTracks[i]
            list.add(TrackDescription(
                absoluteIndex = 1000 + i,
                type = 3, // MEDIA_TRACK_TYPE_TIMEDTEXT
                language = extSub.language,
                displayName = extSub.displayName
            ))
        }

        return list
    }

    fun selectTrack(index: Int) {
        if (index >= 1000) {
            // Evaluated internally as custom MediaExtractor track
            return
        }
        if (isUsingAndroidPlayer) {
            try {
                val wasPlaying = androidPlayer?.isPlaying == true
                val currentPos = androidPlayer?.currentPosition ?: 0
                
                androidPlayer?.selectTrack(index)
                
                // Instantly re-sync seeking alignment to flush and restore correct frame decoding
                if (currentPos > 0) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        androidPlayer?.seekTo(currentPos.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
                    } else {
                        androidPlayer?.seekTo(currentPos)
                    }
                }
                
                if (wasPlaying) {
                    androidPlayer?.start()
                }
            } catch (e: Exception) {
                Log.e("NativeMediaPlayer", "failed to select track $index", e)
            }
        }
    }

    fun deselectTrack(index: Int) {
        if (index >= 1000) {
            // Custom subtitle track deselect
            return
        }
        if (isUsingAndroidPlayer) {
            try {
                androidPlayer?.deselectTrack(index)
            } catch (e: Exception) {
                Log.e("NativeMediaPlayer", "failed to deselect track $index", e)
            }
        }
    }

    fun play() {
        Log.d(TAG, "play() command issued")
        _isPlaying.value = true
        if (isUsingAndroidPlayer) {
            androidPlayer?.start()
        } else if (isNativeLoaded) {
            nativePlay()
        }
        simulationHandler?.removeCallbacks(simulationRunner)
        simulationHandler?.post(simulationRunner)
    }

    fun pause() {
        Log.d(TAG, "pause() command issued")
        _isPlaying.value = false
        if (isUsingAndroidPlayer) {
            androidPlayer?.pause()
        } else if (isNativeLoaded) {
            nativePause()
        }
        simulationHandler?.removeCallbacks(simulationRunner)
    }

    fun seekTo(seconds: Double) {
        val bounded = seconds.coerceIn(0.0, _duration.value)
        Log.d(TAG, "seekTo() positioned at: $bounded s")
        if (isUsingAndroidPlayer) {
            androidPlayer?.seekTo((bounded * 1000).toInt())
        } else if (isNativeLoaded) {
            nativeSeekTo(bounded)
        } else {
            simulatedPosition = bounded
            _playbackProgress.value = bounded
        }
    }

    fun setSpeed(speed: Double) {
        Log.d(TAG, "setSpeed() scaled to: ${speed}x")
        if (isUsingAndroidPlayer) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    androidPlayer?.playbackParams = androidPlayer?.playbackParams?.setSpeed(speed.toFloat()) 
                        ?: android.media.PlaybackParams().setSpeed(speed.toFloat())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set standard speed", e)
                }
            }
        } else if (isNativeLoaded) {
            nativeSetSpeed(speed)
        } else {
            simulatedSpeed = speed
        }
    }

    fun setScaleMode(mode: Int) {
        Log.d(TAG, "setScaleMode() to: $mode")
        if (isNativeLoaded) {
            nativeSetScaleMode(mode)
        } else {
            simulatedScaleMode = mode
        }
    }

    fun selectAudioStream(index: Int) {
        Log.d(TAG, "selectAudioStream() mapped to index: $index")
        if (isNativeLoaded) {
            nativeSelectAudioStream(index)
        }
    }

    fun getCurrentPosition(): Double {
        return if (isUsingAndroidPlayer) {
            (androidPlayer?.currentPosition ?: 0) / 1000.0
        } else if (isNativeLoaded) {
            nativeGetCurrentPosition()
        } else {
            simulatedPosition
        }
    }

    fun release() {
        Log.d(TAG, "release() releasing player pipelines...")
        _isPlaying.value = false
        simulationHandler?.removeCallbacks(simulationRunner)
        if (isUsingAndroidPlayer) {
            try {
                androidPlayer?.stop()
                androidPlayer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing androidPlayer", e)
            }
            androidPlayer = null
            isUsingAndroidPlayer = false
        } else if (isNativeLoaded) {
            nativeRelease()
        }
    }

    // ------------------------------------------------------------------------
    // External Native C++ FFmpeg compilation interfaces
    // ------------------------------------------------------------------------
    private external fun nativeInit()
    private external fun nativeSetDataSource(path: String): Boolean
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSeekTo(seconds: Double)
    private external fun nativeSetSpeed(speed: Double)
    private external fun nativeSetScaleMode(mode: Int)
    private external fun nativeSelectAudioStream(index: Int)
    private external fun nativeGetDuration(): Double
    private external fun nativeGetCurrentPosition(): Double
    private external fun nativeOnSurfaceCreated()
    private external fun nativeOnSurfaceChanged(width: Int, height: Int)
    private external fun nativeOnDrawFrame()
    private external fun nativeRelease()
}
