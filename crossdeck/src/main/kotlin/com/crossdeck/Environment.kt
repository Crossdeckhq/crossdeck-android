package com.crossdeck

/**
 * Environment declaration. Must match the `publicKey` prefix —
 * `cd_pub_live_…` ↔ `[Environment.PRODUCTION]`,
 * `cd_pub_test_…` ↔ `[Environment.SANDBOX]`. Mismatch is rejected
 * at [Crossdeck.init] so a typo'd build configuration can't
 * silently route production telemetry into sandbox dashboards.
 */
public enum class Environment(public val wireValue: String) {
    PRODUCTION("production"),
    SANDBOX("sandbox"),
    ;

    public companion object {
        public fun fromWire(value: String?): Environment? = when (value) {
            "production" -> PRODUCTION
            "sandbox" -> SANDBOX
            else -> null
        }
    }
}
