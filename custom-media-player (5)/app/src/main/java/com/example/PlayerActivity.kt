package com.example

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

data class SubtitleSubtitle(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

class PlayerActivity : ComponentActivity() {

    private companion object {
        const val TAG = "PlayerActivity"
    }

    // Interactive Media Player Interface
    private lateinit var mediaPlayer: NativeMediaPlayer

    // UI Widgets
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var realSurfaceView: SurfaceView
    private lateinit var gestureGrabber: View
    private lateinit var gestureOverlayBanner: LinearLayout
    private lateinit var gestureActionIcon: TextView
    private lateinit var gestureOverlayText: TextView
    private lateinit var txtEmbeddedSubtitles: TextView
    private lateinit var playerSeekbar: SeekBar
    private lateinit var txtTimeCurrent: TextView
    private lateinit var txtTimeDuration: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnPlayPauseCard: View
    private lateinit var btnSpeed: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSubtitles: Button
    private lateinit var btnAudio: Button
    private lateinit var btnBack: ImageView
    private lateinit var txtFileTitle: TextView
    private lateinit var videoContainer: FrameLayout

    // Optional Info labels in Portrait Mode
    private var infoResolution: TextView? = null
    private var infoSize: TextView? = null

    // Video Parameters passed from Router Intent
    private var videoId: Long = 0
    private lateinit var videoTitle: String
    private var videoDuration: Long = 0
    private var videoSize: Long = 0
    private lateinit var videoResolution: String
    private lateinit var videoPath: String
    private var isSimulated: Boolean = false

    // Controls Handling
    private val hudHandler = Handler(Looper.getMainLooper())
    private var isHudVisible = true
    private val hideHudRunnable = Runnable { hideHud() }
    private var updateJob: Job? = null

    // Aspect scaling option tracker
    private var currentScaleIndex = 0 // "Fit", "Stretch", "16:9", "4:3", "Crop"
    private val scaleLabels = arrayOf("Fit", "Stretch", "16:9", "4:3", "Crop")

    // Subtitles tracking
    private val externalSubtitles = mutableListOf<String>()
    private val externalSubtitleNames = mutableListOf<String>()
    private var activeSubtitleIndex = -1 // -1 means Off, 0 & 1 are simulated if available
    private var activeAudioTrackIndex = 0 // Track 1 by default
    private var parsedSubtitles: List<SubtitleSubtitle> = emptyList()

    private val subtitleHideHandler = Handler(Looper.getMainLooper())
    private val subtitleHideRunnable = Runnable {
        txtEmbeddedSubtitles.visibility = View.GONE
    }

    private val subtitleFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri)
            externalSubtitles.add(uri.toString())
            externalSubtitleNames.add(fileName)
            
            // Background load/parse text tracks
            loadExternalSubtitleUri(uri)
            
            val internalCount = getInternalSubtitleTracks().size
            activeSubtitleIndex = internalCount + externalSubtitles.size - 1 // Autoselect newest
            
            Toast.makeText(this, "Subtitle loaded: $fileName", Toast.LENGTH_SHORT).show()
            updateSubtitleText()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Retrieve parameters from router bundle
        videoId = intent.getLongExtra("id", -1)
        videoTitle = intent.getStringExtra("title") ?: "Unknown Video"
        videoDuration = intent.getLongExtra("duration", 0)
        videoSize = intent.getLongExtra("size", 0)
        videoResolution = intent.getStringExtra("resolution") ?: "N/A"
        videoPath = intent.getStringExtra("path") ?: ""
        isSimulated = intent.getBooleanExtra("isSimulated", false)

        // Setup Layout and Controllers
        setupLayoutAndViews()

        // Init Player Engine
        mediaPlayer = NativeMediaPlayer()
        mediaPlayer.onSubtitleReceivedListener = { subtitleText ->
            runOnUiThread {
                val internalTracks = getInternalSubtitleTracks()
                val N = internalTracks.size
                if (!isSimulated && activeSubtitleIndex != -1 && activeSubtitleIndex < N) {
                    txtEmbeddedSubtitles.text = subtitleText
                    txtEmbeddedSubtitles.visibility = View.VISIBLE
                    
                    subtitleHideHandler.removeCallbacks(subtitleHideRunnable)
                    subtitleHideHandler.postDelayed(subtitleHideRunnable, 4000)
                }
            }
        }
        mediaPlayer.setDataSource(videoPath)
        
        // Connect surface view structures
        setupVideoCanvas()

        // Apply Defaults (Required: Fit to Scale Aspect Ratio)
        videoContainer.post {
            applyAspectRatio(0) // Default scale fits automatically from launch
        }

        // Start playback
        mediaPlayer.play()
        startTrackingPosition()
        showHud()
    }

    private fun setupLayoutAndViews() {
        setContentView(R.layout.activity_player)

        // Bind layouts
        videoContainer = findViewById(R.id.video_container) ?: findViewById(R.id.player_root_container) as FrameLayout
        glSurfaceView = findViewById(R.id.gl_surface_view)
        realSurfaceView = findViewById(R.id.real_surface_view)
        gestureGrabber = findViewById(R.id.gesture_touch_grabber)
        gestureOverlayBanner = findViewById(R.id.gesture_overlay_banner)
        gestureActionIcon = findViewById(R.id.gesture_action_icon)
        gestureOverlayText = findViewById(R.id.gesture_overlay_text)
        txtEmbeddedSubtitles = findViewById(R.id.txt_embedded_subtitles)
        playerSeekbar = findViewById(R.id.player_seekbar)
        txtTimeCurrent = findViewById(R.id.txt_time_current)
        txtTimeDuration = findViewById(R.id.txt_time_duration)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPlayPauseCard = findViewById(R.id.btn_play_pause_card)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspect = findViewById(R.id.btn_aspect)
        btnSubtitles = findViewById(R.id.btn_subtitles)
        btnAudio = findViewById(R.id.btn_audio)
        btnBack = findViewById(R.id.btn_back)
        txtFileTitle = findViewById(R.id.txt_file_title)

        // Portrait specific info labels
        infoResolution = findViewById(R.id.info_resolution)
        infoSize = findViewById(R.id.info_size)

        // Initialize file title display
        txtFileTitle.text = videoTitle

        // Apply portrait info label text
        infoResolution?.text = "Resolution: $videoResolution"
        infoSize?.text = "Size: " + formatSize(videoSize)

        // Setup Buttons Click actions
        btnBack.setOnClickListener {
            onBackPressed()
        }

        btnPlayPauseCard.setOnClickListener {
            togglePlayPause()
        }

        btnSpeed.setOnClickListener {
            showSpeedSelector()
        }

        btnAspect.setOnClickListener {
            toggleAspectScaling()
        }

        btnSubtitles.setOnClickListener {
            showCustomSubtitleDialog()
        }

        btnAudio.setOnClickListener {
            showCustomAudioTrackDialog()
        }

        // Fast Forward and Rewind buttons (if present in custom Portrait layouts)
        findViewById<View>(R.id.btn_rewind)?.setOnClickListener {
            seekShift(-10)
        }
        findViewById<View>(R.id.btn_fast_forward)?.setOnClickListener {
            seekShift(15)
        }

        // Timeline Slider action
        playerSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaPlayer.duration.value
                    val targetSecs = (progress / 1000.0) * duration
                    txtTimeCurrent.text = formatDuration((targetSecs * 1000).toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hudHandler.removeCallbacks(hideHudRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val duration = mediaPlayer.duration.value
                val targetSecs = (progress / 1000.0) * duration
                mediaPlayer.seekTo(targetSecs)
                showHud()
            }
        })

        // Setup touch gestures
        setupGesturesSystem()
    }

    private fun setupVideoCanvas() {
        if (isSimulated) {
            glSurfaceView.visibility = View.VISIBLE
            realSurfaceView.visibility = View.GONE

            glSurfaceView.setEGLContextClientVersion(2)
            glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {}
                override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {}
                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    // Modern deep teal color cycles corresponding to active elapsed timeline indices
                    val elapsed = (mediaPlayer.getCurrentPosition().toFloat() % 10f) / 10f
                    android.opengl.GLES20.glClearColor(0.04f, 0.08f + 0.05f * elapsed, 0.12f + 0.10f * elapsed, 1.0f)
                    android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
                }
            })
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            glSurfaceView.visibility = View.GONE
            realSurfaceView.visibility = View.VISIBLE

            val holder = realSurfaceView.holder
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    mediaPlayer.setDisplay(h)
                }
                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    mediaPlayer.setDisplay(null)
                }
            })
        }
    }

    // Handles rotating screens natively without crash
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged() called")

        // Retain values momentarily and re-inflate properly
        val currentProgress = mediaPlayer.getCurrentPosition()
        val isPlayingLocal = mediaPlayer.isPlaying.value

        // Re-inflate layouts
        setupLayoutAndViews()

        // Reconnect surfaces to current media pipeline
        setupVideoCanvas()

        // Re-apply aspect scaling size parameters
        videoContainer.post {
            applyAspectRatio(currentScaleIndex)
        }

        // Resume tracking
        startTrackingPosition()
        showHud()
    }

    // ------------------------------------------------------------------------
    // PLAYBACK CONTROLLER COMMANDS
    // ------------------------------------------------------------------------
    private fun togglePlayPause() {
        if (mediaPlayer.isPlaying.value) {
            mediaPlayer.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            mediaPlayer.play()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun seekShift(offsetSecs: Int) {
        val curPos = mediaPlayer.getCurrentPosition()
        mediaPlayer.seekTo(curPos + offsetSecs)
        showHud()
    }

    private fun showSpeedSelector() {
        val speeds = arrayOf("0.5x (Slow)", "1.0x (Normal)", "1.25x", "1.5x", "2.0x (Fast)")
        val valSpeeds = doubleArrayOf(0.5, 1.0, 1.25, 1.5, 2.0)
        
        AlertDialog.Builder(this)
            .setTitle("Select Playback Speed")
            .setItems(speeds) { dialog, index ->
                val speed = valSpeeds[index]
                mediaPlayer.setSpeed(speed)
                btnSpeed.text = "${speed}x"
                dialog?.dismiss()

                showGestureOverlay("⚡", "Playback Speed: ${speed}x")
            }.show()
    }

    private fun toggleAspectScaling() {
        // Toggle aspect mode index
        currentScaleIndex = (currentScaleIndex + 1) % scaleLabels.size
        btnAspect.text = scaleLabels[currentScaleIndex]
        applyAspectRatio(currentScaleIndex)

        showGestureOverlay("📺", "Aspect mode: " + scaleLabels[currentScaleIndex])
    }

    private fun applyAspectRatio(modeIndex: Int) {
        val pW = videoContainer.width
        val pH = videoContainer.height
        if (pW <= 0 || pH <= 0) {
            videoContainer.post { applyAspectRatio(modeIndex) }
            return
        }

        val videoRatio = getVideoAspect()
        val parentRatio = pW.toDouble() / pH.toDouble()

        val glParams = glSurfaceView.layoutParams as FrameLayout.LayoutParams
        val realParams = realSurfaceView.layoutParams as FrameLayout.LayoutParams

        when (modeIndex) {
            0 -> { // Fit to Scale (Aspect Ratio Fit)
                val targetW: Int
                val targetH: Int
                if (videoRatio > parentRatio) {
                    targetW = pW
                    targetH = (pW / videoRatio).toInt()
                } else {
                    targetW = (pH * videoRatio).toInt()
                    targetH = pH
                }
                
                glParams.width = targetW
                glParams.height = targetH
                glParams.gravity = Gravity.CENTER
                
                realParams.width = targetW
                realParams.height = targetH
                realParams.gravity = Gravity.CENTER
            }
            1 -> { // Stretch Fullscreen
                glParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                glParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                
                realParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                realParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            2 -> { // 16:9 Aspect ratio
                val currentRatio = 16.0 / 9.0
                val targetW: Int
                val targetH: Int
                if (currentRatio > parentRatio) {
                    targetW = pW
                    targetH = (pW / currentRatio).toInt()
                } else {
                    targetW = (pH * currentRatio).toInt()
                    targetH = pH
                }

                glParams.width = targetW
                glParams.height = targetH
                glParams.gravity = Gravity.CENTER

                realParams.width = targetW
                realParams.height = targetH
                realParams.gravity = Gravity.CENTER
            }
            3 -> { // 4:3 Aspect ratio
                val currentRatio = 4.0 / 3.0
                val targetW: Int
                val targetH: Int
                if (currentRatio > parentRatio) {
                    targetW = pW
                    targetH = (pW / currentRatio).toInt()
                } else {
                    targetW = (pH * currentRatio).toInt()
                    targetH = pH
                }

                glParams.width = targetW
                glParams.height = targetH
                glParams.gravity = Gravity.CENTER

                realParams.width = targetW
                realParams.height = targetH
                realParams.gravity = Gravity.CENTER
            }
            4 -> { // Crop & Zoom (Center Crop)
                val targetW: Int
                val targetH: Int
                if (videoRatio > parentRatio) {
                    targetW = (pH * videoRatio).toInt()
                    targetH = pH
                } else {
                    targetW = pW
                    targetH = (pW / videoRatio).toInt()
                }

                glParams.width = targetW
                glParams.height = targetH
                glParams.gravity = Gravity.CENTER

                realParams.width = targetW
                realParams.height = targetH
                realParams.gravity = Gravity.CENTER
            }
        }

        glSurfaceView.layoutParams = glParams
        realSurfaceView.layoutParams = realParams
    }

    private fun getVideoAspect(): Double {
        try {
            if (videoResolution.isNotEmpty() && videoResolution != "N/A") {
                val cleanStr = videoResolution.split(" ")[0]
                val parts = cleanStr.split("x")
                if (parts.size >= 2) {
                    val w = parts[0].toDoubleOrNull() ?: 16.0
                    val h = parts[1].toDoubleOrNull() ?: 9.0
                    if (h > 0) return w / h
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating aspect ratios", e)
        }
        return 16.0 / 9.0 // Fallback to widescreen HDTV
    }

    // ------------------------------------------------------------------------
    // SWIPE AND DOUBLE TAP TOUCH GESTURES Engine
    // ------------------------------------------------------------------------
    private fun setupGesturesSystem() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleHud()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenW = gestureGrabber.width
                if (screenW <= 0) return false
                val clickX = e.x
                
                if (clickX >= screenW / 2) {
                    // Forward 15 seconds
                    val cur = mediaPlayer.getCurrentPosition()
                    mediaPlayer.seekTo(cur + 15.0)
                    showGestureOverlay("⏩ +15s", "Fast Forward")
                } else {
                    // Rewind 10 seconds
                    val cur = mediaPlayer.getCurrentPosition()
                    mediaPlayer.seekTo(cur - 10.0)
                    showGestureOverlay("⏪ -10s", "Rewind")
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val screenW = gestureGrabber.width
                val screenH = gestureGrabber.height
                if (screenW <= 0 || screenH <= 0) return false

                // If swipe is primarily horizontal, perform movie seeking!
                if (Math.abs(distanceX) > Math.abs(distanceY)) {
                    val deltaPercent = -distanceX / screenW
                    val totalDuration = mediaPlayer.duration.value
                    val frameShift = deltaPercent * totalDuration * 0.15 // Scaled sensitivity
                    val targetSec = (mediaPlayer.getCurrentPosition() + frameShift).coerceIn(0.0, totalDuration)
                    mediaPlayer.seekTo(targetSec)

                    showGestureOverlay("↕ Seek", formatDuration((targetSec * 1000).toLong()))
                    return true
                } else {
                    // Left-half vertical scroll -> Brightness controls
                    if (e1.x < screenW / 2) {
                        val layoutParams = window.attributes
                        val deltaBrightness = (distanceY / screenH) * 1.5f // sensitivity scalar
                        var newBrightness = (layoutParams.screenBrightness).let {
                            if (it < 0f) 0.5f else it
                        } + deltaBrightness
                        
                        newBrightness = newBrightness.coerceIn(0.01f, 1.0f)
                        layoutParams.screenBrightness = newBrightness
                        window.attributes = layoutParams

                        showGestureOverlay("☼ Brightness", "${(newBrightness * 100).toInt()}%")
                        return true
                    } else {
                        // Right-half vertical scroll -> Volume controls
                        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        // Cast scroll vector changes to audio levels
                        val deltaVolume = (distanceY / screenH) * maxVolume * 1.8f
                        val newVolume = (currentVolume + deltaVolume).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                        showGestureOverlay("🔊 Volume", "$newVolume / $maxVolume")
                        return true
                    }
                }
            }
        })

        gestureGrabber.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent)
            if (motionEvent.action == MotionEvent.ACTION_UP) {
                // Clear active overlays on finger release slowly
                hudHandler.postDelayed({
                    if (gestureOverlayBanner.visibility == View.VISIBLE) {
                        gestureOverlayBanner.visibility = View.GONE
                    }
                }, 1000)
            }
            true
        }
    }

    private fun showGestureOverlay(icon: String, message: String) {
        gestureActionIcon.text = icon
        gestureOverlayText.text = message
        gestureOverlayBanner.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------------
    // SUBTITLES MANAGEMENT & CUSTOM DIALOG
    // ------------------------------------------------------------------------
    private fun getInternalSubtitleTracks(): List<TrackDescription> {
        return if (isSimulated) {
            listOf(
                TrackDescription(absoluteIndex = 101, type = 3, language = "eng", displayName = "Track 1: English (eng)"),
                TrackDescription(absoluteIndex = 102, type = 3, language = "spa", displayName = "Track 2: Spanish / Español (spa)")
            )
        } else {
            mediaPlayer.getTrackInfoList().filter { it.type == 3 || it.type == 4 }
        }
    }

    private fun showCustomSubtitleDialog() {
        hudHandler.removeCallbacks(hideHudRunnable)
        
        val options = mutableListOf<String>()
        val internalTracks = getInternalSubtitleTracks()
        val N = internalTracks.size

        // 1. Add all dynamically discovered Internal Subtitle Tracks (or simulated)
        for (track in internalTracks) {
            options.add(track.displayName)
        }

        // 2. Add previously selected external files
        for (name in externalSubtitleNames) {
            options.add(name)
        }

        // 3. Always add Off option
        val offIndex = options.size
        options.add("Off")

        // 4. Always add File Picker trigger
        val openFromFileIndex = options.size
        options.add("Open from files")

        // Map selected active dot index
        val defaultCheckedIndex = if (activeSubtitleIndex == -1) {
            offIndex
        } else {
            activeSubtitleIndex
        }

        AlertDialog.Builder(this)
            .setTitle("Subtitles Selection")
            .setSingleChoiceItems(options.toTypedArray(), defaultCheckedIndex) { dialog, choiceIndex ->
                if (choiceIndex == openFromFileIndex) {
                    dialog.dismiss()
                    subtitleFilePicker.launch("*/*") // System file container picker
                } else if (choiceIndex == offIndex) {
                    // Deselect previous internal subtitle track if any
                    if (!isSimulated && activeSubtitleIndex != -1 && activeSubtitleIndex < N) {
                        try {
                            mediaPlayer.deselectTrack(internalTracks[activeSubtitleIndex].absoluteIndex)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deselecting subtitle track", e)
                        }
                    }
                    activeSubtitleIndex = -1
                    updateSubtitleText()
                    dialog.dismiss()
                    Toast.makeText(this, "Subtitles turned Off", Toast.LENGTH_SHORT).show()
                } else if (choiceIndex < N) {
                    // Selecting an internal subtitle track
                    // Deselect previous internal track if any was selected
                    if (!isSimulated && activeSubtitleIndex != -1 && activeSubtitleIndex < N) {
                        try {
                            mediaPlayer.deselectTrack(internalTracks[activeSubtitleIndex].absoluteIndex)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deselecting subtitle track", e)
                        }
                    }
                    activeSubtitleIndex = choiceIndex
                    if (!isSimulated) {
                        try {
                            mediaPlayer.selectTrack(internalTracks[choiceIndex].absoluteIndex)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error selecting subtitle track", e)
                        }
                    }
                    updateSubtitleText()
                    dialog.dismiss()
                    Toast.makeText(this, "Active internal track: ${internalTracks[choiceIndex].displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    // Selecting an external subtitle track
                    // Deselect previous internal track if any was selected
                    if (!isSimulated && activeSubtitleIndex != -1 && activeSubtitleIndex < N) {
                        try {
                            mediaPlayer.deselectTrack(internalTracks[activeSubtitleIndex].absoluteIndex)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deselecting subtitle track", e)
                        }
                    }
                    activeSubtitleIndex = choiceIndex
                    updateSubtitleText()
                    dialog.dismiss()
                    Toast.makeText(this, "Active: " + options[choiceIndex], Toast.LENGTH_SHORT).show()
                }
                showHud()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showHud()
            }
            .show()
    }

    private fun showCustomAudioTrackDialog() {
        hudHandler.removeCallbacks(hideHudRunnable)
        
        val audioTracks = if (isSimulated) {
            listOf(
                TrackDescription(absoluteIndex = 201, type = 2, language = "eng", displayName = "Track 1: English (AAC, Stereo)"),
                TrackDescription(absoluteIndex = 202, type = 2, language = "spa", displayName = "Track 2: Spanish / Español (AC3, 5.1)"),
                TrackDescription(absoluteIndex = 203, type = 2, language = "fra", displayName = "Track 3: French / Français (AAC, Stereo)")
            )
        } else {
            mediaPlayer.getTrackInfoList().filter { it.type == 2 }
        }

        val options = if (audioTracks.size <= 1) {
            listOf("Stereo")
        } else {
            audioTracks.map { it.displayName }
        }

        // Clamp pre-checked choice index
        val checkedIndex = activeAudioTrackIndex.coerceIn(0, options.size - 1)

        AlertDialog.Builder(this)
            .setTitle("Select Audio Track")
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, choiceIndex ->
                activeAudioTrackIndex = choiceIndex
                if (audioTracks.size > 1) {
                    val track = audioTracks[choiceIndex]
                    if (!isSimulated) {
                        try {
                            mediaPlayer.selectTrack(track.absoluteIndex)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error selecting audio track", e)
                        }
                    }
                    val selectedName = track.displayName
                    Toast.makeText(this, "Audio Track changed to: $selectedName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Audio Track changed to: Stereo", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                showHud()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showHud()
            }
            .show()
    }

    private fun updateSubtitleText() {
        val curPosMs = (mediaPlayer.getCurrentPosition() * 1000).toLong()
        val internalTracks = getInternalSubtitleTracks()
        val N = internalTracks.size

        if (activeSubtitleIndex == -1) {
            txtEmbeddedSubtitles.visibility = View.GONE
            return
        }

        if (activeSubtitleIndex < N) {
            if (isSimulated) {
                // Simulated virtual subtitle track timing
                val isEng = activeSubtitleIndex == 0
                val seconds = curPosMs / 1000
                val lineText = when (seconds) {
                    in 0..5 -> if (isEng) "Welcome to Custom CinePlayer!" else "¡Bienvenido a Custom CinePlayer!"
                    in 6..12 -> if (isEng) "Observe the extremely fluid playback engine." else "Observe el motor de reproducción extremadamente fluido."
                    in 13..21 -> if (isEng) "Tip: Swipe up on left for Brightness, swipe up on right for Volume." else "Consejo: Deslice a la izquierda para brillo, derecha para volumen."
                    in 22..30 -> if (isEng) "Double tap horizontal borders to skip and rewind." else "Toca los lados horizontales dos veces para saltar o retroceder."
                    in 31..42 -> if (isEng) "Full configuration rotation handling is active." else "El control de rotación de pantalla está activo sin reinicios."
                    else -> if (isEng) "Enjoy local video playback!" else "Disfrute de la reproducción de video local."
                }
                txtEmbeddedSubtitles.text = "[Simulated ${if (isEng) "Eng" else "Esp"}]\n$lineText"
                txtEmbeddedSubtitles.visibility = View.VISIBLE
            } else {
                // For real video files, text is updated asynchronously via the MediaPlayer subtitle decoder callback,
                // OR we check if this is one of our custom MediaExtractor tracks (absoluteIndex >= 1000).
                val selectedTrack = internalTracks[activeSubtitleIndex]
                if (selectedTrack.absoluteIndex >= 1000) {
                    val extTrackIdx = selectedTrack.absoluteIndex - 1000
                    val cues = mediaPlayer.getExtractorSubtitleCues(extTrackIdx)
                    if (cues.isNotEmpty()) {
                        val matchingIndex = cues.indexOfLast { curPosMs >= it.startTimeMs }
                        if (matchingIndex != -1) {
                            val cue = cues[matchingIndex]
                            val durationMs = if (matchingIndex < cues.size - 1) {
                                (cues[matchingIndex + 1].startTimeMs - cue.startTimeMs).coerceAtMost(6000L)
                            } else {
                                5000L
                            }
                            if (curPosMs <= cue.startTimeMs + durationMs) {
                                txtEmbeddedSubtitles.text = cue.text
                                txtEmbeddedSubtitles.visibility = View.VISIBLE
                            } else {
                                txtEmbeddedSubtitles.visibility = View.GONE
                            }
                        } else {
                            txtEmbeddedSubtitles.visibility = View.GONE
                        }
                    } else {
                        txtEmbeddedSubtitles.visibility = View.GONE
                    }
                }
            }
        } else {
            // Render external subtitles parsed
            val extTrackIndex = activeSubtitleIndex - N
            if (extTrackIndex >= 0 && extTrackIndex < externalSubtitles.size && parsedSubtitles.isNotEmpty()) {
                val matching = parsedSubtitles.find { curPosMs >= it.startTimeMs && curPosMs <= it.endTimeMs }
                if (matching != null) {
                    txtEmbeddedSubtitles.text = matching.text
                    txtEmbeddedSubtitles.visibility = View.VISIBLE
                } else {
                    txtEmbeddedSubtitles.visibility = View.GONE
                }
            } else {
                txtEmbeddedSubtitles.visibility = View.GONE
            }
        }
    }

    private fun loadExternalSubtitleUri(uri: Uri) {
        try {
            val contentStream = contentResolver.openInputStream(uri)
            val fullBodyText = contentStream?.bufferedReader()?.use { it.readText() } ?: ""
            parsedSubtitles = parseSrt(fullBodyText)
            Log.d(TAG, "Parsed .srt subtitle track total lines: ${parsedSubtitles.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failure parsing SRT file track", e)
            parsedSubtitles = emptyList()
            Toast.makeText(this, "SRT track parser failed to compile lines.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSrt(content: String): List<SubtitleSubtitle> {
        val list = mutableListOf<SubtitleSubtitle>()
        val lines = content.replace("\r", "").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            val seqNumber = line.toIntOrNull()
            if (seqNumber != null && i + 1 < lines.size) {
                val durationRangeLine = lines[i + 1].trim()
                if (durationRangeLine.contains("-->")) {
                    val ranges = durationRangeLine.split("-->")
                    if (ranges.size == 2) {
                        val start = parseSrtTime(ranges[0].trim())
                        val end = parseSrtTime(ranges[1].trim())
                        
                        var textStr = ""
                        var cursorIdx = i + 2
                        while (cursorIdx < lines.size) {
                            val innerLine = lines[cursorIdx].trim()
                            if (innerLine.isEmpty()) break
                            if (cursorIdx + 1 < lines.size && lines[cursorIdx + 1].contains("-->")) {
                                break
                            }
                            textStr += (if (textStr.isEmpty()) "" else "\n") + innerLine
                            cursorIdx++
                        }
                        if (start >= 0 && end >= 0) {
                            list.add(SubtitleSubtitle(start, end, textStr))
                        }
                        i = cursorIdx
                        continue
                    }
                }
            }
            i++
        }
        return list
    }

    private fun parseSrtTime(timeStr: String): Long {
        try {
            val parts = timeStr.split(":", ",")
            if (parts.size >= 4) {
                val hrs = parts[0].toLong()
                val mins = parts[1].toLong()
                val secs = parts[2].toLong()
                val ms = parts[3].toLong()
                return (hrs * 3600 + mins * 60 + secs) * 1000 + ms
            }
        } catch (e: Exception) {
            try {
                val clean = timeStr.replace(".", ",")
                val parts = clean.split(":", ",")
                if (parts.size >= 4) {
                    val hrs = parts[0].toLong()
                    val mins = parts[1].toLong()
                    val secs = parts[2].toLong()
                    val ms = parts[3].toLong()
                    return (hrs * 3600 + mins * 60 + secs) * 1000 + ms
                }
            } catch (e2: Exception) {}
        }
        return -1
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) {
                            result = cursor.getString(idx)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting filename from uri", e)
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "subtitle.srt"
    }

    // ------------------------------------------------------------------------
    // HUD AUTOMATIC VISIBILITY MANAGEMENT
    // ------------------------------------------------------------------------
    private fun toggleHud() {
        if (isHudVisible) {
            hideHud()
        } else {
            showHud()
        }
    }

    private fun showHud() {
        isHudVisible = true
        findViewById<View>(R.id.top_control_bar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.bottom_control_bar)?.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hideHudRunnable)
        hudHandler.postDelayed(hideHudRunnable, 5000)
    }

    private fun hideHud() {
        isHudVisible = false
        findViewById<View>(R.id.top_control_bar)?.visibility = View.GONE
        findViewById<View>(R.id.bottom_control_bar)?.visibility = View.GONE
    }

    private fun startTrackingPosition() {
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            while (true) {
                val curPos = mediaPlayer.getCurrentPosition()
                val duration = mediaPlayer.duration.value

                // Adjust timeline slider progress
                if (duration > 0) {
                    playerSeekbar.progress = ((curPos / duration) * 1000).toInt()
                }

                // Clock timer parameters
                txtTimeCurrent.text = formatDuration((curPos * 1000).toLong())
                txtTimeDuration.text = formatDuration((duration * 1000).toLong())

                // Subtitle position checking
                updateSubtitleText()

                delay(250)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::mediaPlayer.isInitialized && isHudVisible) {
            mediaPlayer.play()
        }
        startTrackingPosition()
    }

    override fun onPause() {
        super.onPause()
        if (this::mediaPlayer.isInitialized) {
            mediaPlayer.pause()
        }
        updateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        hudHandler.removeCallbacks(hideHudRunnable)
        if (this::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
    }

    // ------------------------------------------------------------------------
    // MEDIA VALUE FORMAT UTILITIES
    // ------------------------------------------------------------------------
    private fun formatDuration(millis: Long): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }
}
