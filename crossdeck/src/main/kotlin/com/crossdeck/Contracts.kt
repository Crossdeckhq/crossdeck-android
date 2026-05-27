// Public, typed accessor for the bank-grade behavioural contracts
// this SDK ships. The full architecture — schema, distribution,
// audit loop, pillar taxonomy — lives in `contracts/README.md`
// at the monorepo root.
//
// Why a typed surface (vs. raw JSON access): contract IDs and
// pillar names are part of Crossdeck's public commitment to
// customers. Reading them through `CrossdeckContracts` means the
// compiler catches drift the moment a contract is renamed or
// retired. Tools that consume contracts at runtime (dashboards,
// AI assistants, customer integration tests) get the exact same
// shape every SDK ships, with no parsing layer to drift.
//
// --- BINARY STABILITY ---
// `Contract` is treated as an evolving — but back-compat — wire
// shape. Fields may be added in any minor release. Existing
// fields will not be removed or repurposed except in a major
// version bump, even if all known contracts stop using them.
// Customers can rely on `id`, `pillar`, `status`, `appliesTo`,
// `codeRef`, `testRef`, `registeredAt`, `firstRegisteredIn`,
// and `bundledIn` being present on every contract in every
// future minor/patch release of this SDK.

package com.crossdeck

import org.json.JSONObject

/**
 * Which bank-grade pillar a contract belongs to. The taxonomy is
 * deliberately small — every contract maps to exactly one. New
 * pillars require a Crossdeck major-version bump.
 */
public enum class ContractPillar(public val wire: String) {
    REVENUE("revenue"),
    ENTITLEMENTS("entitlements"),
    ANALYTICS("analytics"),
    WEBHOOKS("webhooks"),
    ERRORS("errors"),
    LIFECYCLE("lifecycle"),
    IDENTITY("identity");

    public companion object {
        public fun fromWire(value: String): ContractPillar =
            entries.firstOrNull { it.wire == value }
                ?: error("Unknown ContractPillar wire value: $value")
    }
}

/**
 * Lifecycle stage of a contract.
 * - `ENFORCED`: live in this SDK and exercised by `testRef`.
 * - `PROPOSED`: registered for an upcoming release; `testRef`
 *    may point to a not-yet-existing file.
 * - `RETIRED`: kept for history only; filtered out of `all()`.
 */
public enum class ContractStatus(public val wire: String) {
    ENFORCED("enforced"),
    PROPOSED("proposed"),
    RETIRED("retired");

    public companion object {
        public fun fromWire(value: String): ContractStatus =
            entries.firstOrNull { it.wire == value }
                ?: error("Unknown ContractStatus wire value: $value")
    }
}

/** Which SDKs (and/or `backend`) a contract is binding on. */
public enum class ContractAppliesTo(public val wire: String) {
    WEB("web"),
    NODE("node"),
    REACT_NATIVE("react-native"),
    SWIFT("swift"),
    ANDROID("android"),
    BACKEND("backend");

    public companion object {
        public fun fromWire(value: String): ContractAppliesTo =
            entries.firstOrNull { it.wire == value }
                ?: error("Unknown ContractAppliesTo wire value: $value")
    }
}

/**
 * Pointer to the test that exercises a contract clause. The
 * `name` is matched verbatim against the file's text by
 * `scripts/contract-audit.mjs`, so a rename without updating
 * the contract aborts CI.
 */
public data class ContractTestRef(
    public val file: String,
    public val name: String,
)

/**
 * One bank-grade behavioural guarantee — see `contracts/README.md`.
 *
 * Binary stability: this data class is a public, back-compat
 * wire shape. Fields may be added in any minor release. Existing
 * fields will not be removed or repurposed except in a major
 * version bump. Treat it as a stable customer surface — your
 * code can `when` over `status`, filter by `pillar`, or assert
 * `id` strings, and that behaviour will not break under SDK
 * patch/minor updates.
 */
public data class Contract(
    public val id: String,
    public val pillar: ContractPillar,
    public val status: ContractStatus,
    public val claim: String,
    public val appliesTo: List<ContractAppliesTo>,
    public val codeRef: List<String>,
    public val testRef: List<ContractTestRef>,
    /** ISO-8601 date the contract was first registered. */
    public val registeredAt: String,
    /** The release note / phase the contract first appeared in. Immutable. */
    public val firstRegisteredIn: String,
    /** The SDK release this snapshot was bundled with, stamped at build time. */
    public val bundledIn: String,
)

/**
 * Where a contract failure was observed. Wire vocabulary matches
 * Web/Node/RN/Swift so dashboard queries on `run_context` collapse
 * cleanly across platforms.
 */
public enum class ContractFailureRunContext(public val wire: String) {
    CI("ci"),
    DOGFOOD("dogfood"),
    CUSTOMER_APP("customer-app"),
}

/**
 * Input to [Crossdeck.reportContractFailure]. Mirrors the per-SDK
 * shape exactly — the Crossdeck dashboard joins
 * `crossdeck.contract_failed` events across every SDK on
 * `contract_id`, so the property bag has to agree.
 *
 * SCHEMA-LOCK: this class's field set is exhaustively named. No
 * free-form `extra: Map<String, Any?>?` — the schema-lock contract
 * at `contracts/diagnostics/contract-failed-payload-schema-lock.json`
 * forbids unbounded fields. Adding a field requires a PR that
 * amends the contract first, then the public data class.
 */
public data class ContractFailureInput(
    /** Stable contract id (`per-user-cache-isolation` etc.). */
    public val contractId: String,
    /**
     * Short categorical-ish label — the SDK convention is to keep
     * this under 128 chars and stable across runs (so dashboards can
     * group). Never an end-user-supplied string.
     */
    public val failureReason: String,
    public val runContext: ContractFailureRunContext,
    /** Stable identifier for this verification run. */
    public val runId: String,
    /** Optional pointer back to the failing test, for triage. */
    public val testRef: ContractTestRef? = null,
    /**
     * Optional coarse device class, e.g. "phone", "tablet", "tv",
     * "emulator". A categorical bucket, not a device identifier.
     */
    public val deviceClass: String? = null,
)

private data class LoadedBundle(
    val sdkVersion: String,
    val bundledIn: String,
    val contracts: List<Contract>,
)

/**
 * Typed entry point to the bank-grade contracts bundled with this
 * SDK release. Stable, side-effect-free, lazy-loaded once from
 * the JAR resource `crossdeck/contracts.json` (packaged via the
 * gradle `emitContracts` task before every build).
 *
 * ```kotlin
 * import com.crossdeck.CrossdeckContracts
 *
 * for (contract in CrossdeckContracts.all()) {
 *     Log.i("crossdeck", "${contract.id} (${contract.pillar.wire})")
 * }
 *
 * val isolation = CrossdeckContracts.byId("per-user-cache-isolation")
 *     ?: error("entitlement-isolation contract missing")
 * check(isolation.status == ContractStatus.ENFORCED)
 * ```
 */
public object CrossdeckContracts {
    private val loaded: LoadedBundle by lazy { loadFromResource() }

    /** Every contract that applies to this SDK and is currently enforced. */
    public fun all(): List<Contract> =
        loaded.contracts.filter { it.status == ContractStatus.ENFORCED }

    /**
     * Every contract bundled with this SDK release, including
     * `PROPOSED` and `RETIRED` entries. Use `all()` for the
     * enforced-only view.
     */
    public fun allIncludingHistorical(): List<Contract> = loaded.contracts

    /** Look up a contract by its stable `id`. */
    public fun byId(id: String): Contract? =
        loaded.contracts.firstOrNull { it.id == id }

    /** Every enforced contract within a pillar. */
    public fun byPillar(pillar: ContractPillar): List<Contract> =
        loaded.contracts.filter {
            it.pillar == pillar && it.status == ContractStatus.ENFORCED
        }

    /** Filter by lifecycle status. */
    public fun withStatus(status: ContractStatus): List<Contract> =
        loaded.contracts.filter { it.status == status }

    /** Semver of the SDK release these contracts were bundled with. */
    public val sdkVersion: String get() = loaded.sdkVersion

    /** Fully-qualified bundle identifier — e.g. `com.crossdeck:crossdeck:1.4.1`. */
    public val bundledIn: String get() = loaded.bundledIn

    /**
     * Resolve a failing test back to the contract it exercises.
     * Used by JUnit `TestWatcher` hooks to find the contract id of
     * a failed contract test so [Crossdeck.reportContractFailure]
     * can stamp the right `contract_id` on the emitted event.
     */
    public fun findByTestName(name: String): Contract? =
        loaded.contracts.firstOrNull { c -> c.testRef.any { it.name == name } }

    // -- Resource loader ----------------------------------------------------

    private fun loadFromResource(): LoadedBundle {
        val stream = CrossdeckContracts::class.java
            .getResourceAsStream("/crossdeck/contracts.json")
            ?: error(
                "Crossdeck: crossdeck/contracts.json missing from the SDK JAR. " +
                    "Run `node sdks/android/scripts/emit-contracts.mjs` and rebuild.",
            )
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val sdkVersion = root.getString("sdkVersion")
        val bundledIn = root.getString("bundledIn")
        val arr = root.getJSONArray("contracts")
        val list = ArrayList<Contract>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(parseContract(arr.getJSONObject(i)))
        }
        return LoadedBundle(sdkVersion, bundledIn, list)
    }

    private fun parseContract(o: JSONObject): Contract {
        val appliesArr = o.getJSONArray("appliesTo")
        val applies = ArrayList<ContractAppliesTo>(appliesArr.length())
        for (i in 0 until appliesArr.length()) {
            applies.add(ContractAppliesTo.fromWire(appliesArr.getString(i)))
        }
        val codeArr = o.getJSONArray("codeRef")
        val codes = ArrayList<String>(codeArr.length())
        for (i in 0 until codeArr.length()) codes.add(codeArr.getString(i))
        val testArr = o.getJSONArray("testRef")
        val tests = ArrayList<ContractTestRef>(testArr.length())
        for (i in 0 until testArr.length()) {
            val t = testArr.getJSONObject(i)
            tests.add(ContractTestRef(file = t.getString("file"), name = t.getString("name")))
        }
        return Contract(
            id = o.getString("id"),
            pillar = ContractPillar.fromWire(o.getString("pillar")),
            status = ContractStatus.fromWire(o.getString("status")),
            claim = o.getString("claim"),
            appliesTo = applies,
            codeRef = codes,
            testRef = tests,
            registeredAt = o.getString("registeredAt"),
            firstRegisteredIn = o.getString("firstRegisteredIn"),
            bundledIn = o.getString("bundledIn"),
        )
    }
}
