// Compose helper — Stripe-grade ergonomic surface for the SDK inside
// the @Composable tree.
//
// Why this file exists:
//
//   Jetpack Compose hides the navigation graph behind pure functions —
//   there are no Activity / Fragment lifecycle hooks for the SDK's
//   Activity.onResume swizzle to fire on, so a pure-Compose host
//   (single MainActivity, NavHost destinations) emits zero
//   `page.viewed` events. Mirrors the SwiftUI gap on iOS: Apple's
//   UIHostingController is on our denylist for exactly the same
//   reason.
//
//   This file ships the canonical bolt-on: a `Modifier.crossdeckScreen`
//   extension and a `CrossdeckScreen` wrapper composable that fire
//   `page.viewed` once per screen-enter, with the human-readable name
//   the dashboard's Pages tab groups on.
//
// Scope: Compose is `compileOnly` on the SDK module — non-Compose
// consumers never pull the dependency. Hosts that already depend on
// Compose get the helpers for free.

package com.crossdeck.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import com.crossdeck.Crossdeck

/**
 * Fire a `page.viewed` event when this composable enters the
 * composition.
 *
 * Compose destinations don't surface a stable class name to the
 * Android SDK's `Activity.onResume` swizzle (the host is always
 * `MainActivity` / `ComponentActivity`, which the auto-track
 * denylist rightly skips). This modifier is how a pure-Compose app
 * tells the dashboard "the user just landed on this screen" without
 * going through `cd.track(...)` by hand at every NavHost destination.
 *
 * Usage:
 *
 * ```kotlin
 * composable("create_image") {
 *     CreateImageScreen(
 *         modifier = Modifier.crossdeckScreen(crossdeck, "Create Image"),
 *     )
 * }
 * ```
 *
 * For a wrapper-style API (no Modifier plumbing), use
 * [CrossdeckScreen] instead.
 *
 * @param crossdeck The active SDK instance. Pass via your DI graph
 *                  or the singleton accessor you wired up at boot
 *                  (e.g. `MyApplication.crossdeck`). Pass `null`
 *                  when the SDK didn't start — the modifier is a
 *                  no-op so the host app never crashes on a
 *                  misconfigured key.
 * @param name      Human-readable screen name. Shown verbatim on the
 *                  Pages dashboard — keep it short and stable across
 *                  releases ("Create Image", not "CreateImageNavV3").
 *                  Truncated to 128 chars to match the host-side
 *                  title cap.
 */
public fun Modifier.crossdeckScreen(
    crossdeck: Crossdeck?,
    name: String,
): Modifier = composed {
    LaunchedEffect(name) {
        emitScreenView(crossdeck, name)
    }
    this
}

/**
 * Wrapper composable that fires `page.viewed` for the duration of
 * its content. Equivalent to applying [Modifier.crossdeckScreen] at
 * the root of [content]; the wrapper form is more readable when
 * a screen has no Modifier chain.
 *
 * Usage:
 *
 * ```kotlin
 * composable("create_image") {
 *     CrossdeckScreen(crossdeck = MyApplication.crossdeck, name = "Create Image") {
 *         CreateImageScreen()
 *     }
 * }
 * ```
 */
@Composable
public fun CrossdeckScreen(
    crossdeck: Crossdeck?,
    name: String,
    content: @Composable () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, name) {
        // Fire on first composition; future re-compositions of the
        // SAME screen (state changes inside the screen) don't re-fire
        // because DisposableEffect keys on `name`.
        emitScreenView(crossdeck, name)

        // Re-fire on RESUMED transitions so a Compose host that pauses
        // on background and resumes still counts the screen visit.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                emitScreenView(crossdeck, name)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    content()
}

private fun emitScreenView(crossdeck: Crossdeck?, name: String) {
    val cd = crossdeck ?: return
    val trimmed = if (name.length > 128) name.substring(0, 128) else name
    // Match the Web/UIKit `page.viewed` shape so the Pages dashboard
    // groups them uniformly. `screen` is the canonical mobile-screen
    // key the backend groups on when url/path is absent; `title`
    // mirrors what Web auto-track sends so the dashboard's
    // title-first GA-style display has a value to render.
    cd.track(
        "page.viewed",
        mapOf(
            "screen" to trimmed,
            "title" to trimmed,
        ),
    )
}
