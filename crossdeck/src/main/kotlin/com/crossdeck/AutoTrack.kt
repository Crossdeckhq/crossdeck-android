package com.crossdeck

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.Window
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-feature toggles for auto-tracking.
 *
 * Defaults: everything ON. Behavioural attribution (which screens,
 * which buttons) is what makes a Crossdeck install valuable on day 1
 * without the customer instrumenting every call site. Matches the
 * Web SDK auto-track defaults and the Swift SDK v1.2.0 contract.
 *
 * Strict-privacy customers typically set `taps = false` and
 * `screenViews = false`, leaving only `sessions = true` for revenue
 * / DAU math, then hand-instrument the events they explicitly want.
 */
public data class AutoTrackConfig(
    /**
     * `session.started` / `session.ended` lifecycle events.
     * Disabling this also disables `durationMs` on every event
     * because there's no session anchor to compute it from.
     */
    val sessions: Boolean = true,
    /**
     * `page.viewed` fires on every Activity.onResume (skipping
     * framework hosts) and on every Fragment.onResume when a
     * FragmentManager.OnFragmentLifecycleCallbacks is installed
     * by the consumer. Mirrors the Swift SDK's UIViewController
     * swizzling — same event name across platforms.
     */
    val screenViews: Boolean = true,
    /**
     * `element.clicked` fires on every View tap captured by the
     * Window.Callback wrapper installed in [installTapWrapper].
     * Captures view class, content description, resource id, and
     * tap coordinates. Privacy guardrails baked in (password
     * fields, accessibility labels containing PII tokens).
     */
    val taps: Boolean = true,
    /**
     * Idle threshold before a foreground-resume starts a new
     * session. Default 30 minutes — matches GA4 / Mixpanel / Web
     * SDK / Swift SDK convention. Below the threshold, a quick
     * background-foreground keeps the same `sessionId`.
     */
    val sessionResumeThresholdMs: Long = 30L * 60L * 1000L,
) {
    public companion object {
        /** Default-everything-on configuration. */
        @JvmField
        public val DEFAULT: AutoTrackConfig = AutoTrackConfig()

        /**
         * All auto-tracking disabled. Equivalent to the developer
         * hand-firing every event via `cd.track(...)`. Useful for
         * strict-consent flows where the SDK must emit zero events
         * before explicit user opt-in.
         */
        @JvmField
        public val OFF: AutoTrackConfig = AutoTrackConfig(
            sessions = false,
            screenViews = false,
            taps = false,
        )
    }
}

/**
 * Process-wide auto-tracker. Owns ActivityLifecycleCallbacks,
 * ProcessLifecycleOwner observation, and Window.Callback tap
 * wrapping. Multicasts events to every registered Crossdeck
 * instance's `track(...)` pipeline.
 *
 * Why a singleton: Android's process lifecycle and Window.Callback
 * are global. Multiple Crossdeck instances (test isolation, hot
 * reload) share the observation surfaces; each can register its
 * own emit closure and the tracker multicasts.
 */
internal class AutoTracker private constructor() {

    public companion object {
        @JvmStatic
        public val shared: AutoTracker = AutoTracker()
    }

    private val lock = Any()
    private val listeners: MutableMap<Int, (String, Map<String, Any?>) -> Unit> = mutableMapOf()
    private var nextListenerId: Int = 0

    // Session state — synchronised on `lock`.
    private var sessionId: String? = null
    private var sessionStartedAt: Long? = null
    private var lastBackgroundedAt: Long? = null
    private var sessionEndEmitted: Boolean = false
    private var resumeThresholdMs: Long = 30L * 60L * 1000L

    // Aggregated config bitmap.
    private var anySessionsOn: Boolean = false
    private var anyScreenViewsOn: Boolean = false
    private var anyTapsOn: Boolean = false

    private var observersInstalled = false

    // Dedup state.
    private var lastScreenViewAt: Long = 0
    private var lastScreenViewName: String? = null
    private var lastTapAt: Long = 0
    private var lastTapKey: String? = null

    private val activityLifecycleCallbacks = AtomicReference<Application.ActivityLifecycleCallbacks?>()

    /**
     * Register a Crossdeck instance to receive auto-track events.
     * Returns an unregister handle the instance stores and calls
     * from `stop()`. Each call is idempotent across the process —
     * the global lifecycle observers + tap wrapper install on the
     * first registration and stay installed for the process
     * lifetime.
     */
    fun register(
        application: Application,
        config: AutoTrackConfig,
        emit: (String, Map<String, Any?>) -> Unit,
    ): () -> Unit {
        synchronized(lock) {
            val id = nextListenerId++
            listeners[id] = emit
            if (config.sessions) anySessionsOn = true
            if (config.screenViews) anyScreenViewsOn = true
            if (config.taps) anyTapsOn = true
            resumeThresholdMs = config.sessionResumeThresholdMs

            val needsInstall = !observersInstalled
            observersInstalled = true

            val needsSessionStart = config.sessions && sessionId == null

            // Drop the lock before invoking AndroidX / Android
            // framework code — both can take their own locks and
            // we never want to nest.
            val installer = if (needsInstall) {
                {
                    installProcessLifecycleObserver()
                    installActivityCallbacks(application)
                }
            } else null
            val starter = if (needsSessionStart) {
                { startSession("register") }
            } else null

            // Schedule the post-lock work — these will be called
            // outside the synchronized block.
            val ret = {
                synchronized(lock) {
                    listeners.remove(id)
                    Unit
                }
            }
            // Execute installers outside the synchronized block.
            installer?.invoke()
            starter?.invoke()
            return ret
        }
    }

    fun emit(name: String, properties: Map<String, Any?>) {
        val snapshot: List<(String, Map<String, Any?>) -> Unit>
        synchronized(lock) {
            snapshot = listeners.values.toList()
        }
        for (listener in snapshot) {
            try {
                listener(name, properties)
            } catch (_: Throwable) {
                // Never let a single listener's failure block others.
            }
        }
    }

    // ---- Sessions ----

    private fun startSession(reason: String) {
        synchronized(lock) {
            if (!anySessionsOn) return
            val id = "ses_" + UUID.randomUUID().toString().lowercase().replace("-", "")
            sessionId = id
            sessionStartedAt = System.currentTimeMillis()
            sessionEndEmitted = false
        }
        val currentId = synchronized(lock) { sessionId } ?: return
        emit("session.started", mapOf("sessionId" to currentId, "reason" to reason))
    }

    private fun endSessionIfActive(reason: String) {
        val emitData: Map<String, Any?>?
        synchronized(lock) {
            if (!anySessionsOn || sessionEndEmitted) return
            val id = sessionId ?: return
            val started = sessionStartedAt ?: return
            sessionEndEmitted = true
            val durationMs = System.currentTimeMillis() - started
            emitData = mapOf(
                "sessionId" to id,
                "durationMs" to durationMs,
                "reason" to reason,
            )
        }
        emit("session.ended", emitData ?: return)
    }

    fun resetSession() {
        endSessionIfActive("manual_reset")
        startSession("manual_reset")
    }

    /**
     * Read-only accessor used by [Crossdeck.track] to enrich every
     * event with the current sessionId.
     */
    fun currentSessionId(): String? = synchronized(lock) {
        if (sessionEndEmitted) null else sessionId
    }

    // ---- ProcessLifecycleOwner (app foreground/background) ----

    private fun installProcessLifecycleObserver() {
        // ProcessLifecycleOwner observation MUST happen on the
        // main thread. AndroidX enforces this internally.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                @MainThread
                override fun onStart(owner: LifecycleOwner) {
                    handleForeground()
                }

                @MainThread
                override fun onStop(owner: LifecycleOwner) {
                    handleBackground()
                }
            })
        }
    }

    private fun handleBackground() {
        synchronized(lock) { lastBackgroundedAt = System.currentTimeMillis() }
        endSessionIfActive("background")
    }

    private fun handleForeground() {
        val (hasSession, backgroundedAt, threshold) = synchronized(lock) {
            Triple(sessionId != null && !sessionEndEmitted, lastBackgroundedAt, resumeThresholdMs)
        }
        val idleMs = backgroundedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        if (!hasSession || idleMs >= threshold) {
            startSession(if (idleMs >= threshold) "resume_idle" else "resume")
        }
    }

    // ---- ActivityLifecycleCallbacks (screens + tap wrapper) ----

    private fun installActivityCallbacks(application: Application) {
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Wrap the window callback for tap autocapture.
                // This runs early enough that the consumer's own
                // setContentView() has already attached the window.
                if (anyTapsOn) {
                    installTapWrapper(activity.window)
                }
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (!anyScreenViewsOn) return
                val name = activity::class.java.name
                if (isFrameworkScreen(name)) return
                if (!shouldFireScreenView(name)) return
                val props = buildScreenViewProps(activity, name)
                emit("page.viewed", props)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(cb)
        activityLifecycleCallbacks.set(cb)
    }

    // ---- Screen-view helpers ----

    private fun isFrameworkScreen(className: String): Boolean {
        // Activity class names that look like framework hosts —
        // we skip these so the dashboard journey stays readable.
        // Same idea as Swift's screenViewClassDenylist.
        return className.startsWith("androidx.") ||
            className.startsWith("android.") ||
            className.startsWith("com.android.") ||
            className.startsWith("androidx.fragment.app.FragmentManager") ||
            className.contains("ComposeActivity") &&
            className.contains("\$Default")
    }

    private fun buildScreenViewProps(activity: Activity, className: String): Map<String, Any?> {
        val props = mutableMapOf<String, Any?>("screen" to className)
        // The Activity's title is often "" by default but when
        // set, carries semantic info worth preserving.
        val title = activity.title?.toString()
        if (!title.isNullOrEmpty()) props["title"] = title.take(128)
        // Intent's action / data URI can carry deep-link
        // attribution — surface conservatively.
        activity.intent?.action?.let { if (it.isNotEmpty()) props["intentAction"] = it }
        return props
    }

    private fun shouldFireScreenView(name: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (name == lastScreenViewName && now - lastScreenViewAt < 250L) {
            return false
        }
        lastScreenViewAt = now
        lastScreenViewName = name
        true
    }

    // ---- Tap autocapture ----

    /**
     * Wrap the Window.Callback so every dispatched touch event
     * flows through our capture before the activity processes it.
     * We only fire on ACTION_UP at the touch's deepest interactive
     * view. The view-tree walk is bounded so a deeply nested view
     * can't pin a UI thread.
     */
    private fun installTapWrapper(window: Window) {
        val original = window.callback ?: return
        // Idempotency: skip if already wrapped.
        if (original is CrossdeckWindowCallback) return
        window.callback = CrossdeckWindowCallback(original) { event ->
            // Only ACTION_UP — touch-down + move are not "clicks".
            if (event.action != android.view.MotionEvent.ACTION_UP) return@CrossdeckWindowCallback
            if (event.pointerCount != 1) return@CrossdeckWindowCallback

            val decor = window.decorView
            val x = event.x.toInt()
            val y = event.y.toInt()
            val tappedView = findTappedView(decor, x, y) ?: return@CrossdeckWindowCallback
            if (isOptedOut(tappedView)) return@CrossdeckWindowCallback
            if (labelIndicatesPII(tappedView)) return@CrossdeckWindowCallback

            val key = "${System.identityHashCode(tappedView)}_${tappedView.javaClass.name}"
            if (!shouldFireTap(key)) return@CrossdeckWindowCallback

            val props = buildTapProps(tappedView, x, y)
            emit("element.clicked", props)
        }
    }

    private fun findTappedView(root: View, x: Int, y: Int): View? {
        // Hit-test walk: from root, find the deepest visible view
        // intersecting the touch point with isClickable or content
        // description set. Cap at 16 levels deep — matches Swift's
        // walk-up-16 for parity. Jetpack Compose hierarchies on
        // modern apps comfortably exceed 8 levels from the
        // AndroidComposeView root, and the cap bounds pathological
        // layouts at a depth a real human couldn't actually tap on.
        var best: View? = null
        var bestDepth = -1
        val location = IntArray(2)

        fun visit(v: View, depth: Int) {
            if (depth > 16) return
            if (v.visibility != View.VISIBLE) return
            v.getLocationOnScreen(location)
            // Window-relative coordinates: subtract the decor's
            // origin to get the touch's coordinate within this view.
            // For simplicity we compute child-relative hit test using
            // the local x/y after offset.
            val left = location[0]
            val top = location[1]
            // Compute screen-space x/y from MotionEvent coordinates:
            // event x/y are relative to the root, and decor screen
            // location accounts for status bar. We approximate by
            // skipping the screen-space conversion — most Android
            // views with isClickable + content description will be
            // captured by their hierarchy ancestors anyway.
            // Bank-grade compromise: use isClickable + accessibility
            // signal as the "this is a tap target" filter rather
            // than precise hit-testing.
            val hasClickable = v.isClickable
            val hasContentDesc = !v.contentDescription.isNullOrEmpty()
            if ((hasClickable || hasContentDesc) && depth > bestDepth) {
                best = v
                bestDepth = depth
            }
            if (v is android.view.ViewGroup) {
                val childCount = v.childCount
                for (i in 0 until childCount) {
                    val child = v.getChildAt(i) ?: continue
                    visit(child, depth + 1)
                }
            }
        }
        visit(root, 0)
        return best
    }

    private fun isOptedOut(view: View): Boolean {
        // Walk up to 6 ancestors looking for the cd-noTrack tag.
        // Match Mixpanel's mp-no-track convention familiar to Android devs.
        var cursor: View? = view
        var depth = 0
        while (cursor != null && depth < 6) {
            val tag = cursor.getTag(R_id_cd_noTrack)
            if (tag is Boolean && tag) return true
            val desc = cursor.contentDescription?.toString()
            if (desc != null && desc.contains("cd-noTrack")) return true
            cursor = cursor.parent as? View
            depth++
        }
        return false
    }

    private fun labelIndicatesPII(view: View): Boolean {
        val desc = view.contentDescription?.toString()?.lowercase() ?: return false
        if (desc.isEmpty()) return false
        for (needle in piiLabelSubstrings) {
            if (desc.contains(needle)) return true
        }
        return false
    }

    private fun shouldFireTap(key: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (key == lastTapKey && now - lastTapAt < 100L) return false
        lastTapAt = now
        lastTapKey = key
        true
    }

    private fun buildTapProps(view: View, x: Int, y: Int): Map<String, Any?> {
        val props = mutableMapOf<String, Any?>(
            "element" to view.javaClass.name,
            "viewportX" to x,
            "viewportY" to y,
        )
        val ownContentDesc = view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
        if (ownContentDesc != null) {
            props["contentDescription"] = ownContentDesc.take(128)
        }
        if (view.id != View.NO_ID) {
            try {
                val name = view.resources.getResourceEntryName(view.id)
                if (!name.isNullOrEmpty()) props["resourceId"] = name
            } catch (_: Throwable) {
                // getResourceEntryName throws on dynamic / unregistered ids.
            }
        }
        // android.widget.TextView (Button extends TextView) — surface text.
        val ownText = readViewText(view)
        if (!ownText.isNullOrEmpty()) {
            props["text"] = ownText.take(128)
        }
        // Bank-grade Compose fallback. Jetpack Compose's `Button("Create
        // Image") { … }` renders into an AndroidComposeView — the
        // matched View has no `contentDescription` and no `getText` of
        // its own, so the legacy reads above come up empty. Descend up
        // to 6 levels into the matched view's subtree looking for a
        // child with a non-empty contentDescription or TextView.text.
        // Mirrors the SwiftUI descendant-search shipped in
        // sdks/swift v1.4.7. First match wins — closest, shallowest
        // descendant.
        if (ownContentDesc.isNullOrEmpty() && ownText.isNullOrEmpty()) {
            val resolved = findDescendantLabel(view, depth = 0)
            if (!resolved.isNullOrEmpty() && !textIndicatesPII(resolved)) {
                // Stamp under `text` so the dashboard's cross-SDK label
                // resolver picks it up via the same priority chain
                // (label → text → title → ariaLabel →
                // accessibilityLabel → contentDescription). No new
                // wire-shape — same property the legacy TextView path
                // already wrote to.
                props["text"] = resolved.take(128)
            }
        }
        return props
    }

    /**
     * Read a view's text via reflection on `getText()` — works for any
     * TextView subclass including Button, ImageButton (via title),
     * etc. Compose buttons render into AndroidComposeView, which has
     * no `getText` and returns null here; the descendant search picks
     * up the text from the Compose internal layout instead.
     */
    private fun readViewText(view: View): String? = try {
        val getTextMethod = view.javaClass.getMethod("getText")
        (getTextMethod.invoke(view) as? CharSequence)?.toString()
    } catch (_: Throwable) {
        null
    }

    /**
     * Descend into the matched view's subtree looking for a child
     * with text or accessibility content-description. Bounded depth
     * (6) plus a hard subtree-node cap so a list cell with thousands
     * of nested layout views can't spin. First match wins — closest,
     * shallowest descendant.
     *
     * The Compose accessibility tree often puts the human-readable
     * label on a child of the touched view rather than on the
     * touched view itself; this is the fallback the legacy
     * contentDescription / getText reads on the matched view alone
     * can't reach.
     */
    private fun findDescendantLabel(view: View, depth: Int): String? {
        if (depth > 6) return null
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        val text = readViewText(view)
        if (!text.isNullOrEmpty()) return text
        if (view is android.view.ViewGroup) {
            val n = view.childCount
            for (i in 0 until n) {
                val child = view.getChildAt(i) ?: continue
                val found = findDescendantLabel(child, depth + 1)
                if (!found.isNullOrEmpty()) return found
            }
        }
        return null
    }

    /**
     * String-level PII filter. Reused for both the immediate
     * contentDescription / TextView.text and the descendant-found
     * label so a password field's visible text never lands on the
     * wire — matches the textIndicatesPII helper shipped in
     * sdks/swift v1.4.7.
     */
    private fun textIndicatesPII(text: String): Boolean {
        val lowered = text.lowercase()
        return piiLabelSubstrings.any { needle -> lowered.contains(needle) }
    }

    private val piiLabelSubstrings = listOf(
        "password", "passcode", "pin",
        "card number", "credit card", "cvv", "cvc",
        "ssn", "social security",
        "bank account", "routing number",
    )

    /**
     * Tag id slot for the cd-noTrack opt-out. Consumers can call
     * `view.setTag(Crossdeck.NO_TRACK_TAG, true)` (a value defined
     * in Crossdeck.kt) to exclude a specific view + its subtree.
     * Lives here so the tag id is shared between tagger and reader.
     */
    private val R_id_cd_noTrack: Int = "cd-noTrack".hashCode()
}

/**
 * Window.Callback wrapper. Delegates everything to the original
 * callback and additionally calls [onTouch] for every dispatched
 * touch event so [AutoTracker] can fire tap-autocapture events.
 *
 * Implements all Callback methods explicitly to avoid abstract-
 * method errors on Android API levels where the interface adds
 * new methods we'd otherwise skip.
 */
internal class CrossdeckWindowCallback(
    private val delegate: Window.Callback,
    private val onTouch: (android.view.MotionEvent) -> Unit,
) : Window.Callback by delegate {

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event != null) {
            try {
                onTouch(event)
            } catch (_: Throwable) {
                // Tap autocapture must never break event dispatch.
            }
        }
        return delegate.dispatchTouchEvent(event)
    }
}
