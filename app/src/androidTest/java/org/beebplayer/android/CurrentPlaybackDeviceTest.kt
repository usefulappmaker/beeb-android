package org.beebplayer.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentPlaybackDeviceTest {
    @Test fun vigilResolvesThroughCurrentEpisodeApiAndReturnsFreshHdPlaylist() {
        val api=BbcApi(InstrumentationRegistry.getInstrumentation().targetContext)
        val p=api.episode("p09pm8b0")
        assertEquals("p09pm8b0",p.id);assertTrue(p.version.isNotBlank())
        val stream=api.stream(p,false)
        assertFalse(stream.uhd);assertTrue(stream.version.isNotBlank())
        val playlist=api.get(stream.url)
        assertTrue(playlist.startsWith("#EXTM3U"));assertTrue(playlist.contains("#EXTINF:"))
    }
}
