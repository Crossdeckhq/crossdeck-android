// SDK identifier constants — the wire `sdk.name` + `sdk.version`
// fields and the `Crossdeck-Sdk-Version` header are both built
// from these two literals. Keep in lockstep with the
// `version` field in publishing.publications.release in
// `crossdeck/build.gradle.kts`.
//
// Single source of truth: when bumping the SDK version, edit
// HERE first, then update the maven publication block.
// `scripts/sync-sdk-versions.mjs` reads this file.
package com.crossdeck

/** Package-level constants for the SDK identifier. */
public object Sdk {
    /**
     * Canonical SDK identifier — the `@cross-deck/android` surface
     * id is what backend `src/sdk/registry.ts` matches against to
     * route the per-SDK dashboard tile. Drift here breaks the
     * "Android SDK" install tile.
     */
    public const val NAME: String = "@cross-deck/android"
    public const val VERSION: String = "1.4.4"
}
