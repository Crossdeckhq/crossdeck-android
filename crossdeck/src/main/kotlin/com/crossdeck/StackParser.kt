// Throwable stack-trace normaliser.
//
// Android stack frames come as `StackTraceElement` objects with
// declaringClass / methodName / fileName / lineNumber. For the
// server-side error grouper to fingerprint correctly across
// launches (ASLR is not a factor on the JVM, but line numbers
// shift with R8 / ProGuard minification), we normalise to a stable
// shape:
//
//   "<module> <symbol>"
//
// where `module` is the class's package and `symbol` is
// `ClassName.methodName`. Line numbers are recorded separately so
// a fingerprint can elect to include or omit them — the default
// (matches Web/Node/RN/Swift) is top-N module:symbol joined with
// `|`, no line numbers, so a refactor that moves code within a
// method doesn't re-group the error.

package com.crossdeck

public data class ParsedStackFrame(
    public val module: String,
    public val symbol: String,
    public val frameNumber: Int,
    public val fileName: String? = null,
    public val lineNumber: Int? = null,
)

/**
 * Parse a [Throwable]'s stack trace into the canonical
 * [ParsedStackFrame] shape. Skips any frames the JVM marks as
 * native (no class/method context) so a single missing entry
 * doesn't drop the whole trace.
 */
public fun parseStackTrace(elements: Array<StackTraceElement>): List<ParsedStackFrame> {
    val out = ArrayList<ParsedStackFrame>(elements.size)
    elements.forEachIndexed { index, el ->
        val className = el.className ?: return@forEachIndexed
        val methodName = el.methodName ?: return@forEachIndexed
        if (className.isEmpty() || methodName.isEmpty()) return@forEachIndexed

        // Module = package (everything up to the last `.`).
        // Symbol = `SimpleClassName.methodName`. Inner classes
        // like `Outer$Inner` are preserved on the simple-name
        // side so the dashboard can render them readably.
        val lastDot = className.lastIndexOf('.')
        val module = if (lastDot > 0) className.substring(0, lastDot) else "<default>"
        val simpleName = if (lastDot > 0) className.substring(lastDot + 1) else className
        val symbol = "$simpleName.$methodName"
        val lineNo = el.lineNumber.takeIf { it >= 0 }

        out += ParsedStackFrame(
            module = module,
            symbol = symbol,
            frameNumber = index,
            fileName = el.fileName,
            lineNumber = lineNo,
        )
    }
    return out
}

/**
 * Build a fingerprint string from a [Throwable]. Format mirrors
 * Web/Node/RN/Swift exactly: top-N frames concatenated as
 * `module:symbol` separated by `|` — server-side grouper hashes
 * on the same shape regardless of platform.
 *
 * Default depth (5) matches the cross-SDK contract. Going deeper
 * makes the fingerprint over-specific (every call-site looks
 * unique); going shallower makes it under-specific (unrelated
 * errors collapse into one group).
 */
public fun fingerprintFromStack(throwable: Throwable, depth: Int = 5): String {
    val frames = parseStackTrace(throwable.stackTrace).take(depth)
    if (frames.isEmpty()) {
        // No stack — fall back to type + message-class so we
        // still get a stable bucket for fully-native throws.
        val typeName = throwable::class.qualifiedName ?: throwable::class.java.name
        return "$typeName:no_stack"
    }
    return frames.joinToString("|") { "${it.module}:${it.symbol}" }
}
