package com.example.stepsplit.domain.trip

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One point to export - independent of [com.example.stepsplit.data.local.trip.TripPointEntity] so the formatter has no Room/Android dependency. */
data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timeEpochSecond: Long,
)

/**
 * Formats an already-accepted route as a standards-compatible GPX 1.1 document: chronological
 * order, UTC (`Z`-suffixed) timestamps, elevation only when actually supplied, fixed-precision
 * decimal coordinates (never Kotlin's default `Double.toString()`, which switches to scientific
 * notation for small magnitudes near the equator/prime meridian and would produce an invalid GPX
 * `xsd:decimal`), and XML-escaped text content. Pure and deterministic: the same input always
 * produces byte-identical output, which is what makes it worth unit-testing directly.
 */
object GpxFormatter {
    private const val COORDINATE_FORMAT = "%.7f"
    private const val ELEVATION_FORMAT = "%.1f"

    fun format(trackName: String, points: List<GpxPoint>): String {
        val chronological = points.sortedBy { it.timeEpochSecond }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Bukin's Split Step\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk>\n")
            append("    <name>").append(escapeXml(trackName)).append("</name>\n")
            append("    <trkseg>\n")
            for (point in chronological) {
                append("      <trkpt lat=\"").append(formatCoordinate(point.latitude))
                    .append("\" lon=\"").append(formatCoordinate(point.longitude)).append("\">\n")
                if (point.elevationMeters != null) {
                    append("        <ele>").append(formatElevation(point.elevationMeters)).append("</ele>\n")
                }
                append("        <time>").append(formatTimestamp(point.timeEpochSecond)).append("</time>\n")
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
            append("  </trk>\n")
            append("</gpx>\n")
        }
    }

    private fun formatCoordinate(value: Double): String = String.format(Locale.ROOT, COORDINATE_FORMAT, value)

    private fun formatElevation(value: Double): String = String.format(Locale.ROOT, ELEVATION_FORMAT, value)

    private fun formatTimestamp(epochSecond: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSecond))

    private fun escapeXml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
