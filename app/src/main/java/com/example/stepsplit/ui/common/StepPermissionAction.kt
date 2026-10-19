package com.example.stepsplit.ui.common

import com.example.stepsplit.data.stepsource.StepSourceAvailability

/** What the Today banner offers while steps can't be read for lack of permission. */
enum class StepPermissionAction {
    /** Health Connect's own permission dialog - always the first thing offered. */
    GRANT_PERMISSION,

    /** Health Connect's own permission screen for this app, where the owner switches on Steps. */
    OPEN_HEALTH_CONNECT,
}

/**
 * Null unless the permission is what's missing. Once a request has come back without Steps
 * ([permissionRequestDenied]), the banner offers Health Connect's own screen instead of the dialog:
 * after two "Don't allow" answers Health Connect stops showing the dialog and returns at once, so
 * another *Grant permission* would do nothing. The app can't tell that apart from a first "Don't
 * allow" (the result is the same empty set, and timing the return would be a guess), and Health
 * Connect's screen works in both cases - so any denial switches it.
 */
fun stepPermissionAction(availability: StepSourceAvailability?, permissionRequestDenied: Boolean): StepPermissionAction? = when {
    availability !is StepSourceAvailability.PermissionNotGranted -> null
    permissionRequestDenied -> StepPermissionAction.OPEN_HEALTH_CONNECT
    else -> StepPermissionAction.GRANT_PERMISSION
}
