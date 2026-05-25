package com.crossdeck

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.math.min

/**
 * Performance vitals — Android counterpart of the Swift SDK's
 * MetricKit integration and the Web SDK's web-vitals module.
 *
 * Emits:
 *   * `perf.cold_launch_ms` once per process — measured from
 *     `Process.getStartElapsedRealtime()` to the first
 *     `ProcessLifecycleOwner` `onStart` (app foregrounded).
 *     Mirrors Apple's `MXAppLaunchMetric.histogrammedTimeToFirstDraw`
 *     intent.
 *   * `perf.anr` whenever the main thread blocks for >5 seconds
 *     without acknowledging a heartbeat from our watchdog. Defaults
 *     to Android's own ANR threshold (5s for foreground services
 *     / activities). On detection, we capture the main-thread stack
 *     trace and emit a single event — no double-fires.
 *   * `perf.frame_jank` aggregated counters of frames that took
 *     longer than 16ms / 700ms (slow + frozen, per Android's
 *     [JankStats] / Choreographer conventions). Reported via a
 *     periodic 60-second rollup so we don't spam one event per
 *     dropped frame.
 *
 * Off by default. Set [CrossdeckOptions.enablePerformanceMonitoring]
 * `= true` to install.
 */
internal class PerformanceVitals(
    private val application: Application,
    private val emit: (String, Map<String, Any?>) -> Unit,
) {

    private val lock = Any()
    private var installed = false
    private var anrWatchdog: AnrWatchdog? = null
    private var jankSampler: JankSampler? = null
    private var coldLaunchEmitted = false

    fun start() {
        synchronized(lock) {
            if (installed) return
            installed = true
        }
        installColdLaunchObserver()
        installAnrWatchdog()
        installJankSampler()
    }

    fun stop() {
        synchronized(lock) {
            if (!installed) return
            installed = false
        }
        anrWatchdog?.stop()
        anrWatchdog = null
        jankSampler?.stop()
        jankSampler = null
    }

    // ---- Cold launch ----

    private fun installColdLaunchObserver() {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (coldLaunchEmitted) return
                    coldLaunchEmitted = true
                    val elapsedMs = launchElapsedMs()
                    if (elapsedMs > 0) {
                        emit(
                            "perf.cold_launch_ms",
                            mapOf("durationMs" to elapsedMs),
                        )
                    }
                }
            })
        }
    }

    private fun launchElapsedMs(): Long {
        // Process.getStartElapsedRealtime is API 24+. Below that
        // we have no clean way to measure cold launch — return 0
        // so callers know to skip.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        val started = Process.getStartElapsedRealtime()
        val now = android.os.SystemClock.elapsedRealtime()
        return (now - started).coerceAtLeast(0)
    }

    // ---- ANR watchdog ----

    private fun installAnrWatchdog() {
        anrWatchdog = AnrWatchdog(emit).also { it.start() }
    }

    // ---- Frame jank ----

    private fun installJankSampler() {
        Handler(Looper.getMainLooper()).post {
            jankSampler = JankSampler(emit).also { it.start() }
        }
    }
}

/**
 * ANR watchdog. Posts a heartbeat to the main thread once per
 * second and checks from a background thread whether the heartbeat
 * is being acknowledged. If the main thread blocks for more than
 * 5 seconds without acking, we capture its stack and emit `perf.anr`.
 *
 * 5 seconds matches Android's foreground ANR threshold. We could
 * lower (Mixpanel uses 4s) but matching the OS-level ANR is the
 * cleanest signal for "the user already saw the ANR dialog."
 *
 * One emit per detected ANR — subsequent main-thread ticks reset
 * the watchdog. If the main thread comes back, we don't keep
 * firing.
 */
private class AnrWatchdog(
    private val emit: (String, Map<String, Any?>) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread
    private val anrThresholdMs = 5_000L
    private val checkIntervalMs = 1_000L

    @Volatile private var ticked: Boolean = true
    @Volatile private var running: Boolean = false
    @Volatile private var anrReported: Boolean = false

    private val watcher = Thread({
        while (running) {
            try {
                ticked = false
                mainHandler.post { ticked = true }
                Thread.sleep(anrThresholdMs)
                if (!ticked && !anrReported) {
                    anrReported = true
                    val stack = mainThread.stackTrace
                    val stackLines = stack.take(40).map { it.toString() }
                    emit(
                        "perf.anr",
                        mapOf(
                            "thresholdMs" to anrThresholdMs,
                            "stackTrace" to stackLines.joinToString("\n"),
                        ),
                    )
                }
                if (ticked) {
                    // ANR cleared on main-thread recovery — re-arm
                    // so the next stall is also reported.
                    anrReported = false
                }
                Thread.sleep(checkIntervalMs)
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Throwable) {
                // Never let the watchdog itself crash.
                Thread.sleep(checkIntervalMs)
            }
        }
    }, "crossdeck-anr-watchdog").apply {
        isDaemon = true
    }

    fun start() {
        running = true
        watcher.start()
    }

    fun stop() {
        running = false
        watcher.interrupt()
    }
}

/**
 * Frame jank sampler — Choreographer-based. Counts slow frames
 * (>16ms) and frozen frames (>700ms) per 60-second window, then
 * emits `perf.frame_jank` with the aggregate. Same shape as
 * Android Vitals' "Slow rendering" + "Frozen frames" metrics.
 */
private class JankSampler(
    private val emit: (String, Map<String, Any?>) -> Unit,
) : Choreographer.FrameCallback {

    private val frameNanos16ms = 16_000_000L
    private val frameNanos700ms = 700_000_000L
    private val rollupIntervalMs = 60_000L

    @Volatile private var running: Boolean = false
    private var lastFrameNanos: Long = 0
    private var slowFrameCount: Long = 0
    private var frozenFrameCount: Long = 0
    private var totalFrameCount: Long = 0
    private var windowStartMs: Long = 0

    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        running = true
        windowStartMs = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
        handler.postDelayed(::rollup, rollupIntervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            totalFrameCount++
            if (delta > frameNanos700ms) {
                frozenFrameCount++
            } else if (delta > frameNanos16ms) {
                slowFrameCount++
            }
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun rollup() {
        if (!running) return
        val total = totalFrameCount
        val slow = slowFrameCount
        val frozen = frozenFrameCount
        // Reset the window.
        slowFrameCount = 0
        frozenFrameCount = 0
        totalFrameCount = 0
        val windowMs = System.currentTimeMillis() - windowStartMs
        windowStartMs = System.currentTimeMillis()

        if (total > 0) {
            val slowPct = (slow * 100.0 / total).let { String.format("%.2f", it) }
            val frozenPct = (frozen * 100.0 / total).let { String.format("%.2f", it) }
            emit(
                "perf.frame_jank",
                mapOf(
                    "windowMs" to windowMs,
                    "totalFrames" to total,
                    "slowFrames" to slow,
                    "frozenFrames" to frozen,
                    "slowPct" to slowPct.toDouble(),
                    "frozenPct" to frozenPct.toDouble(),
                ),
            )
        }
        handler.postDelayed(::rollup, rollupIntervalMs)
    }
}
