package dev.cinematv

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import dev.cinematv.hybrid.HybridPolicy
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.metadataLine
import dev.cinematv.ui.shared.presentationKey
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real launcher activity and packaged production JS, never a web mock or TV fixture Activity. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class HybridHostUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val name = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var model: CinemaViewModel
    private lateinit var server: UiFixtureServer
    private var inputTraceEnabled = false
    private val inputTrace = ArrayDeque<JSONObject>()
    private val expectedMode get() = InstrumentationRegistry.getArguments().getString("hybridExpectedMode")
        ?: if (Build.VERSION.SDK_INT >= 29) "hybrid" else "native-fallback"
    private val hybrid get() = expectedMode == "hybrid"
    private val output get() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: instrumentation.targetContext.getExternalFilesDir(null)!!.absolutePath, "hybrid-evidence").apply { mkdirs() }

    @Before fun launchProductionHost() {
        assertTrue(expectedMode in setOf("hybrid", "native-fallback"))
        assertEquals("This lane must be an actual Android TV system image", Configuration.UI_MODE_TYPE_TELEVISION,
            instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        instrumentation.targetContext.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
        server = UiFixtureServer(instrumentation.context).apply { homeCatalog = true }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            model = ViewModelProvider(it)[CinemaViewModel::class.java]
        }
        // Isolate the first Home rail from the playback case's persisted history.
        // Persistence is still verified across the player rebuild within that case.
        runBlocking {
            model.repo.library.initialize()
            model.repo.library.clearHistory()
        }
        scenario.onActivity {
            model.updateReducedMotion(true)
            model.repo.configureMetadata(server.base, "hybrid-offline-fixture-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(25_000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 }
        awaitPresentation()
    }

    @After fun recordAndClose() {
        try {
            if (::activity.isInitialized) {
                if (inputTraceEnabled) recordInputTrace("after-test")
                runCatching { screenshot(name.methodName) }.onFailure {
                    File(output, "${name.methodName}-capture-error.txt").writeText(it.stackTraceToString())
                    throw it
                }
                File(output, "${name.methodName}.json").writeText(JSONObject()
                    .put("method", name.methodName).put("expectedMode", expectedMode).put("api", Build.VERSION.SDK_INT)
                    .put("provider", runCatching { WebViewCompat.getCurrentWebViewPackage(activity)?.versionName }.getOrNull())
                    .put("route", model.page.name).put("webViewPresent", web() != null)
                    .put("activity", activity.javaClass.name).put("syntheticContent", true)
                    .put("webGeometry", runCatching { webGeometry() }.getOrNull()).toString(2))
            }
        } finally {
            try {
                if (inputTraceEnabled) {
                    try {
                        File(output, "${name.methodName}-input-trace.json").writeText(JSONArray(inputTrace.toList()).toString(2))
                    } finally { runCatching { evaluate("window.__hybridQaInputTrace?.dispose(); true") } }
                }
            } finally {
                if (::scenario.isInitialized) scenario.close()
                if (::server.isInitialized) server.close()
                instrumentation.targetContext.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
            }
        }
    }

    private fun web(): WebView? {
        var result: WebView? = null
        instrumentation.runOnMainSync { result = HybridDom.find(activity.window.decorView) }
        return result
    }
    private fun evaluate(expression: String): String = HybridDom.evaluate(activity, expression)
    private fun awaitPresentation() {
        if (hybrid) {
            rule.waitUntil(25_000) { web() != null && runCatching { evaluate("Boolean(document.querySelector('.tv-app'))") == "true" }.getOrDefault(false) }
            assertNotNull("Modern lane must not pass through native fallback", web())
            assertEquals(HybridPolicy.ENTRY_URL, evaluate("location.href"))
            rule.onNodeWithTag("hybrid-native-fallback").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("hybrid-native-fallback").assertExists()
            assertNull("Legacy lane must not instantiate an unsupported WebView", web())
        }
    }
    private fun awaitRoute(route: Page) {
        rule.waitUntil(20_000) { model.page == route }
        if (hybrid && route != Page.PLAYER) rule.waitUntil(15_000) {
            web() != null && runCatching { evaluate("Boolean(document.querySelector('.route-${route.name.lowercase()}'))") == "true" }.getOrDefault(false)
        }
    }
    private fun key(code: Int) {
        // Espresso's KeyEventActionBase regenerates downTime for ACTION_UP.
        // Inject one real press with shared identity, as Android remotes do.
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }
    private fun focus(id: String) {
        val encoded = JSONObject.quote(id)
        assertEquals("true", evaluate("(() => {const n = Array.from(document.querySelectorAll('[data-focus]')).find(x => x.dataset.focus === $encoded); if (!n || n.disabled) return false; n.focus({preventScroll:true}); n.scrollIntoView({block:'nearest',inline:'nearest'}); return document.activeElement === n})()"))
    }
    private fun click(id: String) {
        // A dismissed Dialog and completed search do not imply that Android has
        // returned input ownership. Observe it; never request native focus or hide IME here.
        awaitHybridInputReady(id, requireTarget = false)
        focus(id)
        awaitHybridInputReady(id, requireTarget = true)
        try { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        finally { if (inputTraceEnabled) recordInputTrace("after-key", id) }
    }
    private fun focused() = evaluate("document.activeElement?.dataset.focus || ''")

    private fun nativeInputState(): JSONObject {
        var state = JSONObject()
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val current = HybridDom.find(decor)
            val insets = ViewCompat.getRootWindowInsets(decor)
            val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime())
            val ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                decor.isAttachedToWindow && decor.isShown && decor.hasWindowFocus() && decor.hasFocus() &&
                current != null && current.isAttachedToWindow && current.isShown && current.isFocused && current.hasFocus() &&
                current.hasWindowFocus() && (Build.VERSION.SDK_INT < 30 || imeVisible == false)
            state = JSONObject().put("uptimeMs", SystemClock.uptimeMillis()).put("route", model.page.name)
                .put("lifecycle", activity.lifecycle.currentState.name).put("activityWindowFocus", activity.hasWindowFocus())
                .put("decorAttached", decor.isAttachedToWindow).put("decorShown", decor.isShown).put("decorWindowFocus", decor.hasWindowFocus())
                .put("decorHasFocus", decor.hasFocus()).put("webPresent", current != null)
                .put("webAttached", current?.isAttachedToWindow == true).put("webShown", current?.isShown == true).put("webFocused", current?.isFocused == true)
                .put("webHasFocus", current?.hasFocus() == true).put("webWindowFocus", current?.hasWindowFocus() == true)
                .put("imeKnown", insets != null).put("imeVisible", imeVisible ?: JSONObject.NULL).put("nativeReady", ready)
        }
        return state
    }

    private fun awaitHybridInputReady(target: String, requireTarget: Boolean) {
        val started = SystemClock.uptimeMillis()
        var observation = nativeInputState()
        if (inputTraceEnabled) recordInputTrace(if (requireTarget) "before-key-wait" else "before-focus-wait", target, observation)
        try {
            rule.waitUntil(10_000) {
                observation = nativeInputState()
                if (!observation.getBoolean("nativeReady")) false
                else {
                    val dom = JSONObject(evaluate("JSON.stringify({focused:document.hasFocus(),active:document.activeElement?.dataset.focus || ''})"))
                    observation.put("document", dom)
                    dom.getBoolean("focused") && (!requireTarget || dom.getString("active") == target)
                }
            }
        } finally {
            if (inputTraceEnabled) recordInputTrace(if (requireTarget) "pre-key" else "before-focus", target,
                observation.put("waitMs", SystemClock.uptimeMillis() - started))
        }
    }

    /** Passive, bounded metadata only. Wrappers preserve the transport's receiver, result and exceptions. */
    private fun installInputTrace() {
        inputTraceEnabled = true
        assertEquals("true", evaluate("""(() => {
            if (window.__hybridQaInputTrace) return true;
            const events = [], transport = window.CinemaNative;
            const note = value => { try { if (events.length === 128) events.shift(); events.push({t:performance.now(),...value}); } catch {} };
            const focusId = node => { const id=node?.closest?.('[data-focus]')?.dataset.focus || ''; return /^(nav:[A-Z]+|search:input|settings:[a-z]+)$/.test(id) ? id : ''; };
            const remote = new Set(['Enter','Escape','ArrowUp','ArrowDown','ArrowLeft','ArrowRight']);
            const listener = event => {
                if ((event.type === 'keydown' || event.type === 'keyup') && !remote.has(event.key)) return;
                note({kind:event.type,key:remote.has(event.key) ? event.key : undefined,target:focusId(event.target),
                    active:focusId(document.activeElement),documentFocus:document.hasFocus(),trusted:event.isTrusted,prevented:event.defaultPrevented});
            };
            const types = ['keydown','keyup','click','focusin','focusout'];
            types.forEach(type => document.addEventListener(type, listener, true));
            const wire = (kind, raw) => { try {
                const value = JSON.parse(raw);
                const safe = (text, pattern) => typeof text === 'string' && pattern.test(text) ? text : undefined;
                note({kind,id:safe(value.id,/^[A-Za-z0-9_-]{1,96}$/),method:safe(value.method,/^[a-z]+(?:\.[a-z]+)*$/),
                    event:safe(value.event,/^[a-z]+(?:\.[a-z]+)*$/),error:safe(value.error?.code,/^[A-Z_]{1,64}$/)});
            } catch {} };
            const post = transport.postMessage, receive = transport.onmessage;
            const wrappedPost = function() { wire('post', arguments[0]); return Reflect.apply(post, this, arguments); };
            const wrappedReceive = function() { wire('reply', arguments[0]?.data); if (receive) return Reflect.apply(receive, this, arguments); };
            transport.postMessage = wrappedPost;
            transport.onmessage = wrappedReceive;
            window.__hybridQaInputTrace = {events,dispose() {
                types.forEach(type => document.removeEventListener(type, listener, true));
                if (transport.postMessage === wrappedPost) transport.postMessage = post;
                if (transport.onmessage === wrappedReceive) transport.onmessage = receive;
                delete window.__hybridQaInputTrace;
            }};
            return transport.postMessage === wrappedPost && transport.onmessage === wrappedReceive;
        })()"""))
    }

    private fun recordInputTrace(stage: String, target: String = "", state: JSONObject = nativeInputState()) {
        val entry = JSONObject().put("stage", stage).put("target", target).put("native", state)
        runCatching { JSONArray(evaluate("JSON.stringify(window.__hybridQaInputTrace?.events.splice(0) || [])")) }
            .onSuccess { entry.put("domEvents", it) }
            .onFailure { entry.put("domReadError", it.javaClass.simpleName) }
        if (inputTrace.size == 32) inputTrace.removeFirst()
        inputTrace.addLast(entry)
    }

    private fun webGeometry(): JSONObject? {
        val current = web() ?: return null
        var native = JSONObject()
        instrumentation.runOnMainSync {
            native = JSONObject().put("width", current.width).put("height", current.height)
                .put("contentHeight", current.contentHeight)
                .put("scrollX", current.scrollX).put("scrollY", current.scrollY)
                .put("layoutWidth", current.layoutParams?.width).put("layoutHeight", current.layoutParams?.height)
                .put("isFocused", current.isFocused).put("hasFocus", current.hasFocus())
                .put("windowFocus", current.hasWindowFocus())
        }
        val dom = evaluate("""JSON.stringify((() => {
            const rect = s => { const n=document.querySelector(s); if(!n) return null; const r=n.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,scrollTop:n.scrollTop,scrollLeft:n.scrollLeft,scrollHeight:n.scrollHeight,clientHeight:n.clientHeight}; };
            const style = s => { const n=document.querySelector(s); if(!n) return null; const c=getComputedStyle(n); return {top:c.top,height:c.height,overflowY:c.overflowY,scrollPaddingTop:c.scrollPaddingTop}; };
            return {innerWidth,innerHeight,dpr:devicePixelRatio,windowX:scrollX,windowY:scrollY,
                documentHeight:document.documentElement.clientHeight,documentScroll:document.scrollingElement?.scrollTop,
                documentScrollHeight:document.documentElement.scrollHeight,
                bodyScroll:document.body.scrollTop,body:rect('body'),app:rect('.tv-app'),content:rect('.content'),topbar:rect('.topbar'),
                visualViewport:window.visualViewport ? {height:visualViewport.height,offsetTop:visualViewport.offsetTop,pageTop:visualViewport.pageTop,scale:visualViewport.scale} : null,
                computed:{topbar:style('.topbar'),content:style('.content'),body:style('body')},
                documentFocus:document.hasFocus(),active:document.activeElement?.dataset.focus || '',activeRect:rect('[data-focus]:focus')}; })())""")
        return JSONObject().put("native", native).put("dom", JSONObject(dom))
    }
    private fun assertNativeFocusAfterDown(traverseRail: Boolean = false) {
        val destinations = if (traverseRail) rule.runOnIdle { model.featured.take(10) } else emptyList()
        val diagnostic = HybridNativeDiagnostics(instrumentation, activity, model, output, name.methodName)
        try {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
            if (traverseRail) {
                assertEquals(10, destinations.size)
                fun assertDestination(index: Int) {
                    rule.onNode(hasContentDescription(destinations[index].title) and isFocused()).assertIsFocused()
                    rule.runOnIdle {
                        assertEquals("rail:为你精选", model.focusCoordinator.last("HOME")?.region)
                        assertEquals(destinations[index].presentationKey, model.focusCoordinator.last("HOME")?.itemKey)
                    }
                }
                assertDestination(0)
                repeat(9) { index -> key(KeyEvent.KEYCODE_DPAD_RIGHT); assertDestination(index + 1) }
                assertNativeCardVisible(destinations[9])
                screenshot("${name.methodName}-native-after-nine-rights")
            }
        } finally { diagnostic.close() }
    }
    private fun assertNativeCardVisible(item: MediaItem) {
        val matcher = hasContentDescription(item.title) and isFocused()
        val card = rule.onNode(matcher).assertIsDisplayed().fetchSemanticsNode()
        val root = rule.onRoot().fetchSemanticsNode()
        var viewportNode = card.parent
        while (viewportNode != null && !viewportNode.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
            viewportNode = viewportNode.parent
        }
        val column = requireNotNull(viewportNode) { "Focused native card has no vertical viewport" }
        fun bounds(node: SemanticsNode): Rect = rule.runOnUiThread {
            root.layoutInfo.coordinates.localBoundingBoxOf(node.layoutInfo.coordinates, clipBounds = false)
        }
        val rootBounds = bounds(root)
        val columnBounds = bounds(column)
        val viewport = Rect(maxOf(rootBounds.left, columnBounds.left), maxOf(rootBounds.top, columnBounds.top),
            minOf(rootBounds.right, columnBounds.right), minOf(rootBounds.bottom, columnBounds.bottom))
        val bottomSpace = 4f * card.layoutInfo.density.density
        fun assertInside(label: String, node: SemanticsNode) {
            val rect = bounds(node)
            assertTrue("$label must be fully visible: $rect in $viewport", rect.width > 0 && rect.height > 0 &&
                rect.left >= viewport.left - .5f && rect.top >= viewport.top - .5f &&
                rect.right <= viewport.right + .5f && rect.bottom <= viewport.bottom - bottomSpace + .5f)
        }
        assertInside("Focused native card", card)
        for (text in listOf(item.title, item.metadataLine())) {
            assertTrue("Synthetic caption and metadata must be populated", text.isNotBlank())
            val nodes = rule.onAllNodes(hasText(text) and hasAnyAncestor(matcher), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Focused card must retain $text", nodes.isNotEmpty())
            nodes.forEach { assertInside("Native caption/metadata", it) }
        }
        File(output, "${name.methodName}-native-after-nine-rights-geometry.json").writeText(JSONObject()
            .put("card", bounds(card).toString()).put("viewport", viewport.toString())
            .put("bottomSpacePx", bottomSpace).put("focusedKey", item.presentationKey).toString(2))
    }
    private fun playerBackPress() {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        try {
            instrumentation.runOnMainSync {
                assertEquals("A Back down must stay with the player until its matching release", Page.PLAYER, model.page)
            }
        } finally {
            instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        instrumentation.waitForIdleSync()
        rule.waitForIdle()
        assertTrue("One player Back must never skip its detail return route", model.page in setOf(Page.PLAYER, Page.DETAIL))
    }
    private fun openFirstDetail() {
        if (hybrid) click("hero:open") else rule.runOnIdle { model.open(model.featured.first()) }
        rule.waitUntil(25_000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.isNotEmpty() == true }
        awaitRoute(Page.DETAIL)
    }
    private fun captureReplies() {
        evaluate("(() => { if (!window.__hybridQaReplies) {window.__hybridQaReplies = {}; const receive = CinemaNative.onmessage; CinemaNative.onmessage = function(e) {const m=JSON.parse(e.data); if (typeof m.id==='string' && m.id.startsWith('qa_')) window.__hybridQaReplies[m.id]=m; if(receive) receive(e);};} return true; })()")
    }
    private fun request(id: String, method: String, params: JSONObject = JSONObject()): JSONObject {
        captureReplies()
        val request = JSONObject().put("v", 1).put("id", id).put("method", method).put("params", params).toString()
        evaluate("(() => { CinemaNative.postMessage(${JSONObject.quote(request)}); return true; })()")
        rule.waitUntil(12_000) { evaluate("Boolean(window.__hybridQaReplies[${JSONObject.quote(id)}])") == "true" }
        return JSONObject(evaluate("JSON.stringify(window.__hybridQaReplies[${JSONObject.quote(id)}])"))
    }
    private fun screenshot(label: String) {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        awaitPresentationFrame()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Compositor screenshot was empty")
        try {
            assertTrue(bitmap.width > bitmap.height)
            val path = File(output, "$label.png")
            path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(path.length() > 1024)
        } finally { bitmap.recycle() }
    }

    /** Fence both Chromium and the native replacement after a WebView is destroyed. */
    private fun awaitPresentationFrame() {
        val current = web()
        val ready = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        var cleanup: (() -> Unit)? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            fun awaitDraw(view: View) {
                if (!active.get()) return
                try {
                    check(view.isAttachedToWindow)
                    val observer = view.viewTreeObserver
                    if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                        val committed = Runnable { if (active.get()) ready.countDown() }
                        observer.registerFrameCommitCallback(committed)
                        cleanup = { if (observer.isAlive) observer.unregisterFrameCommitCallback(committed); Unit }
                    } else {
                        val drawn = object : android.view.ViewTreeObserver.OnDrawListener {
                            private var observed = false
                            override fun onDraw() {
                                if (observed || !active.get()) return
                                observed = true
                                view.postOnAnimation { if (active.get()) ready.countDown() }
                            }
                        }
                        observer.addOnDrawListener(drawn)
                        cleanup = { if (observer.isAlive) observer.removeOnDrawListener(drawn) }
                    }
                    view.postInvalidateOnAnimation()
                } catch (error: Throwable) { failure = error; ready.countDown() }
            }
            if (current == null) awaitDraw(activity.window.decorView)
            else current.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!active.get()) return
                    if (HybridDom.find(activity.window.decorView) !== current) {
                        failure = AssertionError("WebView changed while fencing its compositor")
                        ready.countDown()
                    } else awaitDraw(current)
                }
            })
        }
        try {
            assertTrue("Presentation did not submit the tested state within 8s", ready.await(8, TimeUnit.SECONDS))
            failure?.let { throw it }
        } finally {
            active.set(false)
            instrumentation.runOnMainSync { cleanup?.invoke() }
        }
    }

    private fun assertFixedWebViewport(geometry: JSONObject) {
        val native = geometry.getJSONObject("native")
        val dom = geometry.getJSONObject("dom")
        val width = dom.getDouble("innerWidth")
        val height = dom.getDouble("innerHeight")
        val dpr = dom.getDouble("dpr")
        assertTrue("A usable DOM viewport is required: $geometry", width > 0 && height > 0 && dpr > 0)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, native.getInt("layoutWidth"))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, native.getInt("layoutHeight"))
        assertEquals(native.getDouble("width"), width * dpr, 1.0)
        assertEquals(native.getDouble("height"), height * dpr, 1.0)
        // Chromium may promote a full-viewport overflow element to its effective
        // root scroller and mirror that element's offset into WebView.scrollY.
        // Assert the actual document/visual viewport and fixed elements instead;
        // native offsets remain in the evidence as diagnostics.
        for (key in listOf("windowX", "windowY", "documentScroll", "bodyScroll")) {
            assertEquals("Document $key must remain fixed: $geometry", 0.0, dom.getDouble(key), .5)
        }
        assertEquals(height, dom.getDouble("documentHeight"), 1.0)
        assertEquals(height, dom.getDouble("documentScrollHeight"), 1.0)
        for (key in listOf("body", "app", "content")) {
            val rect = dom.getJSONObject(key)
            assertEquals("$key x must stay at the viewport origin", 0.0, rect.getDouble("x"), .5)
            assertEquals("$key y must stay at the viewport origin", 0.0, rect.getDouble("y"), .5)
            assertEquals("$key must fill the viewport width", width, rect.getDouble("width"), 1.0)
            assertEquals("$key must fill the viewport height", height, rect.getDouble("height"), 1.0)
        }
        val visual = dom.getJSONObject("visualViewport")
        assertEquals(height, visual.getDouble("height"), 1.0)
        assertEquals(0.0, visual.getDouble("offsetTop"), .5)
        assertEquals(0.0, visual.getDouble("pageTop"), .5)
        assertEquals(1.0, visual.getDouble("scale"), .001)
        val content = dom.getJSONObject("content")
        assertEquals("The vertical content container must not move horizontally", 0.0, content.getDouble("scrollLeft"), .5)
        assertTrue("Only the declared content container may scroll: $geometry", content.getDouble("scrollTop") >= 0 &&
            content.getDouble("scrollTop") <= content.getDouble("scrollHeight") - content.getDouble("clientHeight") + .5)
        val topbar = dom.getJSONObject("topbar")
        assertEquals("Floating navigation must retain its 4.2vh inset", height * .042, topbar.getDouble("y"), 1.0)
        assertTrue("Floating navigation must fit the fixed viewport", topbar.getDouble("height") > 0 &&
            topbar.getDouble("x") >= 0 && topbar.getDouble("x") + topbar.getDouble("width") <= width + 1 &&
            topbar.getDouble("y") + topbar.getDouble("height") < height)
    }

    @Test fun productionPagesAcceptRemoteInput() {
        if (hybrid) {
            focus("nav:HOME")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals("nav:EXPLORE", focused())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitRoute(Page.EXPLORE)
            for (route in listOf(Page.MOVIES, Page.SERIES, Page.LIBRARY, Page.LIVE)) {
                click("nav:${route.name}")
                awaitRoute(route)
                assertTrue("Each page needs a real focused control", focused().isNotBlank())
            }
            rule.waitUntil(15_000) { model.channels.isNotEmpty() }
            rule.waitUntil(15_000) { evaluate("Boolean(document.querySelector('[data-focus^=\"channel:\"]'))") == "true" }
            evaluate("(() => {document.querySelector('[data-focus^=\"channel:\"]').focus();return true;})()")
            rule.waitUntil(15_000) { model.activeChannel != null && model.epgPrograms.isNotEmpty() }
            click("nav:HOME"); awaitRoute(Page.HOME)
            focus("hero:open")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(focused().startsWith("card:"))
            val original = focused()
            repeat(9) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertNotEquals("Offscreen rail navigation must advance", original, focused())
            assertTrue(focused().startsWith("card:"))
            awaitPresentationFrame()
            val geometry = webGeometry()!!
            File(output, "${name.methodName}-after-nine-rights-geometry.json").writeText(geometry.toString(2))
            assertFixedWebViewport(geometry)
            val dom = geometry.getJSONObject("dom")
            val card = dom.getJSONObject("activeRect")
            val topbar = dom.getJSONObject("topbar")
            assertTrue("Focused poster must clear the floating navigation: $geometry",
                card.getDouble("y") >= topbar.getDouble("y") + topbar.getDouble("height") - 1)
            assertTrue("Focused poster must fit within the viewport: $geometry",
                card.getDouble("width") > 0 && card.getDouble("height") > 0 && card.getDouble("x") >= -1 &&
                    card.getDouble("x") + card.getDouble("width") <= dom.getDouble("innerWidth") + 1 &&
                    card.getDouble("y") + card.getDouble("height") <= dom.getDouble("innerHeight") + 1)
        } else {
            rule.onNodeWithText("观看详情").assertExists()
            assertNativeFocusAfterDown(traverseRail = true)
            rule.onNodeWithContentDescription("打开搜索").performClick()
            rule.onNodeWithText("想看的，在这里。").assertExists()
            assertNull(web())
            Espresso.closeSoftKeyboard()
            key(KeyEvent.KEYCODE_BACK)
            assertEquals(Page.HOME, model.page)
        }
    }

    @Test fun searchInputAndNativeSettingsReturnToTheirWebControls() {
        if (hybrid) {
            installInputTrace()
            click("nav:SEARCH"); awaitRoute(Page.SEARCH)
            click("search:input")
            rule.onNodeWithTag("hybrid-search-input").assertExists()
            rule.onNode(hasSetTextAction()).performTextReplacement("旷野")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.waitUntil(20_000) { model.query == "旷野" && !model.busy && model.results.isNotEmpty() }
            rule.onNodeWithTag("hybrid-search-input").assertDoesNotExist()
            awaitRoute(Page.SEARCH)
            assertTrue(focused().isNotBlank())
            click("nav:SETTINGS"); awaitRoute(Page.SETTINGS)
            click("settings:updates")
            rule.onNodeWithTag("hybrid-native-panel").assertExists()
            rule.onNodeWithText("关于 CinemaTV").assertExists()
            assertNull("Native panels also release the browsing renderer", web())
            key(KeyEvent.KEYCODE_BACK)
            awaitRoute(Page.SETTINGS)
            rule.waitUntil(10_000) { focused() == "settings:updates" }
        } else {
            rule.runOnIdle { model.navigate(Page.SEARCH) }
            rule.onNodeWithText("想看的，在这里。").assertExists()
            rule.onNode(hasSetTextAction()).performTextReplacement("旷野")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.waitUntil(20_000) { !model.busy && model.results.isNotEmpty() }
            rule.runOnIdle { model.navigate(Page.SETTINGS) }
            rule.onNodeWithText("CinemaTV 设置").assertExists()
            assertNull(web())
        }
    }

    @Test fun nativePlaybackPreservesFavoriteProgressAndReturnFocus() {
        val originalFocus = if (hybrid) { focus("hero:open"); focused() } else ""
        openFirstDetail()
        val beforeFavorite = model.repo.isFavorite(model.selected!!)
        if (hybrid) {
            click("detail:favorite")
            rule.waitUntil(10_000) { model.repo.isFavorite(model.selected!!) != beforeFavorite }
            click("detail:play")
        } else rule.onNodeWithText("▶ 立即播放").performClick()
        rule.waitUntil(25_000) { model.page == Page.PLAYER && (model.selected?.durationMs ?: 0) > 0 }
        assertNull("A hidden WebView would still consume renderer memory during playback", web())
        screenshot("${name.methodName}-player")
        // No panel is open: a complete press hides controls, the next returns to detail.
        // Check both real key phases so the new WebView cannot reuse the player's up.
        repeat(2) { if (model.page == Page.PLAYER) playerBackPress() }
        awaitRoute(Page.DETAIL)
        rule.waitUntil(10_000) { (model.selected?.positionMs ?: 0) > 0 }
        assertTrue((model.selected?.durationMs ?: 0) >= 29_000)
        if (hybrid) {
            rule.waitUntil(10_000) { focused() == "detail:play" }
            assertEquals(!beforeFavorite, model.repo.isFavorite(model.selected!!))
            key(KeyEvent.KEYCODE_BACK)
            awaitRoute(Page.HOME)
            rule.waitUntil(10_000) { focused() == originalFocus }
        } else assertNull(web())
    }

    @Test fun bridgeRejectsForeignResourcesAndStalePlaybackIdentity() {
        if (hybrid) {
            val stateReply = request("qa_state", "app.ready", JSONObject().put("protocol", 1)).getJSONObject("result").getJSONObject("state")
            val text = stateReply.toString()
            assertFalse(text.contains(server.base))
            assertFalse(text.contains("hybrid-offline-fixture-token"))
            assertFalse(text.contains("stream.mp4"))
            assertTrue(text.toByteArray(Charsets.UTF_8).size < 512 * 1024)
            assertEquals("METHOD_NOT_ALLOWED", request("qa_url", "playUrl", JSONObject().put("url", "${server.base}/private"))
                .getJSONObject("error").getString("code"))
            evaluate("(() => {window.__hybridQaNetwork='pending';fetch('https://untrusted.invalid/blocked').then(()=>window.__hybridQaNetwork='allowed').catch(()=>window.__hybridQaNetwork='blocked');return true;})()")
            rule.waitUntil(5000) { evaluate("window.__hybridQaNetwork") == "blocked" }
            openFirstDetail()
            val selected = request("qa_detail", "app.ready", JSONObject().put("protocol", 1)).getJSONObject("result").getJSONObject("state").getJSONObject("selected")
            assertEquals("CONFLICT", request("qa_stale", "playback.open", JSONObject().put("key", selected.getString("key")).put("episodeId", "episode_stale"))
                .getJSONObject("error").getString("code"))
            assertEquals(Page.DETAIL, model.page)
            evaluate("(() => {const f=document.createElement('iframe');document.body.append(f);window.__hybridQaFrame='pending';setTimeout(()=>{try{if(f.contentWindow.CinemaNative){f.contentWindow.CinemaNative.postMessage(JSON.stringify({v:1,id:'qa_frame',method:'navigation.go',params:{route:'HOME'}}));window.__hybridQaFrame='sent';}else window.__hybridQaFrame='blocked';}catch(e){window.__hybridQaFrame='blocked';}},100);return true;})()")
            rule.waitUntil(3000) { evaluate("window.__hybridQaFrame") != "pending" }
            SystemClock.sleep(300)
            assertEquals("A child frame must not be allowed to navigate the native model", Page.DETAIL, model.page)
            assertEquals("false", evaluate("Boolean(window.__hybridQaReplies.qa_frame)"))
            evaluate("(() => {document.querySelectorAll('iframe').forEach(x=>x.remove());return true;})()")
        } else {
            assertTrue(Build.VERSION.SDK_INT < HybridPolicy.MIN_HYBRID_API)
            assertNull(web())
            rule.runOnIdle { model.navigate(Page.SETTINGS) }
            rule.onNodeWithText("LibreTV 服务器").assertExists()
            assertNull(web())
        }
    }

    @Test fun rendererFailureAndReactFailureRecoverOrLegacyRecreates() {
        if (hybrid) {
            val first = web()!!
            evaluate("(() => {CinemaNative.postMessage(JSON.stringify({v:1,id:'qa_fatal',method:'app.fatal',params:{}}));return true;})()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("hybrid-recovery").fetchSemanticsNodes().isNotEmpty() }
            assertNull(web())
            rule.onNodeWithText("重新打开界面").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPresentation()
            assertNotSame(first, web())
            val second = web()!!
            var terminated = false
            instrumentation.runOnMainSync { if (Build.VERSION.SDK_INT >= 29) terminated = second.webViewRenderProcess?.terminate() == true }
            assertTrue("Modern WebView must exercise an actual renderer termination", terminated)
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("hybrid-recovery").fetchSemanticsNodes().isNotEmpty() }
            assertNull(web())
            screenshot("${name.methodName}-recovery")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPresentation()
            assertNotSame(second, web())
            awaitRoute(Page.HOME)
        } else {
            val old = activity
            scenario.recreate()
            scenario.onActivity { activity = it; model = ViewModelProvider(it)[CinemaViewModel::class.java] }
            assertNotSame(old, activity)
            awaitPresentation()
            rule.onNodeWithText("观看详情").assertExists()
            assertNativeFocusAfterDown()
        }
    }
}

/** Test-only DOM readback; no diagnostic bridge or fixture entrypoint is shipped in the APK. */
internal object HybridDom {
    fun find(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }

    fun evaluate(activity: MainActivity, expression: String): String {
        val latch = CountDownLatch(1)
        var value: String? = null
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val web = find(activity.window.decorView) ?: throw AssertionError("Expected a live production WebView")
                web.evaluateJavascript(expression) { raw ->
                    value = runCatching { JSONObject("{\"value\":$raw}").opt("value").toString() }.getOrElse { raw }
                    latch.countDown()
                }
            } catch (failure: Throwable) { error = failure; latch.countDown() }
        }
        assertTrue("DOM readback timed out", latch.await(6, TimeUnit.SECONDS))
        error?.let { throw it }
        return value.orEmpty()
    }
}
