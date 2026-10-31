package com.example.stepsplit.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review 2, D5: a count in a sentence agrees with its number - the recording card showed "1 route
 * points recorded". Hebrew (the default) has a singular, a dual and a plural; English one and other.
 */
@RunWith(RobolectricTestRunner::class)
class CountPluralsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun quantity(id: Int, count: Int) = context.resources.getQuantityString(id, count, count)

    @Test
    @Config(qualifiers = "en")
    fun `English counts agree with their number`() {
        assertEquals("1 route point recorded", quantity(R.plurals.trip_points_count_label, 1))
        assertEquals("2 route points recorded", quantity(R.plurals.trip_points_count_label, 2))
        assertEquals("0 route points recorded", quantity(R.plurals.trip_points_count_label, 0))
        assertEquals("Daily goal progress, 1 percent", quantity(R.plurals.cd_daily_progress, 1))
        assertEquals("Weekly goal progress, 45 percent", quantity(R.plurals.cd_weekly_progress, 45))
    }

    @Test
    @Config(qualifiers = "iw")
    fun `Hebrew counts agree with their number, the dual included`() {
        assertEquals("נקודת מסלול אחת נרשמה", quantity(R.plurals.trip_points_count_label, 1))
        assertEquals("שתי נקודות מסלול נרשמו", quantity(R.plurals.trip_points_count_label, 2))
        assertEquals("7 נקודות מסלול נרשמו", quantity(R.plurals.trip_points_count_label, 7))
        assertEquals("התקדמות יעד יומי, אחוז אחד", quantity(R.plurals.cd_daily_progress, 1))
        assertEquals("התקדמות יעד שבועי, שני אחוזים", quantity(R.plurals.cd_weekly_progress, 2))
        assertEquals("התקדמות יעד יומי, 45 אחוזים", quantity(R.plurals.cd_daily_progress, 45))
    }
}
