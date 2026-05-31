package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class VideoItem(
    val id: Long,
    val title: String,
    val duration: Long, // milliseconds
    val size: Long,     // bytes
    val resolution: String,
    val path: String,
    val isSimulated: Boolean = false
)

data class FolderItem(
    val name: String,
    val videoCount: Int,
    val path: String
)

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "TraditionalMainActivity"
    }

    // UI elements
    private lateinit var rvFolders: RecyclerView
    private lateinit var rvVideos: RecyclerView
    private lateinit var tabFolders: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var searchBarEdit: EditText
    private lateinit var searchClearBtn: ImageView
    private lateinit var sortChipName: TextView
    private lateinit var sortChipDate: TextView
    private lateinit var sortChipSize: TextView
    private lateinit var backNavigationBar: LinearLayout
    private lateinit var btnBackToFolders: TextView
    private lateinit var txtCurrentFolderPath: TextView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyStateMessage: TextView
    private lateinit var btnRescanCard: View

    // State Variables
    private var allVideos: List<VideoItem> = emptyList()
    private var selectedFolder: String? = null
    private var currentTab = "folders" // "folders" or "favorites"
    private var currentSort = "name" // "name", "date", "size"
    private var searchQuery = ""

    // Launchers
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Storage permission resolved: GRANTED")
            refreshVideoDiscovery()
        } else {
            Log.d(TAG, "Storage permission resolved: DENIED")
            evaluatePermissionDenial()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        rvFolders = findViewById(R.id.rv_folders)
        rvVideos = findViewById(R.id.rv_videos)
        tabFolders = findViewById(R.id.tab_folders)
        tabFavorites = findViewById(R.id.tab_favorites)
        searchBarEdit = findViewById(R.id.search_bar_edit)
        searchClearBtn = findViewById(R.id.search_clear_btn)
        sortChipName = findViewById(R.id.sort_chip_name)
        sortChipDate = findViewById(R.id.sort_chip_date)
        sortChipSize = findViewById(R.id.sort_chip_size)
        backNavigationBar = findViewById(R.id.back_navigation_bar)
        btnBackToFolders = findViewById(R.id.btn_back_to_folders)
        txtCurrentFolderPath = findViewById(R.id.txt_current_folder_path)
        emptyStateContainer = findViewById(R.id.empty_state_container)
        emptyStateMessage = findViewById(R.id.empty_state_message)
        btnRescanCard = findViewById(R.id.btn_rescan_card)

        // Setup Layout Managers
        rvFolders.layoutManager = LinearLayoutManager(this)
        rvVideos.layoutManager = LinearLayoutManager(this)

        // Attach Listeners
        setupListeners()

        // Trigger permission and scan flow
        triggerPermissionVerification()
    }

    private fun setupListeners() {
        // Tab Click Listeners
        tabFolders.setOnClickListener {
            switchTab("folders")
        }
        tabFavorites.setOnClickListener {
            switchTab("favorites")
        }

        // Search edit listener
        searchBarEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                searchClearBtn.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFiltersAndRefresh()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        searchClearBtn.setOnClickListener {
            searchBarEdit.setText("")
        }

        // Sorting chip listeners
        sortChipName.setOnClickListener {
            switchSort("name")
        }
        sortChipDate.setOnClickListener {
            switchSort("date")
        }
        sortChipSize.setOnClickListener {
            switchSort("size")
        }

        // Back to Folders button
        btnBackToFolders.setOnClickListener {
            selectedFolder = null
            applyFiltersAndRefresh()
        }

        // Rescan button
        btnRescanCard.setOnClickListener {
            triggerPermissionVerification()
            Toast.makeText(this, "Scanning video containers...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchTab(tab: String) {
        if (currentTab == tab) return
        currentTab = tab
        selectedFolder = null // Reset folder state on tab switch

        if (tab == "folders") {
            tabFolders.setBackgroundColor(ColorUtils.parseHex("#2563EB"))
            tabFolders.setTextColor(ColorUtils.parseHex("#FFFFFF"))
            tabFavorites.setBackground(null)
            tabFavorites.setTextColor(ColorUtils.parseHex("#999999"))
        } else {
            tabFavorites.setBackgroundColor(ColorUtils.parseHex("#2563EB"))
            tabFavorites.setTextColor(ColorUtils.parseHex("#FFFFFF"))
            tabFolders.setBackground(null)
            tabFolders.setTextColor(ColorUtils.parseHex("#999999"))
        }

        applyFiltersAndRefresh()
    }

    private fun switchSort(sort: String) {
        if (currentSort == sort) return
        currentSort = sort

        // Update Sorting chip borders & colors
        val activeBg = ColorUtils.parseHex("#1A3B82F6")
        val activeText = ColorUtils.parseHex("#3B82F6")
        val inactiveBg = ColorUtils.parseHex("#1A1C1E")
        val inactiveText = ColorUtils.parseHex("#888888")

        sortChipName.setBackgroundColor(if (sort == "name") activeBg else inactiveBg)
        sortChipName.setTextColor(if (sort == "name") activeText else inactiveText)

        sortChipDate.setBackgroundColor(if (sort == "date") activeBg else inactiveBg)
        sortChipDate.setTextColor(if (sort == "date") activeText else inactiveText)

        sortChipSize.setBackgroundColor(if (sort == "size") activeBg else inactiveBg)
        sortChipSize.setTextColor(if (sort == "size") activeText else inactiveText)

        applyFiltersAndRefresh()
    }

    private fun applyFiltersAndRefresh() {
        if (allVideos.isEmpty()) {
            showEmptyState(if (searchQuery.isNotEmpty()) "No match for your search query." else "Initializing storage scanner...")
            return
        }

        // Apply global search query filtering
        val searchedVideos = if (searchQuery.isEmpty()) {
            allVideos
        } else {
            allVideos.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        if (currentTab == "folders") {
            // Folders logic
            if (searchQuery.isNotEmpty()) {
                // If search is active, skip folders structure and show matching VIDEOS directly!
                backNavigationBar.visibility = View.GONE
                rvFolders.visibility = View.GONE
                rvVideos.visibility = View.VISIBLE

                setVideoAdapter(sortAndFilterVideos(searchedVideos))
            } else if (selectedFolder != null) {
                // Inside secondary selected folder view
                backNavigationBar.visibility = View.VISIBLE
                txtCurrentFolderPath.text = "Home > $selectedFolder"
                rvFolders.visibility = View.GONE
                rvVideos.visibility = View.VISIBLE

                val folderVideos = searchedVideos.filter {
                    val fName = File(it.path).parentFile?.name ?: "Internal"
                    fName == selectedFolder
                }
                setVideoAdapter(sortAndFilterVideos(folderVideos))
            } else {
                // Main folders browsing view
                backNavigationBar.visibility = View.GONE
                rvFolders.visibility = View.VISIBLE
                rvVideos.visibility = View.GONE

                val folderGroups = searchedVideos.groupBy { File(it.path).parentFile?.name ?: "Media" }
                val foldersList = folderGroups.map { (name, list) ->
                    FolderItem(name = name, videoCount = list.size, path = list.first().path)
                }.sortedBy { it.name }

                setFolderAdapter(foldersList)
            }
        } else {
            // Favorites logic
            backNavigationBar.visibility = View.GONE
            rvFolders.visibility = View.GONE
            rvVideos.visibility = View.VISIBLE

            val favVideos = searchedVideos.filter { isFavorite(this, it.path) }
            setVideoAdapter(sortAndFilterVideos(favVideos))
        }
    }

    private fun sortAndFilterVideos(videosList: List<VideoItem>): List<VideoItem> {
        return when (currentSort) {
            "name" -> videosList.sortedBy { it.title.lowercase() }
            "size" -> videosList.sortedByDescending { it.size }
            "date" -> videosList.sortedByDescending {
                if (it.isSimulated) {
                    it.id // Keep ordered for simulation
                } else {
                    File(it.path).lastModified()
                }
            }
            else -> videosList
        }
    }

    private fun setFolderAdapter(folders: List<FolderItem>) {
        if (folders.isEmpty()) {
            showEmptyState("No folders match.")
            return
        }
        hideEmptyState()
        rvFolders.adapter = FolderAdapter(folders) { folder ->
            selectedFolder = folder.name
            applyFiltersAndRefresh()
        }
    }

    private fun setVideoAdapter(videos: List<VideoItem>) {
        if (videos.isEmpty()) {
            showEmptyState(if (searchQuery.isNotEmpty()) "No matching video items found." else "This section is empty.")
            return
        }
        hideEmptyState()
        rvVideos.adapter = VideoAdapter(videos, this,
            onVideoClick = { video ->
                playVideoInActivity(video)
            },
            onFavoriteToggle = { video ->
                toggleFavorite(this, video.path)
                applyFiltersAndRefresh()
            }
        )
    }

    private fun showEmptyState(msg: String) {
        emptyStateMessage.text = msg
        emptyStateContainer.visibility = View.VISIBLE
        rvFolders.visibility = View.GONE
        rvVideos.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
    }

    private fun playVideoInActivity(video: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("id", video.id)
            putExtra("title", video.title)
            putExtra("duration", video.duration)
            putExtra("size", video.size)
            putExtra("resolution", video.resolution)
            putExtra("path", video.path)
            putExtra("isSimulated", video.isSimulated)
        }
        startActivity(intent)
    }

    // ------------------------------------------------------------------------
    // PERMISSIONS MANAGEMENT
    // ------------------------------------------------------------------------
    private fun triggerPermissionVerification() {
        val permission = getTargetPermission()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Storage permission resolved: GRANTED initially")
            refreshVideoDiscovery()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun getTargetPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun evaluatePermissionDenial() {
        val permission = getTargetPermission()
        if (shouldShowRequestPermissionRationale(permission)) {
            Log.d(TAG, "Permission denied normally once. Display rationale.")
            showSoftRationDialog()
        } else {
            Log.d(TAG, "Permission denied with never ask again. Display hard restriction.")
            showHardDenialDialog()
        }
    }

    private fun showSoftRationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Access Required")
            .setMessage("The Custom Video Player needs storage access to scan, index and play media files located on your device storage.")
            .setCancelable(false)
            .setPositiveButton("Try Again") { dialog, _ ->
                dialog?.dismiss()
                permissionLauncher.launch(getTargetPermission())
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog?.dismiss()
                loadSimulationOnly()
            }
            .show()
    }

    private fun showHardDenialDialog() {
        AlertDialog.Builder(this)
            .setTitle("Access Restricted")
            .setMessage("Local media scanning is locked. To browse local movies, please open app settings and manually approve media storage access, or continue with dynamic video streams.")
            .setCancelable(false)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog?.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Simulation Mode") { dialog, _ ->
                dialog?.dismiss()
                loadSimulationOnly()
            }
            .show()
    }

    private fun loadSimulationOnly() {
        allVideos = generateSimulationSamples()
        applyFiltersAndRefresh()
    }

    private fun refreshVideoDiscovery() {
        val queried = queryDeviceStorageVideos()
        val finalVideos = if (queried.isEmpty()) {
            generateSimulationSamples()
        } else {
            queried
        }
        allVideos = finalVideos
        
        runOnUiThread {
            applyFiltersAndRefresh()
        }
    }

    private fun queryDeviceStorageVideos(): List<VideoItem> {
        val list = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RESOLUTION,
            MediaStore.Video.Media.DATA
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val resCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Video"
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val resolution = cursor.getString(resCol) ?: "N/A"
                    val path = cursor.getString(dataCol) ?: ""

                    // Filter empty path
                    if (path.isNotEmpty()) {
                        list.add(VideoItem(id, title, duration, size, resolution, path))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception scanning storage: ", e)
        }
        return list
    }

    private fun generateSimulationSamples(): List<VideoItem> {
        return listOf(
            VideoItem(
                id = -101,
                title = "Nature Trails.mkv",
                duration = 75000,
                size = 142010834,
                resolution = "3840x2160 (Cinema 4K)",
                path = "/storage/emulated/0/Movies/nature_trails_4k.mkv",
                isSimulated = true
            ),
            VideoItem(
                id = -102,
                title = "Big Buck Bunny.mp4",
                duration = 180000,
                size = 89045100,
                resolution = "1920x1080 (HD)",
                path = "/storage/emulated/0/Download/big_buck_bunny.mp4",
                isSimulated = true
            ),
            VideoItem(
                id = -103,
                title = "Tears of Steel.webm",
                duration = 240000,
                size = 352109841,
                resolution = "7683x4320 (Dynamic 8K)",
                path = "/storage/emulated/0/DCIM/tears_of_steel_8k.webm",
                isSimulated = true
            )
        )
    }

    // ------------------------------------------------------------------------
    // OFFLINE PERSISTENCE HELPERS
    // ------------------------------------------------------------------------
    fun isFavorite(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet("favorite_paths", emptySet()) ?: emptySet()
        return favorites.contains(path)
    }

    fun toggleFavorite(context: Context, path: String) {
        val prefs = context.getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet("favorite_paths", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (favorites.contains(path)) {
            favorites.remove(path)
        } else {
            favorites.add(path)
        }
        prefs.edit().putStringSet("favorite_paths", favorites).apply()
    }
}

// ----------------------------------------------------------------------------
// ADAPTERS SECTION
// ----------------------------------------------------------------------------
class FolderAdapter(
    private val folders: List<FolderItem>,
    private val onFolderClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtFolderName: TextView = view.findViewById(R.id.txt_folder_name)
        val txtFolderCount: TextView = view.findViewById(R.id.txt_folder_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = folders[position]
        holder.txtFolderName.text = item.name
        holder.txtFolderCount.text = "${item.videoCount} " + (if (item.videoCount == 1) "Video" else "Videos")
        holder.itemView.setOnClickListener { onFolderClick(item) }
    }

    override fun getItemCount(): Int = folders.size
}

class VideoAdapter(
    private val videos: List<VideoItem>,
    private val context: Context,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onFavoriteToggle: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtVideoTitle: TextView = view.findViewById(R.id.txt_video_title)
        val txtVideoDuration: TextView = view.findViewById(R.id.txt_video_duration)
        val txtVideoSize: TextView = view.findViewById(R.id.txt_video_size)
        val txtIsSimulated: TextView = view.findViewById(R.id.txt_is_simulated)
        val btnFavoriteStar: ImageView = view.findViewById(R.id.btn_favorite_star)
        val txtResolutionBadge: TextView = view.findViewById(R.id.txt_resolution_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = videos[position]
        holder.txtVideoTitle.text = item.title
        holder.txtVideoDuration.text = formatDuration(item.duration)
        holder.txtVideoSize.text = formatSize(item.size)

        // Show/hide simulation badge
        holder.txtIsSimulated.visibility = if (item.isSimulated) View.VISIBLE else View.GONE

        // Assign resolution badge
        holder.txtResolutionBadge.text = if (item.resolution.contains("4K", ignoreCase = true) || item.resolution.contains("2160")) "4K"
                                         else if (item.resolution.contains("8K", ignoreCase = true)) "8K"
                                         else "HD"

        // Set Favorite Star Image
        val isFav = (context as MainActivity).isFavorite(context, item.path)
        holder.btnFavoriteStar.setImageResource(
            if (isFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )

        holder.itemView.setOnClickListener { onVideoClick(item) }
        holder.btnFavoriteStar.setOnClickListener { onFavoriteToggle(item) }
    }

    override fun getItemCount(): Int = videos.size

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

// Helper utility for parsing colors without resource files
object ColorUtils {
    fun parseHex(colorStr: String): Int {
        return android.graphics.Color.parseColor(colorStr)
    }
}
