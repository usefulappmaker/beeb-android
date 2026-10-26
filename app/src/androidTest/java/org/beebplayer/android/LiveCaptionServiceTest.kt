package org.beebplayer.android

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real BBC fragment/clock/native-parser integration; does not require a hardware video decoder. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LiveCaptionServiceTest {
 @Test fun bbcFragmentsDecodeAsNativeTimedEnglishCues() {
  val api=BbcApi(InstrumentationRegistry.getInstrumentation().targetContext)
  val stream=api.live("bbc_one_london");val resources=stream.liveCaptions ?: error("No BBC caption rendition returned")
  val master=resources.resource(resources.masterUrl).toString(Charsets.UTF_8)
  val playlist=Regex("URI=\"(https://beeb-captions.invalid/[^\"]+)\"").find(master)!!.groupValues[1]
  val paths=resources.resource(playlist).toString(Charsets.UTF_8).lineSequence().filter {it.isNotEmpty() && !it.startsWith('#')}.toList().dropLast(2).takeLast(8).reversed()
  var cues=0
  for(path in paths) {
   val data=resources.resource(playlist.substringBeforeLast('/')+"/"+path)
   val text=data.toString(Charsets.UTF_8)
   val pts=Regex("MPEGTS:([0-9]+)").find(text)?.groupValues?.get(1)?.toLong() ?: continue
   assertTrue(pts in 0 until (1L shl 33))
   val parsed=mutableListOf<CuesWithTiming>()
   WebvttParser().parse(data,0,data.size,SubtitleParser.OutputOptions.allCues()) {parsed.add(it)}
   parsed.forEach {assertTrue(it.startTimeUs>=0 && it.endTimeUs<=3_850_000);cues+=it.cues.size}
   if(cues>0)break
  }
  assertTrue("Real BBC English text must decode using the native Media3 parser",cues>0)
 }
}
