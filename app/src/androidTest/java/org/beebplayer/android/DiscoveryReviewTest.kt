package org.beebplayer.android

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscoveryReviewTest {
 @Test fun fullCatalogueSearchUhdAndNativeKeyboard() {
  val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
  val index=CatalogueIndex(context,BbcApi(context))
  assertTrue(index.allUhd().size>=90)
  assertTrue(index.search("vIgIl").any { it.title.equals("Vigil",true) })
  assertTrue(index.search("EastEnders").isNotEmpty())
  assertTrue(index.search("   ").isEmpty())
  assertEquals("epoque",CatalogueIndex.normal("Époque"))
  val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
  fun all()=views(activity.window.decorView)
  fun invoke(name:String) { ins.runOnMainSync { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity) } }
  fun waitFor(test:()->Boolean) { val end=System.currentTimeMillis()+45000;while(System.currentTimeMillis()<end) { var ok=false;ins.runOnMainSync {ok=test()};if(ok)return;Thread.sleep(150) };error("Discovery screen not ready") }
  fun capture(name:String) { Thread.sleep(1800);val b=ins.uiAutomation.takeScreenshot()!!;val f=File(context.getExternalFilesDir(null),"discovery/$name.png");f.parentFile!!.mkdirs();f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
  ins.runOnMainSync { MainActivity::class.java.getDeclaredField("currentTab").apply {isAccessible=true}.set(activity,"Browse") }
  invoke("browse")
  waitFor { all().filterIsInstance<TextView>().any {it.text.toString()=="Drama & Soaps" || it.text.toString()=="Drama & soaps" || it.text.toString()=="Comedy"} }
  capture("01-browse")
  invoke("uhdCatalogue")
  waitFor { all().filterIsInstance<TextView>().any {it.text.toString().contains("programmes")} }
  capture("02-uhd")
  invoke("search")
  ins.runOnMainSync { all().filterIsInstance<EditText>().first().apply {requestFocus();(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)} }
  capture("03-native-keyboard")
  ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(1500)
  ins.runOnMainSync { all().filterIsInstance<EditText>().first().setText("Vigil") }
  waitFor {all().filterIsInstance<TextView>().any {it !is EditText && it.text.toString()=="Vigil"}}
  capture("04-search-results")
  val voice=Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
  println("Native speech handler available: ${context.packageManager.resolveActivity(voice,0)!=null}")
 }
}
