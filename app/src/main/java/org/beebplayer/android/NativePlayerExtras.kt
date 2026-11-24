// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Three strict layers, vertically ordered:
 *
 *   3. actions at the very top: Restart episode / Play live, or Skip intro / Play next
 *   2. Media3's transport bar: rewind, play/pause, forward, subtitles, settings
 *   1. the scrubber under it
 *
 * Up always climbs and Down always descends; Left/Right stay inside the layer. The links are
 * installed by PlaybackActivity.wireFocusLayers(). Without them the D-pad used raw spatial
 * search across two overlapping view trees and could land anywhere.
 */
@OptIn(UnstableApi::class)
class NativePlayerExtras(context: Context, private val player: ExoPlayer, private val playerView: PlayerView,
                         private val episode: Programme?, title: String, private val nextAction: () -> Unit,
                         private val live: Boolean = false, private val restartAction: () -> Unit = {}) : FrameLayout(context) {
    private val pink = Color.rgb(250, 35, 137)
    companion object { const val HUD_TIMEOUT_MS = 3_000 }
    private val ink = Color.rgb(16, 16, 22)
    private val topBlock = LinearLayout(context)
    private val actions = LinearLayout(context)
    private val metadata = LinearLayout(context)
    val skip = actionButton("Skip intro", "Skip intro") { activeSkip?.let { player.seekTo(it.endMs); show() } }
    private val restart = actionButton("Restart episode", "Play the current programme from its start") { restartAction(); show() }
    private val playLive = actionButton("Play live", "Jump back to the live point") { player.seekToDefaultPosition(); show() }
    private val nextButton = actionButton("Play next", "Play next episode") { nextAction() }
    private val ordered = listOf(restart, playLive, skip, nextButton)
    private var dialog: Dialog? = null
    private var activeSkip: Skip? = null
    private var nextEpisode: Programme? = null
    private var firstFrame = false
    private var controllerDownId = View.NO_ID
    val play: View get() = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause)
    val visible: Boolean get() = playerView.isControllerFullyVisible
    /** First visible action; Up from the transport bar lands here. */
    val anchorId: Int get() = ordered.firstOrNull { it.visibility == VISIBLE }?.id ?: View.NO_ID

    init {
        actions.orientation = LinearLayout.HORIZONTAL
        ordered.forEach { action ->
            action.id = View.generateViewId()
            action.visibility = GONE
            actions.addView(action, LinearLayout.LayoutParams(-2, dp(46)).apply { marginEnd = dp(12) })
        }
        actions.visibility = GONE

        metadata.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(2), dp(180), dp(14)) }
        metadata.addView(label(episode?.title ?: title, 24f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        episode?.subtitle?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 17f)) }
        val details = listOfNotNull(episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }).joinToString("  ·  ")
        if (details.isNotEmpty()) metadata.addView(label(details, 14f))
        episode?.summary?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 15f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        metadata.contentDescription = "Programme details. " + listOfNotNull(episode?.title, episode?.subtitle, details, episode?.summary).joinToString(". ")

        topBlock.orientation = LinearLayout.VERTICAL
        topBlock.setPadding(dp(32), dp(20), dp(32), dp(12))
        topBlock.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE0101016.toInt(), Color.TRANSPARENT))
        topBlock.addView(actions, LinearLayout.LayoutParams(-2, -2))
        topBlock.addView(metadata, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        topBlock.visibility = GONE
        addView(topBlock, LayoutParams(-1, -2, Gravity.TOP))

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBlock.visibility = visibility
            actions.visibility = if (ordered.any { it.visibility == VISIBLE }) VISIBLE else GONE
            // Clear the bottom safe area, and more of it while the controls are on screen.
            val h = resources.displayMetrics.heightPixels.toFloat()
            val hidden = maxOf(0.13f, (resources.displayMetrics.heightPixels * 0.10f + dp(24)) / h)
            playerView.subtitleView?.setBottomPaddingFraction(if (visibility == VISIBLE) hidden + 0.18f else hidden)
        })
        update()
    }

    /** PlaybackActivity measures the panel; the top row shares the same margins. */
    fun setSafeInsets(side: Int, top: Int) {
        if (topBlock.paddingLeft == side && topBlock.paddingTop == top) return
        topBlock.setPadding(side, top, side, dp(12))
    }

    /** Down from the action row returns to play/pause. */
    fun bindController(playPause: View?) { controllerDownId = playPause?.id ?: View.NO_ID; rewire() }

    private fun rewire() {
        val shown = ordered.filter { it.visibility == VISIBLE }
        shown.forEachIndexed { index, action ->
            action.nextFocusLeftId = if (index > 0) shown[index - 1].id else View.NO_ID
            action.nextFocusRightId = if (index < shown.size - 1) shown[index + 1].id else View.NO_ID
            action.nextFocusUpId = View.NO_ID
            action.nextFocusDownId = controllerDownId
        }
    }

    fun show(focus: Boolean = true) {
        playerView.showController()
        if (focus && !play.hasFocus() && ordered.none { it.hasFocus() }) play.requestFocus()
    }
    fun hide() = playerView.hideController()
    fun scheduleHide() { if (player.isPlaying && dialog == null) playerView.controllerShowTimeoutMs = HUD_TIMEOUT_MS }
    fun ready() { if (!firstFrame) { firstFrame = true; show() } }
    fun update(marker: Skip? = activeSkip, upcoming: Programme? = nextEpisode) {
        activeSkip = marker; nextEpisode = upcoming
        skip.text = activeSkip?.title.orEmpty(); skip.contentDescription = activeSkip?.title
        skip.visibility = if (activeSkip == null) GONE else VISIBLE
        val nearEnd = upcoming != null && player.duration > 0 && player.duration - player.currentPosition <= 30_000
        nextButton.visibility = if (nearEnd && !live) VISIBLE else GONE
        restart.visibility = if (live) VISIBLE else GONE
        playLive.visibility = if (live) VISIBLE else GONE
        if (!nearEnd && nextButton.hasFocus() || activeSkip == null && skip.hasFocus()) show()
        actions.visibility = if (ordered.any { it.visibility == VISIBLE }) VISIBLE else GONE
        rewire()
    }
    fun replayNotice() { Toast.makeText(context, "Rewound 30 seconds · captions temporarily on", Toast.LENGTH_SHORT).show() }
    fun dismissTransientNotice() = Unit
    private fun present(d: Dialog) { dialog?.dismiss(); dialog = d; playerView.controllerShowTimeoutMs = 0; show(false); d.setOnDismissListener { dialog = null; playerView.controllerShowTimeoutMs = HUD_TIMEOUT_MS; show() }; d.show() }
    private fun detailsDialog() {
        val info = listOfNotNull(episode?.subtitle?.takeIf { it.isNotBlank() }, episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }, episode?.summary?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        present(AlertDialog.Builder(context).setTitle(episode?.title ?: "Programme details").setMessage(info.ifBlank { "Programme information is unavailable." }).setPositiveButton("Close", null).create())
    }
    internal fun debugOpenDetails() { detailsDialog() }
    internal fun debugDialogShowing(): Boolean { check(BuildConfig.DEBUG); return dialog?.isShowing == true }
    internal fun debugDismissDialog() { dialog?.dismiss() }
    fun dispose() { dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(2)) }

    /** BBC pink marks the focused action; unfocused actions stay dark so the highlight is obvious. */
    private fun actionButton(title: String, description: String, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 15f; isAllCaps = false; contentDescription = description
        setPadding(dp(22), dp(8), dp(22), dp(8))
        isFocusable = true
        fun paint(on: Boolean) {
            // Focus is shown by colour alone: same size, same padding, same stroke width.
            background = GradientDrawable().apply {
                cornerRadius = dp(23).toFloat()
                setColor(if (on) pink else 0xCC1C1B24.toInt())
                setStroke(dp(3), if (on) Color.WHITE else 0xFF47434E.toInt())
            }
            setTextColor(if (on) ink else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, on -> paint(on) }
        setOnClickListener { action() }
    }
    private fun broadcastDate(raw: String): String {
        if (raw.isBlank()) return ""
        val date = runCatching { Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrElse { runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() } ?: return raw
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}
