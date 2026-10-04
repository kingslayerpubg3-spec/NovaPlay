package com.novaplay.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var infoText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var locked = false
    private var aspectIndex = 0
    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val aspectNames = arrayOf("Fit", "Fill", "Zoom")

    private var mode = 0
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volumeF = 0f

    private val hideInfo = Runnable { infoText.visibility = View.GONE }
    private val hideLock = Runnable { lockBtn.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("resume", MODE_PRIVATE)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        setupGestures()
    }

    private fun makeButton(label: String, action: () -> Unit): TextView {
        val ctx = this@PlayerActivity
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            setOnClickListener { action() }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        playerView = PlayerView(this)
        playerView.setShowSubtitleButton(true)
        playerView.controllerShowTimeoutMs = 3000
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        infoText = TextView(this)
        infoText.textSize = 28f
        infoText.setTextColor(Color.WHITE)
        infoText.setBackgroundColor(0x99000000.toInt())
        infoText.setPadding(40, 20, 40, 20)
        infoText.visibility = View.GONE
        root.addView(infoText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.END
        topBar.setBackgroundColor(0x55000000)
        topBar.addView(makeButton("CC+") { pickSubtitle() })
        topBar.addView(makeButton("Rotate") { rotate() })
        topBar.addView(makeButton("Aspect") { cycleAspect() })
        topBar.addView(makeButton("PiP") { enterPip() })
        topBar.addView(makeButton("Lock") { setLocked(true) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        lockBtn = makeButton("Unlock") { setLocked(false) }
        lockBtn.setBackgroundColor(0x99000000.toInt())
        lockBtn.visibility = View.GONE
        root.addView(lockBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v }
        )
        setContentView(root)
    }

    private fun showInfo(text: String) {
        infoText.text = text
        infoText.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 800)
    }

    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockBtn.visibility = View.VISIBLE
                    handler.removeCallbacks(hideLock)
                    handler.postDelayed(hideLock, 3000)
                } else if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val p = player ?: return true
                val back = e.x < playerView.width / 2
                val dur = if (p.duration == C.TIME_UNSET) Long.MAX_VALUE else p.duration
                val target = (p.currentPosition + if (back) -10000 else 10000).coerceIn(0L, dur)
                p.seekTo(target)
                showInfo(if (back) "-10s" else "+10s")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null) return false
                val p = player ?: return false
                if (mode == 0) {
                    if (abs(dx) > abs(dy)) {
                        mode = 1
                        seekTarget = p.currentPosition
                    } else if (e1.x < playerView.width / 2) {
                        mode = 2
                        val cur = window.attributes.screenBrightness
                        brightness = if (cur < 0) 0.5f else cur
                    } else {
                        mode = 3
                        volumeF = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    }
                }
                when (mode) {
                    1 -> {
                        seekTarget = (seekTarget - dx * 60).toLong().coerceAtLeast(0L)
                        showInfo(fmt(seekTarget))
                    }
                    2 -> {
                        brightness = (brightness + dy / playerView.height).coerceIn(0.01f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = brightness
                        window.attributes = lp
                        showInfo("Brightness ${(brightness * 100).toInt()}%")
                    }
                    3 -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volumeF = (volumeF + dy / playerView.height * max * 1.5f).coerceIn(0f, max.toFloat())
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeF.toInt(), 0)
                        showInfo("Volume ${volumeF.toInt()}/$max")
                    }
                }
                return true
            }
        })

        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (locked) return
                aspectIndex = if (detector.scaleFactor > 1f) 2 else 0
                playerView.resizeMode = aspectModes[aspectIndex]
                showInfo(aspectNames[aspectIndex])
            }
        })

        playerView.setOnTouchListener { _, ev ->
            sd.onTouchEvent(ev)
            if (!sd.isInProgress) gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && mode == 1) {
                player?.seekTo(seekTarget)
                mode = 0
            }
            true
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        playerView.useController = !value
        handler.removeCallbacks(hideLock)
        if (value) {
            topBar.visibility = View.GONE
            lockBtn.visibility = View.VISIBLE
            handler.postDelayed(hideLock, 3000)
        } else {
            lockBtn.visibility = View.GONE
            playerView.showController()
        }
    }

    private fun rotate() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % aspectModes.size
        playerView.resizeMode = aspectModes[aspectIndex]
        showInfo(aspectNames[aspectIndex])
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) topBar.visibility = View.GONE
    }

    private fun pickSubtitle() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            data?.data?.let { addSubtitle(it) }
        }
    }

    private fun addSubtitle(uri: Uri) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }?.lowercase() ?: ""
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition
        p.replaceMediaItem(idx, item.buildUpon().setSubtitleConfigurations(listOf(sub)).build())
        p.seekTo(idx, pos)
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    private fun initPlayer() {
        val list: ArrayList<String> = intent.getStringArrayListExtra("uris")
            ?: intent.data?.let { arrayListOf(it.toString()) }
            ?: run { finish(); return }
        val index = intent.getIntExtra("index", 0).coerceIn(0, list.size - 1)
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.setMediaItems(list.map { MediaItem.fromUri(it) })
        p.seekTo(index, prefs.getLong(list[index], 0L))
        p.prepare()
        p.playWhenReady = true
    }

    private fun savePosition() {
        val p = player ?: return
        val uri = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val dur = p.duration
        val pos = p.currentPosition
        val value = if (dur != C.TIME_UNSET && pos > dur - 5000) 0L else pos
        prefs.edit().putLong(uri, value).apply()
        intent.putExtra("index", p.currentMediaItemIndex)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
