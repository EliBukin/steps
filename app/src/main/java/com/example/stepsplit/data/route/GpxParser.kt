package com.example.stepsplit.data.route

import android.util.Xml
import com.example.stepsplit.domain.route.RouteLine
import com.example.stepsplit.domain.route.RouteSegment
import com.example.stepsplit.domain.route.RouteText
import com.example.stepsplit.domain.route.RouteWaypoint
import java.io.ByteArrayInputStream
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Reads a GPX file someone sent or saved: its tracks (`trk`, every `trkseg`), routes (`rte`) and
 * waypoints (`wpt`), with their names and heights. GPX 1.1, GPX 1.0 and files without a namespace
 * all read the same; times, extensions (heart rate, colors, an app's own tags) and everything else
 * are skipped.
 *
 * **Safety.** The file comes from anyone, so:
 * - A file with a DOCTYPE is refused as soon as the parser reaches it, before anything after it is
 *   read: GPX never needs one, and a DOCTYPE is where entity tricks (a "billion laughs" file, an
 *   external entity pointing at a local file or a web address) are declared. Android's parser
 *   never fetches anything, and with the DOCTYPE refused, no declared entity is ever expanded.
 * - The caller limits the file's size (see [ImportedRouteRepository]); here, at most
 *   [MAX_READ_POINTS] points and [MAX_WAYPOINTS] waypoints are kept, however many the file has.
 * - Numbers must be plain decimals ("31.25", "-4.5e2"): "NaN", "Infinity", hex and comma decimals
 *   are refused, and so is a latitude past ±90 or a longitude past ±180. A point without a valid
 *   position is skipped and counted; a bad height only loses the height.
 *
 * **Broken files.** A file that stops in the middle (a download cut short) or goes wrong part way
 * keeps everything read before the break, and says it's [Parsed.damaged]. Never an exception.
 */
internal object GpxParser {
    /** Points read from one file at most, before the import thins them to its own limit. */
    const val MAX_READ_POINTS = 1_000_000

    const val MAX_WAYPOINTS = 2_000

    sealed interface Result {
        data class Parsed(
            /** The file's own name for itself: `<metadata><name>` (GPX 1.1) or `<gpx><name>` (GPX 1.0). */
            val fileTitle: String?,
            /** The first named track or route's name. */
            val firstLineName: String?,
            val lines: List<RouteLine>,
            val waypoints: List<RouteWaypoint>,
            /** Points left out: no valid position, or past [MAX_READ_POINTS]. */
            val skippedPoints: Int,
            /** Waypoints left out: no valid position, or past [MAX_WAYPOINTS]. */
            val skippedWaypoints: Int,
            /** The file breaks off or goes wrong part way; what came before is here. */
            val damaged: Boolean,
        ) : Result

        /** Not a GPX file: another kind of XML, or not XML at all. */
        data object NotGpx : Result

        /** A KML file (Google Earth, My Maps): not read yet. */
        data object Kml : Result

        /** The file declares a DOCTYPE - see the class doc comment. */
        data object Refused : Result
    }

    fun parse(bytes: ByteArray): Result {
        val parser = Xml.newPullParser()
        // Namespaces on, so `name` is the local name whatever prefix or namespace the file uses.
        // DOCTYPE processing stays off (the default); nextToken() below reports one, and it's refused.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        return try {
            // Null: the parser finds the encoding itself (a byte order mark, or the XML declaration).
            parser.setInput(ByteArrayInputStream(bytes), null)
            Reader(parser).read()
        } catch (_: XmlPullParserException) {
            Result.NotGpx
        } catch (_: IOException) {
            Result.NotGpx
        }
    }

    private class RefusedException : Exception()

    private class Reader(private val parser: XmlPullParser) {
        private var fileTitle: String? = null
        private var firstLineName: String? = null
        private val lines = mutableListOf<RouteLine>()
        private val waypoints = mutableListOf<RouteWaypoint>()
        private var readPoints = 0
        private var skippedPoints = 0
        private var skippedWaypoints = 0

        // The line and segment being read, so a file that breaks off keeps them.
        private var openLine: MutableList<RouteSegment>? = null
        private var openSegment: SegmentBuilder? = null

        fun read(): Result {
            val root = try {
                firstElement()
            } catch (_: RefusedException) {
                return Result.Refused
            } catch (_: XmlPullParserException) {
                return Result.NotGpx
            } catch (_: IOException) {
                return Result.NotGpx
            } ?: return Result.NotGpx
            when (root) {
                "gpx" -> {}
                "kml" -> return Result.Kml
                else -> return Result.NotGpx
            }
            var damaged = false
            try {
                readGpx()
            } catch (_: RefusedException) {
                return Result.Refused
            } catch (_: XmlPullParserException) {
                damaged = true
            } catch (_: IOException) {
                damaged = true
            } catch (_: RuntimeException) {
                // The parser's own odd failures on a mangled file (an index past the end, say).
                damaged = true
            }
            if (damaged) {
                closeSegment()
                closeLine()
            }
            return Result.Parsed(
                fileTitle = fileTitle,
                firstLineName = firstLineName,
                lines = lines.toList(),
                waypoints = waypoints.toList(),
                skippedPoints = skippedPoints,
                skippedWaypoints = skippedWaypoints,
                damaged = damaged,
            )
        }

        /** The next token that matters: a start tag, an end tag, text, or the end of the document. */
        private fun next(): Int {
            while (true) {
                when (val token = parser.nextToken()) {
                    XmlPullParser.DOCDECL -> throw RefusedException()
                    XmlPullParser.START_TAG, XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return token
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> return XmlPullParser.TEXT
                    // Comments, processing instructions, whitespace between tags.
                    else -> {}
                }
            }
        }

        /** The root element's local name, or null for a document without one. */
        private fun firstElement(): String? {
            while (true) {
                when (next()) {
                    XmlPullParser.START_TAG -> return parser.name
                    XmlPullParser.END_DOCUMENT -> return null
                }
            }
        }

        /** Calls [onChild] for each child element of the current one (positioned on its start tag), until its end tag. */
        private inline fun children(onChild: (String) -> Unit) {
            val depth = parser.depth
            while (true) {
                when (next()) {
                    XmlPullParser.START_TAG -> onChild(parser.name)
                    XmlPullParser.END_TAG -> if (parser.depth == depth) return
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of file")
                }
            }
        }

        /** Skips the current element and everything inside it. */
        private fun skip(): Unit = children { skip() }

        /** The current element's text, with anything nested inside it skipped. */
        private fun text(): String {
            val depth = parser.depth
            val text = StringBuilder()
            while (true) {
                when (next()) {
                    XmlPullParser.TEXT -> if (text.length <= MAX_TEXT_LENGTH) text.append(parser.text.orEmpty())
                    XmlPullParser.START_TAG -> skip()
                    XmlPullParser.END_TAG -> if (parser.depth == depth) return text.toString()
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of file")
                }
            }
        }

        private fun readGpx(): Unit = children { name ->
            when (name) {
                "metadata" -> children { child -> if (child == "name") fileTitle = fileTitle ?: RouteText.clean(text()) else skip() }
                // GPX 1.0 has the file's name right under <gpx>.
                "name" -> fileTitle = fileTitle ?: RouteText.clean(text())
                "wpt" -> readWaypoint()
                "trk" -> readLine(segmentTag = "trkseg", pointTag = "trkpt")
                "rte" -> readLine(segmentTag = null, pointTag = "rtept")
                else -> skip()
            }
        }

        /** A track (its points in [segmentTag]s) or a route (its points right inside it, one segment). */
        private fun readLine(segmentTag: String?, pointTag: String) {
            openLine = mutableListOf()
            if (segmentTag == null) openSegment = SegmentBuilder()
            children { name ->
                when (name) {
                    "name" -> RouteText.clean(text())?.let { if (firstLineName == null) firstLineName = it }
                    segmentTag -> {
                        openSegment = SegmentBuilder()
                        children { child -> if (child == pointTag) readPoint() else skip() }
                        closeSegment()
                    }
                    pointTag -> if (segmentTag == null) readPoint() else skip()
                    else -> skip()
                }
            }
            closeSegment()
            closeLine()
        }

        private fun readPoint() {
            val latitude = latitude(parser.getAttributeValue(null, "lat"))
            val longitude = longitude(parser.getAttributeValue(null, "lon"))
            var elevation = Double.NaN
            children { name -> if (name == "ele") elevation = elevation(text()) ?: Double.NaN else skip() }
            if (latitude == null || longitude == null || isNullIsland(latitude, longitude) || readPoints >= MAX_READ_POINTS) {
                skippedPoints++
                return
            }
            readPoints++
            openSegment?.add(latitude, longitude, elevation)
        }

        private fun readWaypoint() {
            val latitude = latitude(parser.getAttributeValue(null, "lat"))
            val longitude = longitude(parser.getAttributeValue(null, "lon"))
            var elevation: Double? = null
            var name: String? = null
            var description: String? = null
            var comment: String? = null
            children { child ->
                when (child) {
                    "ele" -> elevation = elevation(text())
                    "name" -> name = RouteText.clean(text())
                    "desc" -> description = RouteText.clean(text(), RouteText.MAX_NOTE_LENGTH)
                    "cmt" -> comment = RouteText.clean(text(), RouteText.MAX_NOTE_LENGTH)
                    else -> skip()
                }
            }
            if (latitude == null || longitude == null || isNullIsland(latitude, longitude) || waypoints.size >= MAX_WAYPOINTS) {
                skippedWaypoints++
                return
            }
            waypoints += RouteWaypoint(latitude, longitude, elevation, name, note = description ?: comment)
        }

        /** Keeps the segment being read when it has a line to draw - two points or more. */
        private fun closeSegment() {
            val segment = openSegment ?: return
            openSegment = null
            if (segment.size >= 2) {
                openLine?.add(segment.build())
            } else {
                // A lone point draws nothing.
                skippedPoints += segment.size
                readPoints -= segment.size
            }
        }

        private fun closeLine() {
            val segments = openLine ?: return
            openLine = null
            if (segments.isNotEmpty()) lines += RouteLine(segments.toList())
        }
    }

    /** Points of one segment as they're read, in growing arrays. */
    private class SegmentBuilder {
        private var latitudes = DoubleArray(64)
        private var longitudes = DoubleArray(64)
        private var elevations = DoubleArray(64)
        var size = 0
            private set

        fun add(latitude: Double, longitude: Double, elevation: Double) {
            if (size == latitudes.size) {
                val capacity = size * 2
                latitudes = latitudes.copyOf(capacity)
                longitudes = longitudes.copyOf(capacity)
                elevations = elevations.copyOf(capacity)
            }
            latitudes[size] = latitude
            longitudes[size] = longitude
            elevations[size] = elevation
            size++
        }

        fun build() = RouteSegment(latitudes.copyOf(size), longitudes.copyOf(size), elevations.copyOf(size))
    }

    /** More text than this in one element is cut: a name or a note never needs it. */
    private const val MAX_TEXT_LENGTH = 4_096

    /** A plain decimal number, optionally with an exponent: what GPX's xsd:decimal and xsd:double allow, minus NaN and infinities. */
    private val DECIMAL = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    internal fun number(text: String?): Double? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.length > 40 || !DECIMAL.matches(trimmed)) return null
        return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun latitude(text: String?) = number(text)?.takeIf { it in -90.0..90.0 }

    private fun longitude(text: String?) = number(text)?.takeIf { it in -180.0..180.0 }

    /** Heights from the bottom of the Dead Sea's deepest point to the top of Everest, and a bit. */
    private fun elevation(text: String?) = number(text)?.takeIf { it in -1_000.0..9_000.0 }

    /** 0, 0 is in the ocean off Africa: what some apps write for "no position". */
    private fun isNullIsland(latitude: Double, longitude: Double) = latitude == 0.0 && longitude == 0.0
}
