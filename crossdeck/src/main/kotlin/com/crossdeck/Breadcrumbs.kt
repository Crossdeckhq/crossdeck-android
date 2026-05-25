// Bounded ring buffer for context-leading-up-to-an-error.
//
// When an error is captured the last N breadcrumbs ship with it
// so the consumer can reconstruct the path the user took to hit
// the failure. Bounded so a long-running app session can't
// accumulate unbounded memory; ring semantics so older context
// is dropped without a linear shift cost.
//
// `synchronized` block guards mutations; reads return a defensive
// copy so the caller observes the buffer state at the moment of
// the read even if it continues to mutate afterwards.

package com.crossdeck

public enum class BreadcrumbCategory(public val wireValue: String) {
    UI("ui"),
    HTTP("http"),
    LIFECYCLE("lifecycle"),
    ERROR("error"),
    IDENTITY("identity"),
    CUSTOM("custom"),
}

public enum class BreadcrumbLevel(public val wireValue: String) {
    DEBUG("debug"),
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
}

public data class Breadcrumb(
    val timestampMs: Long = System.currentTimeMillis(),
    val category: BreadcrumbCategory,
    val level: BreadcrumbLevel = BreadcrumbLevel.INFO,
    val message: String,
    val data: Map<String, String>? = null,
)

/** Default cap. 50 mirrors Web/Node/RN/Swift. */
public const val DEFAULT_BREADCRUMB_CAPACITY: Int = 50

public class Breadcrumbs(public val capacity: Int = DEFAULT_BREADCRUMB_CAPACITY) {
    init {
        require(capacity > 0) { "Breadcrumb capacity must be > 0" }
    }

    private val lock = Any()
    private val buffer: ArrayDeque<Breadcrumb> = ArrayDeque(capacity)

    public fun add(crumb: Breadcrumb) {
        synchronized(lock) {
            while (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(crumb)
        }
    }

    public fun snapshot(): List<Breadcrumb> = synchronized(lock) { buffer.toList() }

    public fun clear() {
        synchronized(lock) { buffer.clear() }
    }
}
