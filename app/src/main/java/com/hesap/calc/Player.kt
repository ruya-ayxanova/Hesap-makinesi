package com.hesap.calc

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * VLC tarzı tam ekran oynatıcı.
 * - Çift dokun: sol/sağ 10 sn geri/ileri, orta oynat/duraklat
 * - Sol yarıda dikey kaydır: parlaklık, sağ yarıda: ses
 * - Yatay kaydır: ileri/geri sar
 * - Hız (0.5x-2x), görüntü oranı (sığdır/doldur/yakınlaştır), ekranı döndür
 */
class VideoScreen(
    private val act: ComponentActivity,
    file: File,
    private val onClose: () -> Unit
) : FrameLayout(act) {

    private val player: ExoPlayer = ExoPlayer.Builder(act).build()
    private val surface = SurfaceView(act)
    private val handler = Handler(Looper.getMainLooper())
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val topBar = LinearLayout(act)
    private val bottomBar = LinearLayout(act)
    private val seek = SeekBar(act)
    private val tvPos = TextView(act)
    private val tvDur = TextView(act)
    private val hud = TextView(act)
    private lateinit var btnPlay: TextView
    private lateinit var speedBtn: TextView

    private var aspectMode = 0
    private var videoW = 0
    private var videoH = 0
    private var pixelRatio = 1f
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 2
    private var dragging = false
    private var landscape = false
    private var released = false

    // hareket durumu: 0 yok, 1 parlaklık, 2 ses, 3 sarma
    private var mode = 0
    private var startPos = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volF = 0f

    private val hideControls = Runnable { if (player.isPlaying && !dragging) setControls(false) }
    private val hideHud = Runnable { hud.visibility = GONE }
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val d = dur()
            if (!dragging) {
                seek.max = (d / 1000).toInt()
                seek.progress = (player.currentPosition / 1000).toInt()
                tvPos.text = fmt(player.currentPosition)
            }
            tvDur.text = fmt(d)
            handler.postDelayed(this, 500)
        }
    }

    private val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setControls(topBar.visibility != VISIBLE)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val third = width / 3f
            when {
                e.x < third -> seekBy(-10_000)
                e.x > 2 * third -> seekBy(10_000)
                else -> togglePlay()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e1 == null) return false
            if (mode == 0) {
                mode = when {
                    abs(e2.x - e1.x) > abs(e2.y - e1.y) -> 3
                    e1.x < width / 2f -> 1
                    else -> 2
                }
                startPos = player.currentPosition
                volF = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                val cur = act.window.attributes.screenBrightness
                if (cur >= 0f) brightness = cur
            }
            when (mode) {
                1 -> {
                    brightness = (brightness + dy / height).coerceIn(0.02f, 1f)
                    val lp = act.window.attributes
                    lp.screenBrightness = brightness
                    act.window.attributes = lp
                    showHud("☀  ${(brightness * 100).roundToInt()}%", 800)
                }
                2 -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    volF = (volF + dy / height * max * 1.3f).coerceIn(0f, max.toFloat())
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volF.roundToInt(), 0)
                    showHud("🔊  ${(volF / max * 100).roundToInt()}%", 800)
                }
                3 -> {
                    val d = dur()
                    val delta = ((e2.x - e1.x) / width * 90_000f).toLong()
                    seekTarget = (startPos + delta).coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
                    val sign = if (delta >= 0) "+" else "−"
                    showHud("${fmt(seekTarget)}  ($sign${abs(delta) / 1000} sn)", 5000)
                }
            }
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(-1, -1, Gravity.CENTER))
        player.setVideoSurfaceView(surface)
        buildControls()

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoW = videoSize.width
                videoH = videoSize.height
                pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                applyAspect()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
                if (isPlaying) scheduleHide()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    btnPlay.text = "▶"
                    setControls(true)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(act, "Bu video oynatılamadı (biçim desteklenmiyor olabilir)", Toast.LENGTH_LONG).show()
                handler.post { onClose() }
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true

        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) applyAspect()
        }
        handler.post(ticker)
        scheduleHide()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dur(): Long = player.duration.let { if (it > 0) it else 0L }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    private fun btn(text: String, size: Float = 22f, onClick: () -> Unit) = TextView(act).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick(); scheduleHide() }
    }

    private fun buildControls() {
        // üst çubuk
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setBackgroundColor(0x66000000)
        topBar.setPadding(dp(8), dp(20), dp(8), dp(4))
        topBar.addView(btn("✕") { onClose() })
        topBar.addView(android.view.View(act), LinearLayout.LayoutParams(0, 1, 1f))
        speedBtn = btn("1x", 18f) { cycleSpeed() }
        topBar.addView(speedBtn)
        topBar.addView(btn("⛶") { cycleAspect() })
        topBar.addView(btn("⟳") { toggleRotate() })
        addView(topBar, LayoutParams(-1, -2, Gravity.TOP))

        // alt çubuk
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setBackgroundColor(0x66000000)
        bottomBar.setPadding(dp(12), dp(4), dp(12), dp(12))

        val row1 = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(tvPos, tvDur).forEach { it.setTextColor(Color.WHITE); it.textSize = 13f; it.text = "00:00" }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = fmt(p * 1000L)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                player.seekTo(sb.progress * 1000L)
                dragging = false
                scheduleHide()
            }
        })
        row1.addView(tvPos)
        row1.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(tvDur)
        bottomBar.addView(row1)

        val row2 = LinearLayout(act).apply { gravity = Gravity.CENTER }
        row2.addView(btn("⏪ 10", 18f) { seekBy(-10_000) })
        btnPlay = btn("⏸", 30f) { togglePlay() }
        row2.addView(btnPlay)
        row2.addView(btn("10 ⏩", 18f) { seekBy(10_000) })
        bottomBar.addView(row2)
        addView(bottomBar, LayoutParams(-1, -2, Gravity.BOTTOM))

        // bilgi kutusu
        hud.setTextColor(Color.WHITE)
        hud.textSize = 20f
        hud.setBackgroundColor(0xAA000000.toInt())
        hud.setPadding(dp(20), dp(12), dp(20), dp(12))
        hud.visibility = GONE
        addView(hud, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun setControls(show: Boolean) {
        val v = if (show) VISIBLE else GONE
        topBar.visibility = v
        bottomBar.visibility = v
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 3500)
    }

    private fun showHud(text: String, ms: Long) {
        hud.text = text
        hud.visibility = VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    private fun seekBy(ms: Long) {
        val d = dur()
        player.seekTo((player.currentPosition + ms).coerceIn(0L, if (d > 0) d else Long.MAX_VALUE))
        showHud(if (ms < 0) "⏪  10 sn" else "10 sn  ⏩", 600)
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        val s = speeds[speedIdx]
        player.setPlaybackSpeed(s)
        speedBtn.text = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"
        showHud("Hız: ${speedBtn.text}", 700)
    }

    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % 3
        applyAspect()
        showHud(arrayOf("Sığdır", "Doldur", "Yakınlaştır")[aspectMode], 700)
    }

    private fun toggleRotate() {
        landscape = !landscape
        act.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun applyAspect() {
        val cw = width
        val ch = height
        if (cw == 0 || ch == 0 || videoW == 0 || videoH == 0) return
        val vr = videoW * pixelRatio / videoH
        val cr = cw.toFloat() / ch
        var w = cw
        var h = ch
        when (aspectMode) {
            0 -> if (cr > vr) w = (ch * vr).toInt() else h = (cw / vr).toInt()
            2 -> if (cr > vr) h = (cw / vr).toInt() else w = (ch * vr).toInt()
        }
        val lp = surface.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gd.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode == 3) {
                player.seekTo(seekTarget)
                handler.removeCallbacks(hideHud)
                handler.postDelayed(hideHud, 600)
            }
            mode = 0
        }
        return true
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        player.release()
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val lp = act.window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lp
        WindowCompat.getInsetsController(act.window, act.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
