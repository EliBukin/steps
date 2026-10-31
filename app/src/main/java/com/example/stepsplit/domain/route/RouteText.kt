package com.example.stepsplit.domain.route

/** The names and notes of imported routes and their waypoints, as they're kept. Pure and JVM-testable. */
object RouteText {
    /** The longest route or waypoint name kept, in UTF-16 characters. */
    const val MAX_NAME_LENGTH = 200

    /** The longest waypoint note kept. */
    const val MAX_NOTE_LENGTH = 500

    /**
     * [raw] as it is stored, or null for nothing: runs of whitespace and control characters (a line
     * break inside a file's `<name>`, a tab) become one space, the ends are trimmed, and anything
     * past [maxLength] is cut off without splitting an emoji in half. Blank is nothing.
     */
    fun clean(raw: String?, maxLength: Int = MAX_NAME_LENGTH): String? {
        if (raw == null) return null
        val collapsed = buildString {
            var pendingSpace = false
            for (char in raw) {
                if (char.isWhitespace() || char.isISOControl()) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && isNotEmpty()) append(' ')
                    pendingSpace = false
                    append(char)
                }
                // Enough for the limit, however long the rest is.
                if (length > maxLength) break
            }
        }
        return limit(collapsed, maxLength).trimEnd().ifEmpty { null }
    }

    /** A file's name without its `.gpx` ending, as a route's name when the file has none of its own. */
    fun nameFromFileName(fileName: String?): String? = clean(fileName?.replace(Regex("""\.gpx$""", RegexOption.IGNORE_CASE), ""))

    private fun limit(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val end = if (Character.isHighSurrogate(text[maxLength - 1])) maxLength - 1 else maxLength
        return text.substring(0, end)
    }
}
