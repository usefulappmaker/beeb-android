package org.beebplayer.android

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRefinementsTest {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=ins.targetContext
 private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
 private fun waitFor(timeout: Long=30000, check:()->Boolean) {val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){var yes=false;ins.runOnMainSync {yes=check()};if(yes)return;Thread.sleep(100)};error("Timed out waiting for UI")}
 private fun screenshot(name:String) {Thread.sleep(800);ins.uiAutomation.takeScreenshot()!!.let { b -> val f=context.getExternalFilesDir(null)!!.resolve("refinements/$name.png");f.parentFile!!.mkdirs();f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
 @Test fun searchRemainsResponsiveAndLongDescriptionCannotCoverPlay() {
  val a=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  try {
   ins.runOnMainSync {MainActivity::class.java.getDeclaredMethod("search").apply {isAccessible=true}.invoke(a)}
   val field=views(a.window.decorView).filterIsInstance<EditText>().first()
   // Search while the full catalogue refresh is active. Each key must return promptly.
   for(q in listOf("a","al","ala","alan","alan p","alan partridge")) {
    var elapsed=0L
    ins.runOnMainSync {val start=SystemClock.uptimeMillis();field.requestFocus();field.setText(q);field.setSelection(q.length);elapsed=SystemClock.uptimeMillis()-start}
    assertTrue("Key handling must not search/sort the catalogue on the UI thread ($elapsed ms)",elapsed<500)
    Thread.sleep(300)
   }
   waitFor { views(a.window.decorView).filterIsInstance<TextView>().any {it !is EditText && it.text.toString().endsWith("for “alan partridge”")} }
   ins.runOnMainSync {assertSame(field,a.currentFocus)}
   screenshot("01-search-results")
   // Intentionally oversized synopsis exercises clipping/scrolling and remote focus.
   val p=Programme("p01bmdvt","Alan Partridge: Alpha Papa",summary=("To save his own job, Alan gets his radio colleague sacked. When the man returns to the relaunched station and a siege ensues, Alan is given the high-profile role of go-between.\n\n").repeat(10),duration="1h 25m",date="2013-01-01")
   ins.runOnMainSync {MainActivity::class.java.getDeclaredMethod("episode",Programme::class.java,Programme::class.java).apply {isAccessible=true}.invoke(a,p,null)}
   Thread.sleep(1000)
   val dialog=MainActivity::class.java.getDeclaredField("episodePanel").apply {isAccessible=true}.get(a) as Dialog
   val all=views(dialog.window!!.decorView);val scroll=all.filterIsInstance<ScrollView>().single();val play=all.filterIsInstance<ImageButton>().first {it.contentDescription=="Play"}
   ins.runOnMainSync {
    val s=IntArray(2);val b=IntArray(2);scroll.getLocationOnScreen(s);play.getLocationOnScreen(b)
    assertTrue("Description viewport ends above Play",s[1]+scroll.height<b[1])
    assertTrue("Description must have bounded scrollable content",scroll.getChildAt(0).height>scroll.height)
    scroll.requestFocus();scroll.fullScroll(View.FOCUS_DOWN)
   }
   screenshot("02-long-description")
   ins.runOnMainSync {dialog.dismiss();MainActivity::class.java.getDeclaredField("currentTab").apply {isAccessible=true}.set(a,"Settings");MainActivity::class.java.getDeclaredMethod("settings").apply {isAccessible=true}.invoke(a)}
   ins.runOnMainSync {
    val labels=views(a.window.decorView).filterIsInstance<TextView>().map {it.text.toString()}
    assertFalse(labels.any {it.contains("Open BBC episode") || it.contains("Licences and attribution") || it.contains("Restore encrypted UHD backup")})
    val action=views(a.window.decorView).filterIsInstance<TextView>().first {it.text=="Enable BBC UHD"}
    assertFalse(action is Button);assertNull(action.background)
    @Suppress("UNCHECKED_CAST") val tabs=MainActivity::class.java.getDeclaredField("tabViews").apply {isAccessible=true}.get(a) as Map<String,View>
    tabs["Settings"]!!.requestFocus()
   }
   screenshot("03-settings")
  } finally {ins.runOnMainSync {a.finish()}}
 }
 @Test fun captionAppearanceFollowsSystemChanges() {
  val a=ins.startActivitySync(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("testNoPlayback",true)) as PlaybackActivity
  val view=PlaybackActivity::class.java.getDeclaredField("view").apply {isAccessible=true}.get(a) as androidx.media3.ui.PlayerView
  val subtitle=view.subtitleView!!
  fun property(name:String)=subtitle.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(subtitle)
  fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {it.readText().trim()}}
  val keys=listOf("accessibility_captioning_font_scale","accessibility_captioning_preset","accessibility_captioning_foreground_color")
  val old=keys.associateWith {shell("settings get secure $it")}
  try {
   ins.runOnMainSync {assertEquals(false,property("applyEmbeddedStyles"));assertEquals(false,property("applyEmbeddedFontSizes"))}
   shell("settings put secure accessibility_captioning_font_scale 1.5")
   waitFor {kotlin.math.abs((property("defaultTextSize") as Float)-.060f)<.001f}
   shell("settings put secure accessibility_captioning_preset -1")
   shell("settings put secure accessibility_captioning_foreground_color -16711936")
   waitFor {(property("style") as androidx.media3.ui.CaptionStyleCompat).foregroundColor==android.graphics.Color.GREEN}
  } finally {
   for((key,value) in old)shell(if(value=="null")"settings delete secure $key" else "settings put secure $key $value")
   ins.runOnMainSync {a.finish()}
  }
 }
}
