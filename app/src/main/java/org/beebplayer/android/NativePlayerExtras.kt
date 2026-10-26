// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Supplementary metadata only. Transport, timeline, settings and subtitles are unmodified Media3 UI. */
@OptIn(UnstableApi::class)
class NativePlayerExtras(context: Context, private val player: ExoPlayer, private val playerView: PlayerView,
                         private val episode: Programme?, title: String, private val nextAction: () -> Unit) : FrameLayout(context) {
    private val metadata = LinearLayout(context)
    private var dialog: Dialog? = null
    private var activeSkip: Skip? = null
    private var nextEpisode: Programme? = null
    private var firstFrame = false
    val quality = button("", "Received stream quality") { qualityDialog() }
    val skip = button("Skip intro", "Skip intro") { activeSkip?.let { player.seekTo(it.endMs); show() } }
    private val nextButton = button("Play next", "Play next episode") { nextAction() }
    val play: View get() = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause)
    val visible: Boolean get() = playerView.isControllerFullyVisible

    init {
        metadata.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(180), dp(16)); background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), Color.TRANSPARENT)) }
        metadata.addView(label(episode?.title ?: title, 24f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        episode?.subtitle?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 17f)) }
        val details = listOfNotNull(episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }).joinToString("  ·  ")
        if (details.isNotEmpty()) metadata.addView(label(details, 14f))
        episode?.summary?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 15f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        metadata.contentDescription = "Programme details. " + listOfNotNull(episode?.title, episode?.subtitle, details, episode?.summary).joinToString(". ")
        metadata.isFocusable = true; metadata.isClickable = true
        val selectable = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
        metadata.foreground = context.getDrawable(selectable.resourceId)
        metadata.setOnClickListener { detailsDialog() }
        addView(metadata, LayoutParams(-1, -2, Gravity.TOP))
        addView(quality, LayoutParams(-2, dp(42), Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(22); rightMargin = dp(24) })
        skip.visibility = GONE; nextButton.visibility = GONE
        addView(skip, LayoutParams(-2, dp(44), Gravity.RIGHT or Gravity.BOTTOM).apply { bottomMargin = dp(90); rightMargin = dp(24) })
        addView(nextButton, LayoutParams(-2, dp(44), Gravity.LEFT or Gravity.BOTTOM).apply { bottomMargin = dp(90); leftMargin = dp(24) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            metadata.visibility = visibility; quality.visibility = if (quality.text.isNotEmpty()) visibility else GONE
            playerView.subtitleView?.setBottomPaddingFraction(if (visibility == VISIBLE) .22f else .08f)
        })
        update()
    }
    fun show(focus: Boolean = true) { playerView.showController(); if (focus && !play.hasFocus() && !metadata.hasFocus() && !quality.hasFocus() && !skip.hasFocus() && !nextButton.hasFocus()) play.requestFocus() }
    fun hide() = playerView.hideController()
    fun scheduleHide() { if (player.isPlaying && dialog == null) playerView.controllerShowTimeoutMs = 1500 }
    fun ready() { if (!firstFrame) { firstFrame = true; show() } }
    fun update(marker: Skip? = activeSkip, upcoming: Programme? = nextEpisode) {
        activeSkip = marker; nextEpisode = upcoming
        skip.text = activeSkip?.title.orEmpty(); skip.contentDescription = activeSkip?.title
        skip.visibility = if (activeSkip == null) GONE else VISIBLE
        val nearEnd = upcoming != null && player.duration > 0 && player.duration - player.currentPosition <= 30_000
        nextButton.visibility = if (nearEnd) VISIBLE else GONE
        if (!nearEnd && nextButton.hasFocus() || activeSkip == null && skip.hasFocus()) show()
        val f = player.videoFormat
        quality.text = when { f == null || f.height <= 0 -> ""; f.height >= 2160 && f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "4K HDR · HLG"; f.height >= 2160 && f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "4K HDR"; f.height >= 2160 -> "4K"; f.height >= 1080 -> "Full HD"; f.height >= 720 -> "HD"; else -> "SD" }
        quality.visibility = if (visible && quality.text.isNotEmpty()) VISIBLE else GONE
    }
    fun replayNotice() { Toast.makeText(context, "Rewound 10 seconds · captions temporarily on", Toast.LENGTH_SHORT).show() }
    fun dismissTransientNotice() = Unit
    private fun present(d: Dialog) { dialog?.dismiss(); dialog = d; playerView.controllerShowTimeoutMs = 0; show(false); d.setOnDismissListener { dialog = null; playerView.controllerShowTimeoutMs = 1500; show() }; d.show() }
    private fun detailsDialog() {
        val info = listOfNotNull(episode?.subtitle?.takeIf { it.isNotBlank() }, episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }, episode?.summary?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        present(AlertDialog.Builder(context).setTitle(episode?.title ?: "Programme details").setMessage(info.ifBlank { "Programme information is unavailable." }).setPositiveButton("Close", null).create())
    }
    private fun qualityDialog() {
        val f = player.videoFormat ?: return
        val range = when (f.colorInfo?.colorTransfer) { C.COLOR_TRANSFER_HLG -> "HLG"; C.COLOR_TRANSFER_ST2084 -> "HDR10"; else -> "SDR / unspecified" }
        present(AlertDialog.Builder(context).setTitle("Received stream").setMessage("${f.width} × ${f.height}\n${f.codecs ?: f.sampleMimeType.orEmpty()}\nHDR: $range\n\n${UhdSupport.inspect(context).description}\n\nCheck the TV's HDR status for its output mode.").setPositiveButton("Close", null).create())
    }
    internal fun debugDialogShowing(): Boolean { check(BuildConfig.DEBUG); return dialog?.isShowing == true }
    internal fun debugDismissDialog() { check(BuildConfig.DEBUG); dialog?.dismiss() }
    fun dispose() { dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(2)) }
    private fun button(title: String, description: String, action: () -> Unit) = Button(context).apply { text = title; textSize = 14f; isAllCaps = false; contentDescription = description; setOnClickListener { action() } }
    private fun broadcastDate(raw: String): String {
        if (raw.isBlank()) return ""
        val date = runCatching { Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrElse { runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() } ?: return raw
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}
