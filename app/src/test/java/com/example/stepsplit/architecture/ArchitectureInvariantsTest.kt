package com.example.stepsplit.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static, file-scanning regression tests for two portability invariants the product requirement
 * for strict vehicle-aware step validation is explicit about, and that no amount of behavioral
 * testing alone would ever catch by itself: (1) no manufacturer/model branching anywhere - the
 * validator must work identically on a Galaxy A20 (Local Recording path), a Galaxy A55, and any
 * future device, and (2) the strict validator must never gain a raw-accelerometer fallback -
 * "the absence of vehicle detection is not proof of walking," and a raw accelerometer signal is
 * exactly the kind of unverified proxy that principle rules out. These are architecture
 * invariants a reviewer could otherwise silently violate one line at a time (e.g. `if
 * (Build.MODEL.contains("SM-A207"))` slipped into an unrelated fix); catching that requires
 * scanning actual source text, not just exercising behavior.
 *
 * Plain JVM file I/O, not Robolectric - there is nothing Android-specific about reading this
 * module's own `.kt` files from disk, and skipping the Robolectric bootstrap keeps this fast.
 */
class ArchitectureInvariantsTest {

    private val mainSourceRoot = File("src/main/java/com/example/stepsplit")

    private fun allKotlinFiles(): List<File> {
        check(mainSourceRoot.isDirectory) { "Expected to find $mainSourceRoot relative to the app module's working directory - test working directory assumption is wrong." }
        return mainSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no manufacturer or device-model brand-name references anywhere in main source`() {
        // Deliberately unambiguous brand tokens only (no generic English words like "pixel" that
        // collide with legitimate graphics-code usage, or short fragments like "oppo" that
        // substring-match inside ordinary words such as "opportunistic") - see this test's own
        // false-positive-driven tuning history.
        val forbiddenBrandTokens = listOf("samsung", "galaxy", "oneplus", "xiaomi", "huawei", "motorola")
        val violations = allKotlinFiles().flatMap { file ->
            val text = file.readText().lowercase()
            forbiddenBrandTokens.filter { text.contains(it) }.map { token -> "${file.path}: contains \"$token\"" }
        }
        assertTrue(
            "Found manufacturer/model brand-name references - validation must stay portable and " +
                "must never branch on a specific device, per the product requirement (Galaxy A20/A55 " +
                "are physical test targets only, never a code path):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `Build_MANUFACTURER and Build_BRAND are read only for passive debug display, never for branching`() {
        // DeviceDiagnostics.kt legitimately READS Build.MANUFACTURER for the debug diagnostics
        // panel's plain display text (see that class's own doc comment) - the invariant this
        // guards is that nothing else in the app ever reads it at all, since any other read is by
        // definition either dead code or the seed of a manufacturer-specific branch.
        val allowedFile = "DeviceDiagnostics.kt"
        val violations = allKotlinFiles()
            .filter { it.name != allowedFile }
            .filter { it.readText().contains("Build.MANUFACTURER") || it.readText().contains("Build.BRAND") }
            .map { it.path }
        assertTrue(
            "Build.MANUFACTURER/Build.BRAND referenced outside the debug-display-only allowlist ($allowedFile): $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `no raw accelerometer sensor type is referenced anywhere in main source`() {
        // The product requirement is explicit: never add a raw-accelerometer fallback. Every
        // sensor type below would let a future edit reintroduce exactly that as a "just in case"
        // fallback for when Activity Recognition evidence is unavailable - which the strict
        // validator must never do (report unavailable instead, per StrictStepValidationPolicy's
        // own doc comment). TYPE_STEP_COUNTER/TYPE_STEP_DETECTOR (a genuinely different, already
        // source-agnostic sensor family) are unaffected and continue to be used for passive
        // presence-only diagnostics in DeviceDiagnostics.kt.
        val forbiddenSensorTypes = listOf("TYPE_ACCELEROMETER", "TYPE_LINEAR_ACCELERATION", "TYPE_GRAVITY")
        val violations = allKotlinFiles().flatMap { file ->
            val text = file.readText()
            forbiddenSensorTypes.filter { text.contains(it) }.map { type -> "${file.path}: references $type" }
        }
        assertTrue(
            "Raw accelerometer-family sensor types must never appear anywhere in main source " +
                "(no accelerometer fallback, ever):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `domain-validation package stays pure Kotlin - no Android or GMS import`() {
        // StrictStepValidationPolicy and IntervalReconstructor both document themselves as pure,
        // no-Android decision engines (every timestamp/evidence value is passed in explicitly) -
        // this is what makes them independently unit-testable and source-independent (a future
        // Sensor.TYPE_STEP_COUNTER source passes through the identical validator). An Android or
        // GMS import creeping in here would be the first sign of that boundary eroding.
        val validationDir = File(mainSourceRoot, "domain/validation")
        check(validationDir.isDirectory) { "Expected to find $validationDir" }
        val violations = validationDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { line ->
                        val trimmed = line.trimStart()
                        trimmed.startsWith("import android.") || trimmed.startsWith("import com.google.android.gms")
                    }
                    .map { line -> "${file.name}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "domain/validation must stay pure Kotlin with no Android/GMS dependency:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
