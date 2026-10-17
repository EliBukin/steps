package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripPoint
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure GPX 1.1 serialization from a completed trip's already-recorded points
 * ([com.example.stepsplit.domain.model.TripPoint], as already exposed by `TripDetailUiState`) - no
 * I/O, no Android dependency, so this is directly plain-JVM unit-testable. Never reads a new
 * location fix and never touches stored trip data; the caller is solely responsible for writing
 * the returned string to wherever the user chose to save it.
 */
internal object GpxExport {
    private const val CREATOR = "Steps GPS Route"

    /**
     * One marked spot as a GPX waypoint. [name] is already the name the app shows: the owner's, or
     * "Spot 3" in the app's language (see `spotDisplayName`), so the spot is called the same in the
     * app and in whatever app opens the file. [altitudeMeters] follows the same sea-level rule as the
     * track points' (see [toGpx]).
     */
    data class Waypoint(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        val epochSecond: Long,
        val name: String,
    )

    /**
     * Serializes [points] as a single GPX 1.1 track (`<trk><trkseg>`), preserving their given
     * order exactly. Each point's latitude/longitude/elevation is formatted with
     * [Locale.ROOT] so a device locale that uses a comma decimal separator can never produce
     * invalid GPX, and its timestamp as a UTC ISO-8601 instant (e.g. `2026-08-17T14:23:00Z`).
     * Elevation is only included when [TripPoint.altitudeMeters] is present. GPX's `<ele>` means
     * height above sea level, and the trip detail screen passes points whose heights it already
     * converted to that on Android 14 and newer (see `TripDetailUiState.heightsAboveSeaLevel`); on
     * older Android they are the stored heights above the WGS84 ellipsoid, as before - about 20 m
     * too high in Israel.
     *
     * A trip [name], when there is one, goes XML-escaped into both `<metadata><name>` (the file's
     * name) and `<trk><name>` (the track's), since apps differ in which one they show. GPX 1.1
     * orders `<metadata>` first, then any waypoints, then the track.
     *
     * Each of [waypoints] - the spots marked during the trip, in the order given - becomes a
     * `<wpt lat lon>` with `<ele>` (when it has a height), `<time>` and its XML-escaped `<name>`, in
     * the order GPX 1.1 requires inside a waypoint.
     */
    fun toGpx(points: List<TripPoint>, name: String? = null, waypoints: List<Waypoint> = emptyList()): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escapeXml(CREATOR)).append("\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        val escapedName = name?.takeIf { it.isNotBlank() }?.let(::escapeXml)
        if (escapedName != null) {
            append("  <metadata>\n    <name>").append(escapedName).append("</name>\n  </metadata>\n")
        }
        for (waypoint in waypoints) {
            append("  <wpt lat=\"").append(formatDecimal(waypoint.latitude))
            append("\" lon=\"").append(formatDecimal(waypoint.longitude)).append("\">\n")
            waypoint.altitudeMeters?.let { altitude ->
                append("    <ele>").append(formatDecimal(altitude)).append("</ele>\n")
            }
            append("    <time>").append(formatTimestamp(waypoint.epochSecond)).append("</time>\n")
            append("    <name>").append(escapeXml(waypoint.name)).append("</name>\n")
            append("  </wpt>\n")
        }
        append("  <trk>\n")
        if (escapedName != null) append("    <name>").append(escapedName).append("</name>\n")
        append("    <trkseg>\n")
        for (point in points) {
            append("      <trkpt lat=\"").append(formatDecimal(point.latitude))
            append("\" lon=\"").append(formatDecimal(point.longitude)).append("\">\n")
            point.altitudeMeters?.let { altitude ->
                append("        <ele>").append(formatDecimal(altitude)).append("</ele>\n")
            }
            append("        <time>").append(formatTimestamp(point.capturedAtEpochSecond)).append("</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** e.g. `steps-gps-route-trip-2026-08-17.gpx` for a trip that started on that (trip-local) date. */
    fun suggestedFileName(tripDate: LocalDate): String = "steps-gps-route-trip-$tripDate.gpx"

    private fun formatTimestamp(epochSecond: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSecond))

    private fun formatDecimal(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

    internal fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
