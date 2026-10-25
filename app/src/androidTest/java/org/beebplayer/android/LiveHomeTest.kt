package org.beebplayer.android

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveHomeTest {
    @Test fun cardsPublishWithoutDuplicatesAndLinkDirectlyToLivePlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val cards = listOf(Channel("bbc_one_london", "BBC One"), Channel("bbc_two_england", "BBC Two"))
        val row = LiveHomeChannels.publish(context, cards)
        try {
            assertEquals(row, LiveHomeChannels.publish(context, cards))
            var count = 0
            context.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(row), arrayOf(
                TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI,
                TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO), null, null, null)!!.use {
                while (it.moveToNext()) {
                    val intent = Intent.parseUri(it.getString(0), Intent.URI_INTENT_SCHEME)
                    assertTrue(LiveHomeChannels.channelId(intent) in cards.map { card -> card.id })
                    assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, it.getInt(1))
                    count++
                }
            }
            assertEquals(2, count)
            assertNull(LiveHomeChannels.channelId(Intent(Intent.ACTION_VIEW, Uri.parse("beeb://live/bbc_one/extra"))))
            assertNull(LiveHomeChannels.channelId(Intent(Intent.ACTION_VIEW, Uri.parse("https://live/bbc_one"))))
        } finally { PreviewChannelHelper(context).deletePreviewChannel(row) }
    }
}
