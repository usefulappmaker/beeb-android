// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var api: BbcApi
    private lateinit var store: LocalStore
    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var currentTab = "Home"
    private val backStack = mutableListOf<() -> Unit>()
    private val pink = Color.rgb(250, 35, 137)
    private val ink = Color.rgb(16, 16, 22)
    private var homeFeed: Home? = null
    private var featureIndex = 0
    private var banner: View? = null
    private var returnScreen: () -> Unit = { home() }
    private val rotate = object : Runnable { override fun run() { if (currentTab == "Home" && backStack.isEmpty() && !body.hasFocus()) { featureIndex++; renderHome() }; main.postDelayed(this, 8000) } }
    private val liveRefresh = object : Runnable { override fun run() { if (currentTab == "Live" && backStack.isEmpty()) live(); main.postDelayed(this, 60_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BbcApi(this); store = LocalStore(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { navigateBack() } })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ink); setPadding(dp(38), dp(18), dp(38), dp(12)) }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(ImageView(this).apply { setImageResource(R.drawable.beeb_mark) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        for (tab in listOf("Home", "Browse", "Live", "Saved", "Settings")) nav.addView(button(tab) { backStack.clear(); currentTab = tab; when (tab) { "Home" -> home(); "Browse" -> browse(); "Live" -> live(); "Saved" -> saved(); else -> settings() } }, LinearLayout.LayoutParams(-2, dp(50)).apply { marginStart = dp(12) })
        root.addView(nav)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(24), dp(10), dp(30)); clipChildren = false }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        home()
    }
    override fun onResume() { super.onResume(); main.postDelayed(rotate, 8000); main.postDelayed(liveRefresh, 60_000); if (::store.isInitialized && currentTab == "Home" && backStack.isEmpty() && homeFeed != null) renderHome() }
    override fun onPause() { main.removeCallbacks(rotate); main.removeCallbacks(liveRefresh); super.onPause() }
    override fun onDestroy() { generation++; io.shutdownNow(); super.onDestroy() }
    private fun navigateBack() { if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex).invoke() else if (currentTab != "Home") { currentTab = "Home"; home() } else finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 22f, muted: Boolean = false) = TextView(this).apply { this.text = text; textSize = size; setTextColor(if (muted) 0xFFB6B2C0.toInt() else Color.WHITE); setPadding(0, dp(6), 0, dp(8)) }
    private fun border(focused: Boolean) = GradientDrawable().apply { setColor(if (focused) 0xFF382039.toInt() else 0xFF1C1B24.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(if (focused) 3 else 1), if (focused) pink else 0xFF47434E.toInt()) }
    private fun focus(view: View) { view.isFocusable = true; view.isFocusableInTouchMode = true; view.background = border(false); view.setOnFocusChangeListener { v, on -> v.background = border(on) } }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(20), dp(8), dp(20), dp(8)); focus(this); setOnClickListener { action() } }
    private fun reset(title: String): Int {
        generation++; body.removeAllViews(); banner = null
        if (backStack.isNotEmpty()) body.addView(button("‹ Back") { navigateBack() })
        if (title != "Home") body.addView(label(title, 30f).apply { setTypeface(typeface, Typeface.BOLD) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }; body.addView(content)
        return generation
    }
    private fun <T> load(title: String, task: () -> T, done: (T) -> Unit) {
        val id = reset(title); val spinner = label("Loading…", 18f, true); content.addView(spinner)
        io.execute { val result = runCatching(task); main.post { if (generation == id && !isDestroyed) { content.removeView(spinner); result.fold(done) { error -> content.addView(label(error.message ?: "Unable to load", 19f, true)); content.addView(button("Retry") { load(title, task, done) }) } } } }
    }
    private fun image(url: String, height: Int) = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF24212A.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(height)); contentDescription = null; if (url.isNotEmpty()) Images.load(api, url, this) }
    private fun card(p: Programme, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); focus(this); contentDescription = p.title + " " + p.subtitle
        addView(image(p.image, 125)); addView(label(p.subtitle.ifEmpty { p.title }, 18f).apply { maxLines = 2; minLines = 2 })
        if (p.uhd) addView(label(if (p.hdr) "4K HDR" else "4K", 14f, true))
        setOnClickListener { action() }
    }
    private fun row(title: String, programmes: List<Programme>, onSelect: (Programme) -> Unit) {
        if (programmes.isEmpty()) return
        content.addView(label(title, 24f))
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(5), dp(5), dp(5), dp(10)) }
        programmes.forEach { p -> line.addView(card(p) { onSelect(p) }, LinearLayout.LayoutParams(dp(250), -2).apply { marginEnd = dp(16) }) }
        content.addView(HorizontalScrollView(this).apply { clipToPadding = false; addView(line) })
    }
    private fun grid(items: List<Programme>, onSelect: (Programme) -> Unit) {
        val available = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 116
        val columns = maxOf(2, (available / 265).toInt())
        items.chunked(columns).forEach { group ->
            val line = LinearLayout(this)
            group.forEach { p -> line.addView(card(p) { onSelect(p) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(10), dp(12)) }) }
            repeat(columns - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }; content.addView(line)
        }
    }
    private fun home() { returnScreen = { home() }; if (homeFeed != null) renderHome() else load("Home", { api.home() }) { homeFeed = it; renderHome() } }
    private fun renderHome() {
        val feed = homeFeed ?: return; reset("Home")
        if (feed.features.isNotEmpty()) {
            val panel = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
            // Prefer the artwork ratio, capped to keep the complete card visible on a TV.
            val height = minOf(((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 96) * 9 / 16).toInt(), (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 128).toInt())
            val art = image("", height)
            panel.addView(art, FrameLayout.LayoutParams(-1, dp(height)))
            val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)); background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00101016, 0xF0101016.toInt())) }
            val counter = label("", 14f).apply { setTextColor(pink) }
            val headline = label("", 32f)
            val summary = label("", 18f).apply { maxLines = 2 }
            overlay.addView(counter); overlay.addView(headline); overlay.addView(summary); overlay.addView(label("Explore programme  ›", 16f))
            panel.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            fun updateFeature() {
                featureIndex = Math.floorMod(featureIndex, feed.features.size)
                val f = feed.features[featureIndex]
                panel.contentDescription = "${f.headline}. Featured ${featureIndex + 1} of ${feed.features.size}. Use left and right to browse."
                counter.text = "FEATURED  ·  ${featureIndex + 1} / ${feed.features.size}"
                headline.text = f.headline; summary.text = f.summary
                Images.load(api, f.image, art)
            }
            panel.setOnFocusChangeListener { _, focused ->
                panel.foreground = if (focused) GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), pink) } else null
            }
            panel.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        featureIndex += if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        updateFeature()
                    }
                    true
                } else false
            }
            panel.setOnClickListener { openShow(feed.features[Math.floorMod(featureIndex, feed.features.size)].show) }
            updateFeature(); content.addView(panel); banner = panel
        }
        val history = store.history()
        if (history.isNotEmpty()) {
            content.addView(label("Continue Watching", 24f))
            val line = LinearLayout(this)
            history.forEach { record ->
                val target = record.target ?: record.episode
                val view = card(target.copy(subtitle = (if (record.completed) "Up next · " else "Resume · ") + target.subtitle)) {
                    if (record.completed && record.next == null) openShow(Programme(record.episode.showId, record.episode.title)) else episode(target, record.next)
                }
                view.setOnLongClickListener { AlertDialog.Builder(this).setMessage("Remove ${record.episode.title} from Continue Watching?").setPositiveButton("Remove") { _, _ -> store.remove(record.episode.showId); renderHome() }.setNegativeButton("Cancel", null).show(); true }
                line.addView(view, LinearLayout.LayoutParams(dp(250), -2).apply { marginEnd = dp(16) })
            }
            content.addView(HorizontalScrollView(this).apply { addView(line) })
        }
        feed.rows.take(8).forEach { (title, shows) -> row(title, shows, ::openShow) }
        content.addView(button("Refresh Home") { homeFeed = null; home() })
    }
    private fun browse() {
        returnScreen = { browse() }
        reset("Browse")
        content.addView(label("A–Z", 22f))
        val letters = listOf("0-9") + ('a'..'z').map(Char::toString)
        val line = LinearLayout(this)
        letters.forEach { letter -> line.addView(button(letter.uppercase()) { page(letter, false, 1) }) }
        content.addView(HorizontalScrollView(this).apply { addView(line) })
        val id = generation
        io.execute { val genres = runCatching { api.genres() }; main.post { if (generation == id) genres.fold({ categories -> content.addView(label("Categories", 22f)); categories.chunked(4).forEach { group -> val r = LinearLayout(this); group.forEach { (key, title) -> r.addView(button(title) { page(key, true, 1) }, LinearLayout.LayoutParams(0, dp(56), 1f)) }; content.addView(r) } }, { content.addView(label("Categories unavailable; A–Z is still available.", 18f, true)) }) } }
    }
    private fun page(key: String, category: Boolean, number: Int) {
        if (backStack.isEmpty()) backStack.add { browse() }
        returnScreen = { page(key, category, number) }
        load("Browse · ${key.replaceFirstChar(Char::uppercase)}", { api.page(key, category, number) }) { result ->
            val bar = LinearLayout(this); if (number > 1) bar.addView(button("‹ Previous") { page(key, category, number - 1) }); bar.addView(label("  Page $number / ${result.totalPages}  ", 18f)); if (number < result.totalPages) bar.addView(button("Next ›") { page(key, category, number + 1) }); content.addView(bar)
            grid(result.shows, ::openShow)
        }
    }
    private fun openShow(show: Programme) {
        val previousScreen = returnScreen
        backStack.add(previousScreen)
        load(show.title, { api.episodes(show) }) { episodes ->
            content.addView(label(show.summary, 19f, true))
            lateinit var bookmark: Button
            bookmark = button(if (store.isSaved(show)) "Remove saved programme" else "Save programme") { store.toggle(show); bookmark.text = if (store.isSaved(show)) "Remove saved programme" else "Save programme" }
            content.addView(bookmark)
            if (episodes.isEmpty()) content.addView(label("No episodes available.", 20f))
            val episodeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val groups = episodes.groupBy { it.series }
            fun showSeries(items: List<Programme>, page: Int = 1) {
                episodeHost.removeAllViews()
                val previous = content; content = episodeHost
                if (items.size > 30) { val bar = LinearLayout(this); if (page > 1) bar.addView(button("‹ Previous episodes") { showSeries(items, page - 1) }); if (page * 30 < items.size) bar.addView(button("More episodes ›") { showSeries(items, page + 1) }); content.addView(bar) }
                grid(items.drop((page - 1) * 30).take(30)) { p ->
                    val index = episodes.indexOfFirst { it.id == p.id }; val candidate = episodes.getOrNull(index + 1)
                    val next = candidate?.takeIf { p.position > 0 && it.position > 0 || p.date.isNotEmpty() && it.date.isNotEmpty() }
                    episode(p, next)
                }; content = previous
            }
            if (groups.size > 1) { val line = LinearLayout(this); groups.forEach { (title, items) -> line.addView(button(title) { showSeries(items) }) }; content.addView(HorizontalScrollView(this).apply { addView(line) }) }
            content.addView(episodeHost); groups.values.firstOrNull()?.let { showSeries(it) }
        }
    }
    private fun episode(p: Programme, next: Programme?) {
        val actions = mutableListOf("▶ Play at best available quality")
        if (store.resume(p) > 0) actions += "Start over"
        val dialog = AlertDialog.Builder(this).setTitle(p.title + "\n" + p.subtitle).setMessage(p.summary + "\n" + p.duration + if (p.uhd) " · 4K available" else "").setPositiveButton("Play at best available quality") { _, _ -> play(p, next, false) }.apply {
            if (actions.size > 1) setNeutralButton("Start over") { _, _ -> play(p, next, true) }
        }.setNegativeButton("Close", null).create()
        dialog.setOnShowListener { val icon = getDrawable(R.drawable.beeb_mark)!!; icon.setBounds(0, 0, dp(28), dp(28)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setCompoundDrawables(icon, null, null, null) }
        dialog.show()
    }
    private fun play(p: Programme, next: Programme?, restart: Boolean) { startActivity(Intent(this, PlaybackActivity::class.java).putExtra("episode", p.json().toString()).putExtra("next", next?.json()?.toString()).putExtra("restart", restart)) }
    private fun saved() { returnScreen = { saved() }; reset("Saved programmes"); val items = store.saved(); if (items.isEmpty()) content.addView(label("Save a programme from its episode page.", 20f, true)); grid(items, ::openShow) }
    private fun live() { returnScreen = { live() }; load("Live", { api.channels() }) { channels -> grid(channels.map { Programme(it.id, it.title, it.name, image = it.image) }) { p -> startActivity(Intent(this, PlaybackActivity::class.java).putExtra("channel", p.id).putExtra("title", p.title + " · " + p.subtitle)) } } }
    private fun settings() {
        returnScreen = { settings() }
        reset("Settings")
        content.addView(label("Beeb for Android TV · ${BuildConfig.VERSION_NAME}", 22f))
        content.addView(label("4K HLG · TwinVine", 26f)); content.addView(label(if (api.vault.configured()) "UHD identity connected" else "Optional one-time setup required for UHD", 19f, true))
        content.addView(label("Run the setup helper on your computer, then enter its complete one-time URL. After connecting, playback runs directly on this TV.", 18f, true))
        val input = EditText(this).apply { hint = "One-time setup URL"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
        content.addView(input)
        val status = label("", 18f, true)
        content.addView(button("Connect TwinVine") {
            val link = input.text.toString(); input.setText(""); status.text = "Connecting…"; val id = generation
            io.execute { val result = runCatching { api.vault.connect(link) }; main.post { if (generation == id) result.fold({ settings(); Toast.makeText(this, "Connected. You can close the computer helper.", Toast.LENGTH_LONG).show() }, { status.text = it.message ?: "Setup failed" }) } }
        }); content.addView(status)
        if (api.vault.configured()) content.addView(button("Forget UHD identity") { AlertDialog.Builder(this).setMessage("Remove UHD setup from this app?").setPositiveButton("Forget") { _, _ -> api.vault.forget(); settings() }.setNegativeButton("Cancel", null).show() })
        content.addView(button("Captions on 10-second rewind: ${if (store.autoCaptions()) "On" else "Off"}") { store.autoCaptions(!store.autoCaptions()); settings() })
        content.addView(button("Android caption appearance settings") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS)) }.onFailure { Toast.makeText(this, "Caption settings are unavailable on this device", Toast.LENGTH_LONG).show() } })
        content.addView(button("Licences and attribution") { val notice = listOf("NOTICE.md", "LICENSE.txt", "APACHE-2.0.txt").joinToString("\n\n") { assets.open(it).bufferedReader().use { reader -> reader.readText() } }; AlertDialog.Builder(this).setTitle("Licences and attribution").setMessage(notice).setPositiveButton("Close", null).show() })
        content.addView(button("Open BBC episode URL / ID") { val field = EditText(this); AlertDialog.Builder(this).setTitle("BBC episode URL or ID").setView(field).setPositiveButton("Open") { _, _ -> load("Episode", { api.episode(Parsing.pid(field.text.toString())) }) { episode(it, null) } }.setNegativeButton("Cancel", null).show() })
        content.addView(label("Independent player · GPL-3.0-or-later\nNo BBC account sync or offline downloads. UHD needs compatible content, decoder and HLG display. Keep setup links and private keys out of screenshots and bug reports.", 17f, true))
    }
}

private object Images {
    private val executor = Executors.newFixedThreadPool(4)
    private val memory = object : LruCache<String, Bitmap>(32 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024 }
    fun load(api: BbcApi, url: String, target: ImageView) {
        target.tag = url; memory.get(url)?.let { target.setImageBitmap(it); return }
        executor.execute {
            val bitmap = runCatching { api.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> if (!r.isSuccessful) null else r.body?.byteStream()?.use(BitmapFactory::decodeStream) } }.getOrNull()
            if (bitmap != null) { memory.put(url, bitmap); target.post { if (target.tag == url) target.setImageBitmap(bitmap) } }
        }
    }
}
