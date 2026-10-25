// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
class PlaybackActivity : ComponentActivity() {
    private lateinit var api: BbcApi
    private lateinit var store: LocalStore
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var status: TextView
    private lateinit var extras: NativePlayerExtras
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
    private var failureDetail = ""
    private var currentSkip: Skip? = null
    private var lastSave = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) { return }
            val pos = player.currentPosition
            val active = source?.skips?.firstOrNull { it.contains(pos) }
            currentSkip = active
            extras.update(active, next)
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
        player = ExoPlayer.Builder(this, renderers).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); setHandleAudioBecomingNoisy(true) }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (handlePlaybackKey(event)) true else super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply { player = this@PlaybackActivity.player; useController = true; controllerShowTimeoutMs = 1500; controllerAutoShow = true; setShowSubtitleButton(true); keepScreenOn = true }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        extras = NativePlayerExtras(this, player, view, episode, intent.getStringExtra("title").orEmpty(), ::playNext)
        root.addView(extras, FrameLayout.LayoutParams(-1, -1))
        status = text("Finding the best available stream…", 20f).apply { setBackgroundColor(0xCC101016.toInt()); maxWidth = dp(650) }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        // Keep the standard Media3 rewind button while retaining temporary captions on rewind.
        (view.findViewById<View>(androidx.media3.ui.R.id.exo_rew_with_amount)
            ?: view.findViewById<View>(androidx.media3.ui.R.id.exo_rew))?.setOnClickListener { rewind() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (extras.visible && player.isPlaying) extras.hide() else finish() }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { status.visibility = View.GONE; extras.ready(); badge(); extras.scheduleHide() }
                if (state == Player.STATE_ENDED) { episode?.let { store.update(it, next, player.duration, player.duration, true) }; extras.show(); extras.update(currentSkip, next) }
                diagnostic("state-$state")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { extras.update(); if (isPlaying) extras.scheduleHide() else if (player.playbackState == Player.STATE_READY) extras.show() }
            override fun onVideoSizeChanged(videoSize: VideoSize) { badge(); diagnostic("video") }
            override fun onTracksChanged(tracks: Tracks) { badge(); diagnostic("tracks") }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { if (cueGroup.cues.isNotEmpty()) captionSamples++; diagnostic("captions") }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (!settingTemporaryCaptions && temporaryUntil >= 0) { originalTextParameters = null; temporaryUntil = -1 }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerFailure = error.errorCodeName
                failureDetail = (error.cause as? androidx.media3.exoplayer.ExoTimeoutException)?.let { "timeout operation ${it.timeoutOperation}" } ?: error.cause?.javaClass?.simpleName.orEmpty()
                if (source?.uhd == true && !fallback && episode != null) { fallback = true; resume = player.currentPosition; resolve(false) }
                else { status.text = "Playback unavailable: ${error.errorCodeName}\n${error.cause?.message.orEmpty()}\nPress Back to return."; status.visibility = View.VISIBLE; extras.show() }
                diagnostic("error-${error.errorCodeName}")
            }
        })
        resolve(true); main.post(tick)
        if (next == null && episode != null) {
            val current = episode!!
            io.execute {
                val following = runCatching { api.episodes(Programme(current.showId, current.title)).let { items -> items.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }?.let { items.getOrNull(it + 1) } } }.getOrNull()
                main.post { if (alive) next = following }
            }
        }
    }
    private fun resolve(allowUhd: Boolean) {
        status.text = if (fallback) "UHD could not play. Trying HD…" else "Finding the best available stream…"; status.visibility = View.VISIBLE
        io.execute {
            val result = runCatching {
                if (BuildConfig.DEBUG && intent.hasExtra("testStream")) {
                    val url = intent.getStringExtra("testStream")!!; require(url.startsWith("https://")); Stream(url, caption = intent.getStringExtra("testCaption").orEmpty())
                } else episode?.let { api.stream(it, allowUhd && supportsUhd()) } ?: api.live(intent.getStringExtra("channel") ?: error("Missing episode"))
            }
            main.post { if (alive) result.fold(::prepare) { error -> status.text = error.message ?: "Stream lookup failed"; extras.show(); diagnostic("lookup-failed") } }
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
        val builder = MediaItem.Builder().setUri(stream.url).setMediaId(episode?.id ?: "live").setMediaMetadata(MediaMetadata.Builder().setTitle(episode?.title ?: intent.getStringExtra("title")).setSubtitle(episode?.subtitle).setDescription(episode?.summary).build())
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
        extras.play.requestFocus(); extras.show()
    }
    private fun supportsUhd(): Boolean = runCatching {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true
        hdr && MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec -> !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", true) } && codec.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 } }
    }.getOrDefault(false)
    private fun badge() { extras.update(currentSkip, next) }
    private fun playNext() {
        val upcoming = next ?: return
        save()
        startActivity(android.content.Intent(this, PlaybackActivity::class.java).putExtra("episode", upcoming.json().toString()).putExtra("restart", true))
        finish()
    }
    private fun rewind() {
        if (!player.isCurrentMediaItemSeekable) { extras.show(); return }
        val previous = player.currentPosition
        if (store.autoCaptions() && player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSupported } && (temporaryUntil >= 0 || player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))) {
            if (temporaryUntil < 0) originalTextParameters = player.trackSelectionParameters
            temporaryUntil = maxOf(temporaryUntil, previous)
            settingTemporaryCaptions = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("en").setSelectUndeterminedTextLanguage(true).build()
            settingTemporaryCaptions = false
        }
        if (player.isCurrentMediaItemSeekable) player.seekTo(maxOf(0, previous - 10_000))
        extras.show(); if (temporaryUntil >= 0) extras.replayNotice()
    }
    private fun restoreCaptions() { settingTemporaryCaptions = true; originalTextParameters?.let { player.trackSelectionParameters = it }; originalTextParameters = null; temporaryUntil = -1; settingTemporaryCaptions = false; extras.dismissTransientNotice() }
    private fun save() { episode?.let { store.update(it, next, player.currentPosition, player.duration) } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player.currentPosition); super.onSaveInstanceState(outState) }
    override fun onPause() { if (::player.isInitialized) { save(); player.pause() }; super.onPause() }
    override fun onDestroy() { alive = false; main.removeCallbacksAndMessages(null); io.shutdownNow(); extras.dispose(); player.release(); super.onDestroy() }
    private fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { rewind(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (player.isCurrentMediaItemSeekable) player.seekTo(player.currentPosition + 10_000); extras.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.playWhenReady) player.pause() else player.play(); extras.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); extras.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); extras.show(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!extras.visible && !extras.skip.hasFocus()) { rewind(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!extras.visible && !extras.skip.hasFocus()) { if (player.isCurrentMediaItemSeekable) player.seekTo(player.currentPosition + 10_000); extras.show(); return true }
            }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (!extras.visible && !extras.skip.hasFocus()) { extras.show(); return true }
                extras.show(false)
            }
        }
        return false
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
        return JSONObject().put("positionMs", player.currentPosition).put("height", player.videoFormat?.height ?: 0).put("playing", player.isPlaying).put("subtitleTrackCount", player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }).put("quality", extras.quality.text).put("controlsVisible", extras.visible).put("playLabel", extras.play.contentDescription).put("captionSamples", captionSamples).put("captionsEnabled", !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)).put("failure", playerFailure).put("failureDetail", failureDetail)
    }
    internal fun debugRewindCheck() {
        check(BuildConfig.DEBUG); restoreCaptions()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        rewind()
    }
    internal fun debugControls(): NativePlayerExtras { check(BuildConfig.DEBUG); return extras }
    internal fun debugSeek(ms: Long) { check(BuildConfig.DEBUG); player.seekTo(ms); player.play() }
}
