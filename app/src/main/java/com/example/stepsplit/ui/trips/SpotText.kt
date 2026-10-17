package com.example.stepsplit.ui.trips

import android.content.res.Resources
import com.example.stepsplit.R
import com.example.stepsplit.data.trip.SpotMarkResult
import com.example.stepsplit.domain.model.TripSpot

/**
 * A spot's name as the app shows it: the owner's name for it, or "Spot 3" in the app's language.
 * The GPX file uses the same, so a spot is called the same in the app and in the other app.
 */
fun spotDisplayName(resources: Resources, spot: TripSpot): String =
    spot.name ?: resources.getString(R.string.trip_spot_default_name, spot.number)

/** What a Mark spot tap did, for the Trips screen's snackbar and the notification's toast and text alike. */
fun spotMarkMessage(resources: Resources, result: SpotMarkResult): String = when (result) {
    is SpotMarkResult.Marked ->
        resources.getString(R.string.trip_spot_marked_message, resources.getString(R.string.trip_spot_default_name, result.number))
    is SpotMarkResult.AlreadyMarked ->
        resources.getString(R.string.trip_spot_already_marked_message, resources.getString(R.string.trip_spot_default_name, result.number))
    SpotMarkResult.NoFix -> resources.getString(R.string.trip_spot_no_fix_message)
    SpotMarkResult.NotRecording -> resources.getString(R.string.trip_spot_not_recording_message)
    SpotMarkResult.Failed -> resources.getString(R.string.trip_spot_failed_message)
}
