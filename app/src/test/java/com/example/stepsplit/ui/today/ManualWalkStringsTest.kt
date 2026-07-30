package com.example.stepsplit.ui.today

import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Confirms real Hebrew (default) and English translations exist for the three new manual-walk
 * user-facing messages: automatic completion, the failed-completion (sync) error, and the
 * stale/zero-step recovery prompt.
 */
@RunWith(RobolectricTestRunner::class)
class ManualWalkStringsTest {

    private val ids = listOf(
        R.string.manual_walk_auto_completed_message,
        R.string.finish_walk_sync_failed_message,
        R.string.stale_walk_recovery_title,
        R.string.stale_walk_recovery_message,
        R.string.stale_walk_action_cancel,
        R.string.stale_walk_action_finish_now,
        R.string.stale_walk_action_keep_ongoing,
    )

    @Test
    fun `hebrew default strings exist for auto-completion, failed completion, and stale-walk recovery`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ids.forEach { id -> assertTrue(context.getString(id).isNotBlank()) }
    }

    /**
     * [Config.qualifiers] = "en" makes Robolectric resolve resources from `values-en/` for this
     * method. Asserting the exact expected English copy (rather than just non-blank) is what
     * actually catches a missing translation: a missing `values-en` entry would silently resolve
     * to the Hebrew default instead, which would fail these exact-text assertions.
     */
    @Config(qualifiers = "en")
    @Test
    fun `english strings exist and are real translations, not a silent fallback to hebrew`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals(
            "The walk ended automatically because no movement was detected.",
            context.getString(R.string.manual_walk_auto_completed_message),
        )
        assertEquals(
            "The walk couldn't be finished because step synchronization failed. Please try again.",
            context.getString(R.string.finish_walk_sync_failed_message),
        )
        assertEquals("No steps recorded for your active walk", context.getString(R.string.stale_walk_recovery_title))
        assertEquals("Cancel walk", context.getString(R.string.stale_walk_action_cancel))
        assertEquals("Finish now", context.getString(R.string.stale_walk_action_finish_now))
        assertEquals("Keep tracking", context.getString(R.string.stale_walk_action_keep_ongoing))
    }
}
