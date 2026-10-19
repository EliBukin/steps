package com.example.stepsplit.ui.common

import com.example.stepsplit.data.stepsource.StepSourceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which button the Today banner shows: Health Connect's dialog first, its own screen after a denial. */
class StepPermissionActionTest {

    @Test
    fun `without the permission the banner first offers Health Connect's dialog`() {
        assertEquals(
            StepPermissionAction.GRANT_PERMISSION,
            stepPermissionAction(StepSourceAvailability.PermissionNotGranted, permissionRequestDenied = false),
        )
    }

    @Test
    fun `after a denied request the banner offers Health Connect's own screen`() {
        assertEquals(
            StepPermissionAction.OPEN_HEALTH_CONNECT,
            stepPermissionAction(StepSourceAvailability.PermissionNotGranted, permissionRequestDenied = true),
        )
    }

    @Test
    fun `once steps can be read there is nothing to offer, even after a denial`() {
        assertNull(stepPermissionAction(StepSourceAvailability.Available, permissionRequestDenied = true))
        assertNull(stepPermissionAction(StepSourceAvailability.Available, permissionRequestDenied = false))
    }

    @Test
    fun `other problems than the permission get no permission button`() {
        val others = listOf(
            StepSourceAvailability.ApiUnavailable,
            StepSourceAvailability.PlayServicesUpdateRequired,
            StepSourceAvailability.Error("RemoteException"),
            null,
        )
        for (availability in others) {
            assertNull("$availability", stepPermissionAction(availability, permissionRequestDenied = true))
            assertNull("$availability", stepPermissionAction(availability, permissionRequestDenied = false))
        }
    }
}
