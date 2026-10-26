package org.beebplayer.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Network/device integration check, separate from deterministic UI and system-preference checks. */
@RunWith(AndroidJUnit4::class)
class LiveCaptionDeviceTest {
 @Test fun actualBbcLiveVideoAndNativeEnglishCaptions() {
  val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
  val a=ins.startActivitySync(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("channel","bbc_one_london").putExtra("title","BBC One")) as PlaybackActivity
  fun waitFor(check:()->Boolean) {val end=SystemClock.uptimeMillis()+90000;while(SystemClock.uptimeMillis()<end){var yes=false;ins.runOnMainSync {yes=check()};if(yes)return;Thread.sleep(250)};error("Live video/captions did not become ready")}
  try {
   waitFor {a.debugSnapshot().optInt("subtitleTrackCount")>0}
   val player=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(a) as ExoPlayer
   ins.runOnMainSync {assertTrue(a.debugSnapshot().optBoolean("liveCaptionBridge"));player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("en").build()}
   waitFor {a.debugSnapshot().optInt("captionSamples")>0 && a.debugSnapshot().optLong("positionMs")>1000 && a.debugSnapshot().optInt("height")>0}
   ins.runOnMainSync {assertEquals("",a.debugSnapshot().optString("failure"))}
   val bitmap=ins.uiAutomation.takeScreenshot()!!;val f=context.getExternalFilesDir(null)!!.resolve("refinements/04-live-native-subtitles.png");f.parentFile!!.mkdirs();f.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  } finally {ins.runOnMainSync {a.finish()}}
 }
}
