// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import androidx.activity.result.contract.ActivityResultContracts
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
    private var directSetup: UhdSetupSession? = null
    private var setupProgress: AlertDialog? = null
    private var backupProgress: AlertDialog? = null
    private var pendingBackup: ByteArray? = null
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBackup; pendingBackup = null
        if (uri != null && bytes != null) io.execute {
            val result = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to open backup destination") }
            bytes.fill(0)
            main.post { if (!isDestroyed) Toast.makeText(this, if (result.isSuccess) "Encrypted UHD backup saved. Keep its password separately." else "Unable to save backup", Toast.LENGTH_LONG).show() }
        } else bytes?.fill(0)
    }
    private var searchQuery = ""
    private var searchInput: EditText? = null
    private var searchUpdate: Runnable? = null
    private val searchWorker = Executors.newSingleThreadExecutor()
    private var searchTask: java.util.concurrent.Future<*>? = null
    private var searchRevision = 0
    private var refreshingCatalogue = false
    private val catalogue by lazy { CatalogueIndex(this, api) }
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val query = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !query.isNullOrBlank()) { searchQuery = query; search() }
    }
    private lateinit var api: BbcApi
    private lateinit var store: LocalStore
    private lateinit var body: LinearLayout
    private lateinit var pageHeader: LinearLayout
    private val tabViews = mutableMapOf<String, LinearLayout>()
    private var platformBack: android.window.OnBackInvokedCallback? = null
    private var episodePanel: android.app.Dialog? = null
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
        for (tab in listOf("Home", "Browse", "Search", "Live", "Saved", "Settings")) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isFocusableInTouchMode = true; contentDescription = tab
                setPadding(dp(18), dp(4), dp(18), 0)
                addView(label(tab, 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(8)) }, LinearLayout.LayoutParams(-2, -2))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(24), dp(3)))
                setOnFocusChangeListener { _, _ -> updateTabs() }
                setOnClickListener {
                    backStack.clear(); currentTab = tab
                    when (tab) { "Home" -> home(); "Browse" -> browse(); "Search" -> search(); "Live" -> live(); "Saved" -> saved(); else -> settings() }
                }
            }
            tabViews[tab] = item
            nav.addView(item, LinearLayout.LayoutParams(-2, dp(50)).apply { marginStart = dp(6) })
        }
        updateTabs()
        root.addView(nav)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(30)); clipChildren = false }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24) }); setContentView(root)
        if (!handleLaunch(intent)) home()
        io.execute { LiveHomeChannels.refreshIfNeeded(this) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleLaunch(intent) }
    private fun handleLaunch(incoming: Intent): Boolean {
        if (incoming.action == Intent.ACTION_APPLICATION_PREFERENCES) { currentTab = "Settings"; settings(); return true }
        val channel = LiveHomeChannels.channelId(incoming) ?: return false
        backStack.clear(); currentTab = "Live"; live()
        if (channel != "all") startActivity(Intent(this, PlaybackActivity::class.java).putExtra("channel", channel).putExtra("title", "BBC Live"))
        return true
    }
    override fun onResume() { super.onResume();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            platformBack = android.window.OnBackInvokedCallback { navigateBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, platformBack!!)
        }
 main.postDelayed(rotate, 8000); main.postDelayed(liveRefresh, 60_000); if (::store.isInitialized && currentTab == "Home" && backStack.isEmpty() && homeFeed != null) renderHome() }
    override fun onPause() {
        if (android.os.Build.VERSION.SDK_INT >= 33) platformBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        platformBack = null
        main.removeCallbacks(rotate); main.removeCallbacks(liveRefresh); super.onPause() }
    override fun onDestroy() { directSetup?.cancel(); setupProgress?.dismiss(); episodePanel?.dismiss(); backupProgress?.dismiss(); generation++; searchRevision++; searchTask?.cancel(true); searchWorker.shutdownNow(); main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }
    private fun navigateBack() { if (episodePanel?.isShowing == true) episodePanel?.dismiss() else if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex).invoke() else if (currentTab != "Home") { currentTab = "Home"; home() } else finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 22f, muted: Boolean = false) = TextView(this).apply { this.text = text; textSize = size; setTextColor(if (muted) 0xFFB6B2C0.toInt() else Color.WHITE); setPadding(0, dp(6), 0, dp(8)) }
    private fun border(focused: Boolean) = GradientDrawable().apply { setColor(if (focused) 0xFF382039.toInt() else 0xFF1C1B24.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(if (focused) 3 else 1), if (focused) pink else 0xFF47434E.toInt()) }
    private fun focus(view: View) { view.isFocusable = true; view.isFocusableInTouchMode = true; view.background = border(false); view.setOnFocusChangeListener { v, on -> v.background = border(on) } }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(20), dp(8), dp(20), dp(8)); focus(this); setOnClickListener { action() } }
    private fun updateTabs() {
        tabViews.forEach { (name, item) ->
            val selected = name == currentTab
            val focused = item.hasFocus()
            (item.getChildAt(0) as TextView).apply {
                setTextColor(if (selected || focused) Color.WHITE else 0xFFAAA9B2.toInt())
                setTypeface(null, if (selected || focused) Typeface.BOLD else Typeface.NORMAL)
            }
            item.getChildAt(1).background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(if (focused) Color.WHITE else if (selected) pink else Color.TRANSPARENT)
            }
            item.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
        }
    }
    private fun reset(title: String): Int {
        generation++; searchRevision++; searchTask?.cancel(true); searchInput = null; searchUpdate?.let(main::removeCallbacks); body.removeAllViews(); banner = null
        updateTabs()
        pageHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        if (title != "Home") {
            pageHeader.addView(label(title, 30f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(pageHeader)
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }; body.addView(content)
        return generation
    }
    private fun <T> load(title: String, task: () -> T, done: (T) -> Unit) {
        val id = reset(title); val spinner = label("Loading…", 18f, true); content.addView(spinner)
        io.execute { val result = runCatching(task); main.post { if (generation == id && !isDestroyed) { content.removeView(spinner); result.fold(done) { error -> content.addView(label(error.message ?: "Unable to load", 19f, true)); content.addView(button("Retry") { load(title, task, done) }) } } } }
    }
    private fun image(url: String, height: Int) = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF24212A.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(height)); contentDescription = null; if (url.isNotEmpty()) Images.load(api, url, this) }
    private fun card(p: Programme, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
        isFocusable = true; isFocusableInTouchMode = true; contentDescription = p.title + " " + p.subtitle
        val art = image(p.image, 140).apply {
            background = GradientDrawable().apply { setColor(0xFF24212A.toInt()); cornerRadius = dp(8).toFloat() }
            clipToOutline = true
        }
        addView(art)
        val caption = label(p.subtitle.ifEmpty { p.title }, 17f).apply { maxLines = 2; minLines = 2; setPadding(dp(2), dp(10), dp(2), 0) }
        addView(caption)
        if (p.uhd) addView(label(if (p.hdr) "4K HDR" else "4K", 12f, true))
        setOnFocusChangeListener { _, on ->
            art.foreground = if (on) GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat(); setStroke(dp(3), Color.WHITE) } else null
            caption.setTextColor(if (on) Color.WHITE else 0xFFCDCCD4.toInt())
            animate().scaleX(if (on) 1.025f else 1f).scaleY(if (on) 1.025f else 1f).setDuration(120).start()
        }
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
    private fun discoveryText(text: String, action: () -> Unit) = TextView(this).apply {
        this.text=text; textSize=19f; gravity=Gravity.CENTER_VERTICAL
        isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(18),dp(12),dp(18),dp(12))
        setTextColor(0xFFCDCCD4.toInt())
        setOnFocusChangeListener { _, focused ->
            setTextColor(if(focused) Color.WHITE else 0xFFCDCCD4.toInt())
            setTypeface(null,if(focused) Typeface.BOLD else Typeface.NORMAL)
            background=if(focused) GradientDrawable().apply { setColor(0xFF302B36.toInt()); cornerRadius=dp(24).toFloat() } else null
        }
        setOnClickListener { action() }
    }
    private fun browse() {
        returnScreen = { browse() }; reset("Browse")
        content.addView(label("Discover",22f))
        content.addView(discoveryText("4K UHD   ›") { uhdCatalogue() },LinearLayout.LayoutParams(-1,dp(58)))
        content.addView(label("Categories",22f))
        val categoriesHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(categoriesHost)
        content.addView(label("A–Z",22f).apply { setPadding(0,dp(22),0,dp(10)) })
        val line=LinearLayout(this)
        (listOf("0-9")+('a'..'z').map(Char::toString)).forEach { letter ->
            line.addView(discoveryText(letter.uppercase()) { page(letter,false,1) },LinearLayout.LayoutParams(if(letter=="0-9")dp(74) else dp(52),dp(50)))
        }
        content.addView(HorizontalScrollView(this).apply { addView(line) })
        val id=generation
        io.execute { val result=runCatching { api.genres() }; main.post { if(generation==id && !isDestroyed) result.fold({ categories ->
            categories.chunked(3).forEach { group -> val row=LinearLayout(this); group.forEach { (key,title) ->
                row.addView(discoveryText(title) { page(key,true,1) },LinearLayout.LayoutParams(0,dp(54),1f).apply { marginEnd=dp(14) })
            }; repeat(3-group.size) { row.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }; categoriesHost.addView(row) }
        },{ categoriesHost.addView(label("Categories unavailable. A–Z and 4K UHD are still available.",16f,true)) }) } }
    }
    private fun uhdCatalogue() {
        if(backStack.isEmpty()) backStack.add { browse() }
        returnScreen={ uhdCatalogue() }; reset("4K UHD")
        val items=catalogue.allUhd()
        content.addView(label("${items.size} programmes",16f,true))
        var shown=0
        lateinit var more: TextView
        fun append() {
            if(::content.isInitialized) content.removeView(more)
            val next=items.drop(shown).take(30); shown+=next.size; grid(next,::openShow)
            more.text="Show more programmes  ›"; if(shown<items.size) content.addView(more)
        }
        more=discoveryText("Show more programmes  ›") { append() }; append()
        val status=label("",15f,true);content.addView(status)
        content.addView(discoveryText("Refresh catalogue") { refreshCatalogue(status,true) { if(currentTab=="Browse") uhdCatalogue() } })
        refreshCatalogue(status,false) { if(currentTab=="Browse") uhdCatalogue() }
    }
    private fun refreshCatalogue(status: TextView, force: Boolean, done: () -> Unit) {
        if(refreshingCatalogue || !force && !catalogue.needsRefresh()) return
        refreshingCatalogue=true;status.text="Updating programme catalogue…"
        io.execute { val result=runCatching { catalogue.refresh() }; main.post {
            refreshingCatalogue=false
            if(!isDestroyed && status.isAttachedToWindow) result.fold({ status.text="Catalogue updated";done() },{ status.text="Using the saved catalogue. Refresh is unavailable right now." })
        } }
    }
    private fun search() {
        currentTab="Search"; returnScreen={ search() }; reset("Search")
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(3),dp(10),dp(3)); background=GradientDrawable().apply { setColor(0xFF24212B.toInt());cornerRadius=dp(30).toFloat() } }
        val field=EditText(this).apply {
            hint="Search programmes";textSize=20f;setTextColor(Color.WHITE);setHintTextColor(0xFFAAA9B2.toInt())
            setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            filters=arrayOf(android.text.InputFilter.LengthFilter(200));background=null;setPadding(dp(16),dp(12),dp(16),dp(12));setText(searchQuery)
        }; searchInput=field
        bar.addView(field,LinearLayout.LayoutParams(0,dp(58),1f))
        val mic=ImageButton(this).apply {
            contentDescription="Search by voice";setImageResource(R.drawable.search_mic);imageTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(14),dp(14),dp(14),dp(14));isFocusable=true
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(0xFF393340.toInt()) }
            setOnFocusChangeListener { _,on -> background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(if(on)0xFF63546B.toInt() else 0xFF393340.toInt()) } }
            setOnClickListener {
                val intent=Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,"Search Beeb programmes")
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,java.util.Locale.getDefault().toLanguageTag())
                runCatching { voiceSearch.launch(intent) }.onFailure {
                    Toast.makeText(this@MainActivity,"Voice input is unavailable on this device. Use the keyboard instead.",Toast.LENGTH_LONG).show()
                    field.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        bar.addView(mic,LinearLayout.LayoutParams(dp(50),dp(50)))
        content.addView(bar)
        val status=label("",15f,true);content.addView(status)
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };content.addView(results)
        fun render(moveFocus: Boolean = false) {
            if(searchInput!==field) return
            val query=searchQuery; val revision=++searchRevision
            searchTask?.cancel(true)
            searchTask=searchWorker.submit {
                val matches=try { catalogue.search(query) } catch (_: java.util.concurrent.CancellationException) { return@submit }
                main.post {
                    if(isDestroyed || searchInput!==field || revision!=searchRevision) return@post
                    results.removeAllViews()
                    results.addView(label(if(query.isBlank()) "Find programmes by title or description" else "${matches.size} ${if(matches.size==1) "result" else "results"} for “$query”",18f,true))
                    var shown=0;lateinit var more: TextView
                    fun append() {
                        results.removeView(more); val next=matches.drop(shown).take(if(shown==0)9 else 30);shown+=next.size
                        val previous=content;content=results
                        try { grid(next,::openShow) } finally { content=previous }
                        if(shown<matches.size)results.addView(more)
                    }
                    more=discoveryText("More results  ›") { append() };append()
                    if(moveFocus) (0 until results.childCount).map {results.getChildAt(it)}.filterIsInstance<LinearLayout>().firstOrNull()?.getChildAt(0)?.requestFocus()
                }
            }
        }
        field.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { searchQuery=s.toString().take(200); searchRevision++; searchTask?.cancel(true); searchUpdate?.let(main::removeCallbacks);searchUpdate=Runnable { render() };main.postDelayed(searchUpdate!!,350) }
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        field.setOnEditorActionListener { _,action,event ->
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode==KeyEvent.KEYCODE_ENTER) {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken,0)
                searchUpdate?.let(main::removeCallbacks);render(true);true
            } else false
        }
        content.addView(discoveryText("Refresh catalogue") { refreshCatalogue(status,true) { render() } })
        render();refreshCatalogue(status,false) { render() }
    }
    private fun page(key: String, category: Boolean, number: Int) {
        if (backStack.isEmpty()) backStack.add { browse() }
        returnScreen = { page(key, category, number) }
        load(key.replaceFirstChar(Char::uppercase), { api.page(key, category, number) }) { result ->
            val bar = LinearLayout(this); if (number > 1) bar.addView(button("‹ Previous") { page(key, category, number - 1) }); bar.addView(label("  Page $number / ${result.totalPages}  ", 18f)); if (number < result.totalPages) bar.addView(button("Next ›") { page(key, category, number + 1) }); content.addView(bar)
            grid(result.shows, ::openShow)
        }
    }
    private fun openShow(show: Programme) {
        val previousScreen = returnScreen
        backStack.add(previousScreen)
        load(show.title, { api.episodes(show) }) { episodes ->
            io.execute { runCatching { catalogue.discover(show,episodes) } }
            content.addView(label(show.summary, 19f, true))
            val bookmark = ImageButton(this).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
                fun update() {
                    val saved = store.isSaved(show)
                    setImageResource(if (saved) R.drawable.bookmark_filled else R.drawable.bookmark_outline)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (saved) pink else Color.WHITE)
                    contentDescription = if (saved) "Remove saved programme" else "Save programme"
                    tooltipText = contentDescription
                    isSelected = saved
                }
                fun backgroundFor(on: Boolean) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) 0xFF494650.toInt() else 0xFF24212B.toInt()) }
                background = backgroundFor(false)
                setOnFocusChangeListener { _, on -> background = backgroundFor(on) }
                update()
                setOnClickListener { store.toggle(show); update(); announceForAccessibility(if (store.isSaved(show)) "Programme saved" else "Programme removed from Saved") }
            }
            pageHeader.addView(bookmark, LinearLayout.LayoutParams(dp(48), dp(48)))
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
            if (groups.size > 1) {
                val line = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(12)) }
                var selected = groups.keys.first()
                fun updateSeries() {
                    for (i in 0 until line.childCount) (line.getChildAt(i) as TextView).apply {
                        val active = tag == selected
                        setTextColor(if (active) ink else if (hasFocus()) Color.WHITE else 0xFFB6B2C0.toInt())
                        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (active) Color.WHITE else if (hasFocus()) 0xFF393640.toInt() else Color.TRANSPARENT) }
                    }
                }
                groups.forEach { (title, items) ->
                    val tab = TextView(this).apply {
                        tag = title; text = if (title == "Episodes") "Specials" else title; textSize = 17f; gravity = Gravity.CENTER; isFocusable = true
                        setPadding(dp(22), dp(10), dp(22), dp(10))
                        setOnFocusChangeListener { _, _ -> updateSeries() }
                        setOnClickListener { selected = title; updateSeries(); showSeries(items) }
                    }
                    line.addView(tab, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(10) })
                }
                updateSeries()
                content.addView(HorizontalScrollView(this).apply { addView(line) })
            }
            content.addView(episodeHost); groups.values.firstOrNull()?.let { showSeries(it) }
        }
    }
    private fun episode(p: Programme, next: Programme?) {
        episodePanel?.dismiss()
        val dialog = android.app.Dialog(this)
        episodePanel = dialog
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0xFF201F27.toInt()); cornerRadius = dp(24).toFloat() }
            clipToOutline = true
        }
        surface.addView(image(p.image, 390), LinearLayout.LayoutParams(0, -1, .40f))
        val info = RelativeLayout(this).apply { setPadding(dp(30), dp(24), dp(30), dp(24)); clipChildren = true; clipToPadding = true }
        val header = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId() }
        header.addView(label(p.title, 14f, true).apply { setTextColor(pink); letterSpacing = .08f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        header.addView(label(p.subtitle.ifEmpty { p.title }, 24f).apply { maxLines = 2; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(null, Typeface.BOLD) })
        val date = runCatching { java.time.LocalDate.parse(p.date.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)) }.getOrDefault(p.date.take(10))
        val meta = listOf(p.duration, date, if (p.uhd) { if (p.hdr) "4K HDR available" else "4K available" } else "").filter { it.isNotBlank() }.joinToString("   ·   ")
        header.addView(label(meta, 13f, true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        info.addView(header,RelativeLayout.LayoutParams(-1,-2).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        val description = label(p.summary, 16f).apply { setTextColor(0xFFD5D3DD.toInt()); setLineSpacing(dp(3).toFloat(), 1f) }
        val descriptionScroll = ScrollView(this).apply { id=View.generateViewId(); contentDescription="Programme description"; isFocusable = true; isFillViewport=false; clipChildren=true; clipToPadding=true; isVerticalScrollBarEnabled=true; addView(description) }
        val actions = LinearLayout(this).apply { id=View.generateViewId(); gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val playButton = ImageButton(this).apply {
            setImageResource(R.drawable.beeb_mark); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(22), dp(13), dp(22), dp(13)); contentDescription = if (store.resume(p) > 0) "Resume" else "Play"
            isFocusable = true; isFocusableInTouchMode = true
            fun appearance(on: Boolean) = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(if (on) Color.WHITE else 0xFFE3DFE7.toInt()) }
            background = appearance(false)
            setOnFocusChangeListener { _, on -> background = appearance(on) }
            setOnClickListener { dialog.dismiss(); play(p, next, false) }
        }
        actions.addView(playButton, LinearLayout.LayoutParams(dp(90), dp(54)))
        if (store.resume(p) > 0) actions.addView(button("Start over") { dialog.dismiss(); play(p, next, true) }, LinearLayout.LayoutParams(-2, dp(54)).apply { marginStart = dp(16) })
        info.addView(actions,RelativeLayout.LayoutParams(-1,dp(58)).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) })
        info.addView(descriptionScroll,RelativeLayout.LayoutParams(-1,-1).apply { addRule(RelativeLayout.BELOW,header.id); addRule(RelativeLayout.ABOVE,actions.id); topMargin=dp(6); bottomMargin=dp(16) })
        playButton.nextFocusUpId=descriptionScroll.id
        descriptionScroll.nextFocusDownId=playButton.id.takeIf {it!=View.NO_ID} ?: View.generateViewId().also {playButton.id=it}
        surface.addView(info, LinearLayout.LayoutParams(0, -1, .60f))
        dialog.setContentView(surface)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.82f)
        }
        dialog.setOnDismissListener { episodePanel = null }
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(96), dp(390))
        playButton.requestFocus()
    }
    private fun play(p: Programme, next: Programme?, restart: Boolean) { startActivity(Intent(this, PlaybackActivity::class.java).putExtra("episode", p.json().toString()).putExtra("next", next?.json()?.toString()).putExtra("restart", restart)) }
    private fun saved() { returnScreen = { saved() }; reset("Saved programmes"); val items = store.saved(); if (items.isEmpty()) content.addView(label("Save a programme from its episode page.", 20f, true)); grid(items, ::openShow) }
    private fun live() { returnScreen = { live() }; load("Live", { api.channels() }) { channels -> grid(channels.map { Programme(it.id, it.title, it.name, image = it.image) }) { p -> startActivity(Intent(this, PlaybackActivity::class.java).putExtra("channel", p.id).putExtra("title", p.title + " · " + p.subtitle)) } } }
    private fun saveBackupToDownloads(bytes: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            pendingBackup = bytes
            runCatching { exportBackup.launch("Beeb-UHD-backup.beeb") }.onFailure { pendingBackup?.fill(0); pendingBackup = null; Toast.makeText(this, "No file picker is available. Keep the original TwinVine configuration on your computer.", Toast.LENGTH_LONG).show() }
            return
        }
        showBackupProgress("Saving encrypted backup…")
        io.execute {
            val result = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "Beeb-UHD-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.beeb")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Beeb/")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create backup")
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to write backup")
                    contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
            }
            bytes.fill(0)
            main.post { if (!isDestroyed) { backupProgress?.dismiss(); backupProgress = null; Toast.makeText(this, if (result.isSuccess) "Encrypted backup saved in Downloads/Beeb. Copy it to your computer and keep the password separately." else "Unable to save backup to Downloads", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun showBackupProgress(title: String) {
        backupProgress?.dismiss()
        backupProgress = AlertDialog.Builder(this).setTitle(title).setMessage("This can take a little while on a TV.").setView(ProgressBar(this)).create().also { it.show() }
    }
    private fun passwordPrompt(title: String, confirm: Boolean, action: (CharArray) -> Unit) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun field(hint: String) = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true; fields.addView(this) }
        val first = field("Backup password (at least 12 characters)")
        val second = if (confirm) field("Repeat password") else null
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields).setPositiveButton(if (confirm) "Save backup" else "Restore", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = first.text.toString().toCharArray()
            if (password.size < 12) { first.error = "Use at least 12 characters"; password.fill('\u0000') }
            else if (second != null && first.text.toString() != second.text.toString()) { second.error = "Passwords do not match"; password.fill('\u0000') }
            else { first.setText(""); second?.setText(""); dialog.dismiss(); action(password) }
        } }
        dialog.show()
    }
    private fun enableDirectUhd() {
        if(directSetup!=null)return
        val session=UhdSetupSession();directSetup=session
        val dialog=AlertDialog.Builder(this).setTitle("Enable BBC UHD").setMessage("Downloading and validating UHD setup…")
            .setView(ProgressBar(this)).setNegativeButton("Cancel") { _,_ -> if(session.cancel() && directSetup===session)directSetup=null }
            .setOnCancelListener { if(session.cancel() && directSetup===session)directSetup=null }.create()
        setupProgress=dialog;dialog.show()
        io.execute {
            val result=runCatching { DirectUhdSetup.enable(api,session) { message -> main.post { if(directSetup===session && !isDestroyed)dialog.setMessage(message) } } }
            main.post { if(directSetup===session && !isDestroyed) {
                directSetup=null;setupProgress=null;dialog.dismiss();settings()
                val message=result.fold({ title -> "Connected. BBC returned a 4K HLG stream for $title." },{ error ->
                    if(error is java.util.concurrent.CancellationException) "Setup cancelled. Your existing setup has been kept."
                    else if(error is DirectUhdSetup.SetupError) error.message.orEmpty()
                    else "Could not verify BBC UHD access. Your existing setup has been kept. Check your connection and try again."
                })
                AlertDialog.Builder(this).setTitle(if(result.isSuccess)"BBC UHD connected" else "BBC UHD setup").setMessage(message).setPositiveButton("OK",null).show()
            } }
        }
    }
    private fun settings() {
        returnScreen = { settings() }
        reset("Settings")
        content.addView(label("Beeb for Android TV · ${BuildConfig.VERSION_NAME}", 22f))
        content.addView(label("4K UHD playback", 24f)); content.addView(label(api.vault.status(), 19f, true))
        content.addView(label("Automatically configure UHD playback on this TV. No computer required.",18f,true))
        content.addView(discoveryText("Enable BBC UHD") { enableDirectUhd() })
        content.addView(label(UhdSupport.inspect(this).description, 17f, true))
        if (api.vault.configured()) content.addView(discoveryText("Export encrypted UHD backup") {
            passwordPrompt("Protect UHD backup", true) { password -> showBackupProgress("Encrypting UHD backup…"); io.execute {
                val result = runCatching { api.vault.exportBackup(password) }; password.fill('\u0000')
                main.post { if (!isDestroyed) { backupProgress?.dismiss(); backupProgress = null; result.fold({ bytes -> saveBackupToDownloads(bytes) }, { Toast.makeText(this, "Unable to export this identity", Toast.LENGTH_LONG).show() }) } }
            } }
        })
        if (api.vault.configured()) content.addView(discoveryText("Forget UHD identity") { AlertDialog.Builder(this).setMessage("Remove UHD setup from this app?").setPositiveButton("Forget") { _, _ -> api.vault.forget(); settings() }.setNegativeButton("Cancel", null).show() })
        content.addView(label("TV home", 22f))
        content.addView(discoveryText("Add live-channel cards to TV home") {
            val busy = Toast.makeText(this, "Updating live-channel cards…", Toast.LENGTH_SHORT); busy.show()
            io.execute { val result = runCatching { LiveHomeChannels.publish(this, api.channels()) }; main.post { if (!isDestroyed) result.fold({ id ->
                LiveHomeChannels.requestVisible(this, id)
                Toast.makeText(this, "Live-channel cards published. Visibility and placement depend on your TV launcher.", Toast.LENGTH_LONG).show()
            }, { Toast.makeText(this, "The launcher could not accept the live-channel row.", Toast.LENGTH_LONG).show() }) } }
        })
        content.addView(label("Subtitles", 22f))
        content.addView(discoveryText("Captions on 10-second rewind: ${if (store.autoCaptions()) "On" else "Off"}") {
            store.autoCaptions(!store.autoCaptions())
            (content.findFocus() as? TextView)?.text="Captions on 10-second rewind: ${if(store.autoCaptions()) "On" else "Off"}"
        })
        content.addView(discoveryText("Android caption appearance settings") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS)) }.onFailure { Toast.makeText(this, "Caption settings are unavailable on this device", Toast.LENGTH_LONG).show() } })
        content.addView(label("Independent BBC player · GPL-3.0-or-later", 17f, true))
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
