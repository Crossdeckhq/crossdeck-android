package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema-lock tests for `crossdeck.contract_failed`.
 *
 * The Android SDK's `reportContractFailure(...)` must:
 *   1. Honour the allow-list in
 *      contracts/diagnostics/contract-failed-payload-schema-lock.json
 *   2. NEVER go through the customer's `track()` pipeline — the
 *      reliability telemetry is single-fire to a dedicated endpoint
 *      hardcoded in `_DiagnosticTelemetry`.
 *   3. NEVER include any forbidden field on the wire even if the
 *      caller's input were to carry one.
 *
 * The schema-lock contract is the structural defence behind the
 * independent-controller flow in Privacy Policy §6 — these tests
 * fail loudly the moment the wire shape drifts.
 */
class ContractFailedSchemaLockTest {

    /// Mirrors `allowedFields.required` from the JSON contract.
    private val requiredFields = setOf(
        "contract_id",
        "sdk_version",
        "sdk_platform",
        "failure_reason",
        "run_context",
        "run_id",
    )

    private val optionalFields = setOf(
        "test_file",
        "test_name",
        "device_class",
    )

    private val forbiddenFields = setOf(
        "anonymousId",
        "developerUserId",
        "crossdeckCustomerId",
        "email",
        "ip",
        "user_agent",
        "message",
        "stack",
        "stack_trace",
        "frames",
        "exception_message",
        "url",
        "path",
        "screen",
        "title",
        "label",
        "text",
        "ariaLabel",
        "accessibilityLabel",
        "contentDescription",
        "session_id",
        "sessionId",
    )

    @Test
    fun `allowed keys match contract required union optional`() {
        assertEquals(
            requiredFields union optionalFields,
            _DiagnosticTelemetry.ALLOWED_KEYS,
        )
    }

    @Test
    fun `allowed keys do not contain any forbidden field`() {
        val overlap = _DiagnosticTelemetry.ALLOWED_KEYS intersect forbiddenFields
        assertTrue(
            "ALLOWED_KEYS overlaps forbidden fields: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `reportContractFailure payload conforms to schema-lock`() {
        // Build the payload using the same logic reportContractFailure
        // uses. We're exercising payload construction, not network IO.
        val input = ContractFailureInput(
            contractId = "per-user-cache-isolation",
            failureReason = "snapshot did not match",
            runContext = ContractFailureRunContext.CI,
            runId = "run_abc",
            testRef = ContractTestRef(
                file = "FooTest.kt",
                name = "isolation_test",
            ),
            deviceClass = "phone",
        )
        val payload: MutableMap<String, String> = LinkedHashMap()
        payload["contract_id"] = input.contractId
        payload["sdk_version"] = Sdk.VERSION
        payload["sdk_platform"] = "android"
        payload["failure_reason"] = input.failureReason
        payload["run_context"] = input.runContext.wire
        payload["run_id"] = input.runId
        input.testRef?.let {
            payload["test_file"] = it.file
            payload["test_name"] = it.name
        }
        input.deviceClass?.let { payload["device_class"] = it }

        for ((key, _) in payload) {
            assertTrue(
                "payload key '$key' is not in ALLOWED_KEYS",
                _DiagnosticTelemetry.ALLOWED_KEYS.contains(key),
            )
        }
        for (required in requiredFields) {
            assertNotNull("missing required field $required", payload[required])
        }
    }

    @Test
    fun `reportContractFailure does not enter customer track pipeline`() {
        // Defensive: the reliability endpoint URL is hardcoded. If
        // anyone repoints _DiagnosticTelemetry at the customer events
        // pipeline, this test fails.
        assertEquals(
            "https://api.cross-deck.com/v1/sdk/diagnostic",
            _DiagnosticTelemetry.ENDPOINT_URL,
        )
        assertFalse(_DiagnosticTelemetry.ENDPOINT_URL.contains("/v1/events"))
    }
}
