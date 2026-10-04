package com.example.filmio.feature.catalog.database.util

/** GLOB keeps non-ASCII characters exact, unlike lower() on Android SQLite builds using ICU. */
internal fun literalTitleSearchPattern(query: String): String = buildString {
    append('*')
    query.forEach { character ->
        when (character) {
            in 'a'..'z', in 'A'..'Z' -> {
                append('[')
                append(character.uppercaseChar())
                append(character.lowercaseChar())
                append(']')
            }
            '*' -> append("[*]")
            '?' -> append("[?]")
            '[' -> append("[[]")
            ']' -> append("[]]")
            else -> append(character)
        }
    }
    append('*')
}
