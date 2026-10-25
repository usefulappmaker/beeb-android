// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import android.content.ContentValues
import android.media.tv.TvContract.PreviewPrograms
import androidx.tvprovider.media.tv.TvContractCompat
import java.util.concurrent.Executors

/** Standard Android TV home row. The launcher decides whether/where this row is shown. */
object LiveHomeChannels {
    private const val ROW = "beeb-live-channels"
    private const val JOB = 304013
    fun link(context: Context, id: String): Intent {
        require(id.matches(Regex("[a-z0-9_]{1,80}")))
        return Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("beeb").authority("live").appendPath(id).build(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    fun channelId(intent: Intent): String? {
        val uri = intent.data ?: return null
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "beeb" || uri.host != "live" || uri.pathSegments.size != 1) return null
        return uri.pathSegments.single().takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) }
    }
    @Synchronized fun publish(context: Context, channels: List<Channel>): Long {
        require(channels.isNotEmpty())
        val helper = PreviewChannelHelper(context)
        val preferences = context.getSharedPreferences("live-home", Context.MODE_PRIVATE)
        var rowId = preferences.getLong("row", -1)
        val existingRows = helper.allChannels
        if (existingRows.none { it.id == rowId }) {
            rowId = existingRows.firstOrNull { it.internalProviderId == ROW }?.id ?: -1
        }
        if (rowId < 0) rowId = helper.publishChannel(PreviewChannel.Builder().setDisplayName("Beeb · Live channels")
            .setDescription("BBC live channels. Select a card to start watching.")
            .setInternalProviderId(ROW).setAppLinkIntentUri(Uri.parse(link(context, "all").toUri(Intent.URI_INTENT_SCHEME)))
            .setLogo(BitmapFactory.decodeResource(context.resources, R.drawable.beeb_mark)).build())
        check(rowId >= 0) { "The TV launcher did not accept the live-channel row" }
        val existing = mutableMapOf<String, Long>()
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(rowId)
        context.contentResolver.query(uri, arrayOf(PreviewPrograms._ID, PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) existing[cursor.getString(1)] = cursor.getLong(0)
        }
        channels.forEachIndexed { index, channel ->
            val values = ContentValues().apply {
                put(PreviewPrograms.COLUMN_CHANNEL_ID, rowId)
                put(PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
                put(PreviewPrograms.COLUMN_TYPE, PreviewPrograms.TYPE_CHANNEL)
                put(PreviewPrograms.COLUMN_LIVE, 1)
                put(PreviewPrograms.COLUMN_TITLE, channel.name)
                put(PreviewPrograms.COLUMN_SHORT_DESCRIPTION, channel.title)
                put(PreviewPrograms.COLUMN_WEIGHT, channels.size - index)
                put(PreviewPrograms.COLUMN_INTENT_URI, link(context, channel.id).toUri(Intent.URI_INTENT_SCHEME))
                put(PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, PreviewPrograms.ASPECT_RATIO_16_9)
                put(PreviewPrograms.COLUMN_POSTER_ART_URI, channel.image.ifEmpty { "android.resource://${context.packageName}/${R.drawable.beeb_mark}" })
            }
            val old = existing.remove(channel.id)
            if (old == null) checkNotNull(context.contentResolver.insert(PreviewPrograms.CONTENT_URI, values))
            else context.contentResolver.update(TvContractCompat.buildPreviewProgramUri(old), values, null, null)
        }
        existing.values.forEach(helper::deletePreviewProgram)
        preferences.edit().putLong("row", rowId).putLong("updated", System.currentTimeMillis()).apply()
        schedule(context)
        return rowId
    }
    fun refreshIfNeeded(context: Context) {
        val preferences = context.getSharedPreferences("live-home", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - preferences.getLong("updated", 0) < 15 * 60_000) return
        runCatching { publish(context, BbcApi(context).channels()) }
    }
    fun requestVisible(context: Context, row: Long) { TvContractCompat.requestChannelBrowsable(context, row) }
    private fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) != null) return
        val job = JobInfo.Builder(JOB, ComponentName(context, LiveChannelJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60_000L).build()
        scheduler.schedule(job)
    }
}

class LiveChannelJob : JobService() {
    private var executor: java.util.concurrent.ExecutorService? = null
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        executor = Executors.newSingleThreadExecutor().also { it.execute {
            val ok = runCatching { LiveHomeChannels.publish(this, BbcApi(this).channels()) }.isSuccess
            if (!stopped) jobFinished(params, !ok)
            it.shutdown()
        } }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; executor?.shutdownNow(); return true }
}
