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

    /**
     * Step acquisition/repository/classification code - everything automatic step counting
     * touches - must never depend on the manually-started GPS trip feature. See the product
     * requirement: step counting must never depend on GPS, trip state, location permission,
     * location availability, route points, distance, or `TripRecordingService`.
     */
    private val stepSourceDirs = listOf(
        "data/repository",
        "data/stepsource",
        "data/local/bucket",
        "data/local/bout",
        "data/local/override",
        "domain/classification",
        "domain/aggregation",
        "domain/stats",
    )

    /** GPS trip recording code - everything manually-started trip recording touches - must never depend on step data. */
    private val gpsDirs = listOf(
        "data/trip",
        "trip/service",
        "domain/trip",
        "data/local/trip",
    )

    private fun importLines(dirs: List<String>): List<Pair<File, String>> = dirs.flatMap { dir ->
        val root = File(mainSourceRoot, dir)
        if (!root.isDirectory) return@flatMap emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map { it.trimStart() }
                    .filter { it.startsWith("import ") }
                    .map { file to it }
            }
            .toList()
    }

    @Test
    fun `step acquisition and repository code never imports the GPS trip feature`() {
        val forbiddenPrefixes = listOf(
            "import com.example.stepsplit.data.trip",
            "import com.example.stepsplit.trip.service",
            "import com.example.stepsplit.domain.trip",
            "import com.example.stepsplit.data.local.trip",
            "import com.google.android.gms.location.FusedLocationProviderClient",
        )
        val violations = importLines(stepSourceDirs)
            .filter { (_, line) -> forbiddenPrefixes.any { line.startsWith(it) } }
            .map { (file, line) -> "${file.path}: $line" }
        assertTrue(
            "Step acquisition/repository code must never depend on the GPS trip feature (see the product requirement that step counting is completely independent of GPS):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `step acquisition and repository code never references location permissions or FusedLocationProviderClient`() {
        val forbiddenTokens = listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "FusedLocationProviderClient")
        val violations: List<String> = stepSourceDirs.flatMap { dir ->
            val root = File(mainSourceRoot, dir)
            if (!root.isDirectory) return@flatMap emptyList<String>()
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                val text = file.readText()
                forbiddenTokens.filter { text.contains(it) }.map { token -> "${file.path}: references $token" }
            }.toList()
        }
        assertTrue(
            "Step acquisition/repository code must never reference location permissions - opening Today must never request location:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `GPS trip code never imports step repository, step source, or step-classification tables`() {
        val forbiddenPrefixes = listOf(
            "import com.example.stepsplit.data.repository",
            "import com.example.stepsplit.data.stepsource",
            "import com.example.stepsplit.data.local.bucket",
            "import com.example.stepsplit.data.local.bout",
            "import com.example.stepsplit.data.local.override",
            "import com.example.stepsplit.domain.classification",
        )
        val violations = importLines(gpsDirs)
            .filter { (_, line) -> forbiddenPrefixes.any { line.startsWith(it) } }
            .map { (file, line) -> "${file.path}: $line" }
        assertTrue(
            "GPS trip code must never depend on step data/repository/classification (see the product requirement that GPS never reads, writes, or influences steps):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
