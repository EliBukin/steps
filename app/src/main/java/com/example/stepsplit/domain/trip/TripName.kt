package com.example.stepsplit.domain.trip

/**
 * A trip's optional name, like "Wadi Qelt hike". Pure and JVM-testable. A trip with no name is
 * titled by its date, exactly as before names existed.
 */
object TripName {
    /** The longest name kept, in UTF-16 characters (the text field counts the same way). */
    const val MAX_LENGTH = 60

    /**
     * [raw] as it is stored, or null for no name: runs of whitespace and control characters
     * (a pasted line break, a tab) become one space, the ends are trimmed, and anything past
     * [MAX_LENGTH] is cut off without splitting an emoji in half. Blank is no name.
     */
    fun normalize(raw: String?): String? {
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
            }
        }
        return limit(collapsed).trimEnd().ifEmpty { null }
    }

    /** [text] cut to at most [MAX_LENGTH] characters, never ending on the first half of a surrogate pair. */
    fun limit(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val end = if (Character.isHighSurrogate(text[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
        return text.substring(0, end)
    }
}
