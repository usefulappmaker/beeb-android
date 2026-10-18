// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlaybackActivity : Activity() {
    private lateinit var api: BbcApi
    private lateinit var store: LocalStore
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var status: TextView
    private lateinit var quality: TextView
    private lateinit var skip: Button
    private lateinit var controls: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var episode: Programme? = null
    private var next: Programme? = null
    private var source: Stream? = null
    private var alive = true
    private var fallback = false
    private var resume = 0L
    private var temporaryUntil = -1L
    private var originalTextParameters: androidx.media3.common.TrackSelectionParameters? = null
    private var settingTemporaryCaptions = false
    private var captionSamples = 0
    private var playerFailure = ""
    private var currentSkip: Skip? = null
    private var lastSave = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) { return }
            val pos = player.currentPosition
            val active = source?.skips?.firstOrNull { it.contains(pos) }
            if (active != currentSkip) { currentSkip = active; skip.text = active?.title.orEmpty(); skip.visibility = if (active == null) View.GONE else View.VISIBLE }
            if (temporaryUntil >= 0 && pos >= temporaryUntil) restoreCaptions()
            if (System.currentTimeMillis() - lastSave >= 5000) { save(); lastSave = System.currentTimeMillis() }
            main.postDelayed(this, 300)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        api = BbcApi(this); store = LocalStore(this)
        episode = intent.getStringExtra("episode")?.let { Programme.restore(JSONObject(it)) }
        next = intent.getStringExtra("next")?.let { Programme.restore(JSONObject(it)) }
        resume = savedInstanceState?.getLong("position") ?: if (intent.getBooleanExtra("restart", false)) 0 else episode?.let(store::resume) ?: 0
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true).forceDisableMediaCodecAsynchronousQueueing()
        player = ExoPlayer.Builder(this, renderers).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); setHandleAudioBecomingNoisy(true) }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply { player = this@PlaybackActivity.player; useController = true; controllerShowTimeoutMs = 1500; controllerAutoShow = false; setShowSubtitleButton(true); keepScreenOn = true }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        status = text("Finding the best available stream…", 21f)
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        quality = text("", 18f).apply { setBackgroundColor(0xAA101016.toInt()); setPadding(dp(14), dp(7), dp(14), dp(7)) }
        root.addView(quality, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(26), dp(34), 0) })
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(180), dp(28))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), Color.TRANSPARENT))
            isFocusable = false
        }
        heading.addView(text(episode?.title ?: intent.getStringExtra("title").orEmpty(), 28f).apply { setPadding(0, 0, 0, dp(4)); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1 })
        episode?.subtitle?.takeIf { it.isNotEmpty() }?.let { subtitle -> heading.addView(text(subtitle, 18f).apply { setPadding(0, 0, 0, 0); setTextColor(0xFFDDDDDD.toInt()); maxLines = 2 }) }
        root.addView(heading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        quality.bringToFront()
        controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xAA101016.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        skip = button("Skip intro") { currentSkip?.let { player.seekTo(it.endMs) } }.apply { visibility = View.GONE }
        controls.addView(skip); controls.addView(button("↶ 10s") { rewind() }); controls.addView(button("Close") { finish() })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { bottomMargin = dp(110); rightMargin = dp(30) })
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible -> controls.visibility = visible; quality.visibility = visible; heading.visibility = visible })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { status.visibility = View.GONE; badge() }
                if (state == Player.STATE_ENDED) { episode?.let { store.update(it, next, player.duration, player.duration, true) }; status.text = if (next != null) "Episode finished · Press Back for Up next" else "Episode finished"; status.visibility = View.VISIBLE }
                diagnostic("state-$state")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { badge(); diagnostic("video") }
            override fun onTracksChanged(tracks: Tracks) { badge(); diagnostic("tracks") }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { if (cueGroup.cues.isNotEmpty()) captionSamples++; diagnostic("captions") }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (!settingTemporaryCaptions && temporaryUntil >= 0) { originalTextParameters = null; temporaryUntil = -1 }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerFailure = error.errorCodeName
                if (source?.uhd == true && !fallback && episode != null) { fallback = true; resume = player.currentPosition; resolve(false) }
                else { status.text = "Playback unavailable: ${error.errorCodeName}\n${error.cause?.message.orEmpty()}\nPress Back to return."; status.visibility = View.VISIBLE; view.showController() }
                diagnostic("error-${error.errorCodeName}")
            }
        })
        resolve(true); main.post(tick)
    }
    private fun resolve(allowUhd: Boolean) {
        status.text = if (fallback) "UHD could not play. Trying HD…" else "Finding the best available stream…"; status.visibility = View.VISIBLE
        io.execute {
            val result = runCatching {
                if (BuildConfig.DEBUG && intent.hasExtra("testStream")) {
                    val url = intent.getStringExtra("testStream")!!; require(url.startsWith("https://")); Stream(url, caption = intent.getStringExtra("testCaption").orEmpty())
                } else episode?.let { api.stream(it, allowUhd && supportsUhd()) } ?: api.live(intent.getStringExtra("channel") ?: error("Missing episode"))
            }
            main.post { if (alive) result.fold(::prepare) { error -> status.text = error.message ?: "Stream lookup failed"; view.showController(); diagnostic("lookup-failed") } }
        }
    }
    private fun prepare(stream: Stream) {
        playerFailure = ""
        source = stream
        val base = android.net.Uri.parse(stream.url)
        val data = ResolvingDataSource.Factory(OkHttpDataSource.Factory(api.http)) { spec ->
            // Preserve same-host manifest authorisation on relative segment/playlist requests.
            if (spec.uri.host == base.host && !base.encodedQuery.isNullOrEmpty() && spec.uri.encodedQuery.isNullOrEmpty()) spec.withUri(spec.uri.buildUpon().encodedQuery(base.encodedQuery).build()) else spec
        }
        val builder = MediaItem.Builder().setUri(stream.url).setMediaId(episode?.id ?: "live").setMediaMetadata(MediaMetadata.Builder().setTitle(episode?.title ?: intent.getStringExtra("title")).setSubtitle(episode?.subtitle).build())
        if (stream.uhd) builder.setMimeType(MimeTypes.APPLICATION_MPD)
        if (stream.caption.isNotEmpty()) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(stream.caption)).setMimeType(MimeTypes.APPLICATION_TTML).setLanguage("en").setLabel("English").build()))
        val media = builder.build()
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, data))
        player.setMediaSource(factory.createMediaSource(media), resume)
        player.prepare(); player.playWhenReady = true
        val manager = getSystemService(CAPTIONING_SERVICE) as android.view.accessibility.CaptioningManager
        view.subtitleView?.setUserDefaultStyle(); view.subtitleView?.setUserDefaultTextSize()
        if (manager.isEnabled) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setPreferredTextLanguage(manager.locale?.language ?: "en").setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        view.requestFocus(); view.showController()
    }
    private fun supportsUhd(): Boolean = runCatching {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true
        hdr && MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec -> !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", true) } && codec.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 } }
    }.getOrDefault(false)
    private fun badge() {
        val f = player.videoFormat
        val h = f?.height ?: 0; val range = f?.colorInfo?.colorTransfer
        quality.text = when { h >= 2160 && range in listOf(C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_ST2084) -> "4K HDR"; h >= 2160 -> "4K"; h >= 1080 -> "Full HD"; h >= 720 -> "HD"; h > 0 -> "SD"; else -> "" }
    }
    private fun rewind() {
        val previous = player.currentPosition
        if (store.autoCaptions() && player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported } && (temporaryUntil >= 0 || player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))) {
            if (temporaryUntil < 0) originalTextParameters = player.trackSelectionParameters
            temporaryUntil = maxOf(temporaryUntil, previous)
            settingTemporaryCaptions = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("en").setSelectUndeterminedTextLanguage(true).build()
            settingTemporaryCaptions = false
        }
        player.seekTo(maxOf(0, previous - 10_000)); view.showController()
    }
    private fun restoreCaptions() { settingTemporaryCaptions = true; originalTextParameters?.let { player.trackSelectionParameters = it }; originalTextParameters = null; temporaryUntil = -1; settingTemporaryCaptions = false }
    private fun save() { episode?.let { store.update(it, next, player.currentPosition, player.duration) } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player.currentPosition); super.onSaveInstanceState(outState) }
    override fun onPause() { if (::player.isInitialized) { save(); player.pause() }; super.onPause() }
    override fun onDestroy() { alive = false; main.removeCallbacksAndMessages(null); io.shutdownNow(); player.release(); super.onDestroy() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { rewind(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!view.isControllerFullyVisible) { rewind(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) view.showController()
        return super.dispatchKeyEvent(event)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(16), dp(10), dp(16), dp(10)) }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; setTextColor(Color.WHITE); setOnClickListener { action() } }
    private fun diagnostic(event: String) {
        if (!BuildConfig.DEBUG) return
        val j = debugSnapshot().put("event", event)
        filesDir.resolve("playback-diagnostic.json").writeText(j.toString())
    }
    internal fun debugSnapshot(): JSONObject {
        check(BuildConfig.DEBUG)
        return JSONObject().put("positionMs", player.currentPosition).put("height", player.videoFormat?.height ?: 0).put("playing", player.isPlaying).put("subtitleTrackCount", player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }).put("quality", quality.text).put("captionSamples", captionSamples).put("captionsEnabled", !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)).put("failure", playerFailure)
    }
    internal fun debugRewindCheck() {
        check(BuildConfig.DEBUG); restoreCaptions()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        rewind()
    }
    internal fun debugSeek(ms: Long) { check(BuildConfig.DEBUG); player.seekTo(ms); player.play() }
}
