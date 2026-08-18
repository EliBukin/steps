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
    private const val CREATOR = "StepSplit"

    /**
     * Serializes [points] as a single GPX 1.1 track (`<trk><trkseg>`), preserving their given
     * order exactly. Each point's latitude/longitude/elevation is formatted with
     * [Locale.ROOT] so a device locale that uses a comma decimal separator can never produce
     * invalid GPX, and its timestamp as a UTC ISO-8601 instant (e.g. `2026-08-17T14:23:00Z`).
     * Elevation is only included when [TripPoint.altitudeMeters] is present.
     */
    fun toGpx(points: List<TripPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escapeXml(CREATOR)).append("\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <trkseg>\n")
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

    /** e.g. `stepsplit-trip-2026-08-17.gpx` for a trip that started on that (trip-local) date. */
    fun suggestedFileName(tripDate: LocalDate): String = "stepsplit-trip-$tripDate.gpx"

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
