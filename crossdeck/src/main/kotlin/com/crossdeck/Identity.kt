// Identity holder.
//
// Owns the lifecycle of THREE canonical identity primitives across
// process restarts:
//
//   * `anonymousId` — device handle, minted on first launch,
//     persisted, regenerated only by reset(). Carries the
//     canonical `anon_` prefix that the backend `ANON_ID_PATTERN`
//     regex validates.
//
//   * `developerUserId` — the consumer's auth-provider user ID
//     (Firebase Auth `uid`, etc.). Set via identify(userId:...).
//     NEVER confuse with crossdeckCustomerId — different concepts.
//
//   * `crossdeckCustomerId` — Crossdeck's canonical record handle
//     (`cdcust_…`). Returned from /identity/alias and persisted so
//     subsequent events ship the canonical cdcust on the wire.
//
// Synchronised mutations. The public reads return immutable
// IdentitySnapshot snapshots — safe to hand across threads.

package com.crossdeck

import java.util.UUID

public data class IdentitySnapshot(
    val anonymousId: String,
    val developerUserId: String?,
    val crossdeckCustomerId: String?,
)

public class Identity(private val storage: KeyValueStorage) {
    /** Storage keys — match Web/RN/Swift exactly so cross-SDK migration tools work. */
    private val anonymousIdKey: String = "anon_id"
    private val developerUserIdKey: String = "developer_user_id"
    private val crossdeckCustomerIdKey: String = "cdcust_id"

    private val lock = Any()
    private var anonymousId: String
    private var developerUserId: String?
    private var crossdeckCustomerId: String?

    init {
        val stored = storage.getString(anonymousIdKey)
        anonymousId = if (!stored.isNullOrEmpty()) {
            stored
        } else {
            val fresh = makeAnonymousId()
            storage.setString(anonymousIdKey, fresh)
            fresh
        }
        developerUserId = storage.getString(developerUserIdKey)?.takeUnless { it.isEmpty() }
        crossdeckCustomerId = storage.getString(crossdeckCustomerIdKey)?.takeUnless { it.isEmpty() }
    }

    public fun snapshot(): IdentitySnapshot = synchronized(lock) {
        IdentitySnapshot(anonymousId, developerUserId, crossdeckCustomerId)
    }

    /** Returns true if the value changed. Idempotent — same id is a no-op. */
    public fun setDeveloperUserId(id: String?): Boolean = synchronized(lock) {
        val normalised = id?.trim()?.takeUnless { it.isEmpty() }
        if (normalised == developerUserId) return@synchronized false
        developerUserId = normalised
        if (normalised != null) {
            storage.setString(developerUserIdKey, normalised)
        } else {
            storage.remove(developerUserIdKey)
        }
        true
    }

    /**
     * Set the canonical Crossdeck customer id (cdcust_…). Called
     * from the /identity/alias response handler — the server
     * returns the cdcust and we persist it for the lifetime of
     * the install.
     */
    public fun setCrossdeckCustomerId(id: String?): Boolean = synchronized(lock) {
        val normalised = id?.trim()?.takeUnless { it.isEmpty() }
        if (normalised == crossdeckCustomerId) return@synchronized false
        crossdeckCustomerId = normalised
        if (normalised != null) {
            storage.setString(crossdeckCustomerIdKey, normalised)
        } else {
            storage.remove(crossdeckCustomerIdKey)
        }
        true
    }

    /**
     * Reset clears developerUserId + crossdeckCustomerId AND
     * regenerates the anonymousId. Used after sign-out so the
     * next anonymous session is fully unlinked from the prior
     * identified user.
     */
    public fun reset() {
        synchronized(lock) {
            developerUserId = null
            crossdeckCustomerId = null
            storage.remove(developerUserIdKey)
            storage.remove(crossdeckCustomerIdKey)
            val fresh = makeAnonymousId()
            storage.setString(anonymousIdKey, fresh)
            anonymousId = fresh
        }
    }

    /**
     * UUIDv4 with the canonical `anon_` prefix the backend regex
     * (ANON_ID_PATTERN in backend/src/api/v1-events-validation.ts)
     * validates against.
     */
    private fun makeAnonymousId(): String =
        "anon_" + UUID.randomUUID().toString().lowercase().replace("-", "")
}
