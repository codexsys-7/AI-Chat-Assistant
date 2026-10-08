package dev.probe.textselection.ai

internal class JsonParseException : Exception()

internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

internal fun parseJsonObject(text: String): Map<String, Any?> {
    val cursor = JsonCursor(text)
    cursor.skipWs()
    val value = cursor.parseValue()
    cursor.skipWs()
    if (!cursor.eof() || value !is Map<*, *>) throw JsonParseException()
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private class JsonCursor(private val text: String) {
    private var index = 0

    fun eof(): Boolean = index >= text.length

    fun skipWs() {
        while (!eof() && text[index].isWhitespace()) index++
    }

    fun parseValue(): Any? {
        skipWs()
        if (eof()) throw JsonParseException()
        return when (text[index]) {
            '{' -> parseObject()
            '"' -> parseString()
            'n' -> parseNull()
            else -> throw JsonParseException()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skipWs()
        if (peek('}')) {
            index++
            return map
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            if (map.containsKey(key)) throw JsonParseException()
            map[key] = parseValue()
            skipWs()
            when {
                peek('}') -> {
                    index++
                    return map
                }
                peek(',') -> index++
                else -> throw JsonParseException()
            }
        }
    }

    private fun parseNull(): Any? {
        expectLiteral("null")
        return null
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!eof()) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                else -> {
                    if (ch.code < 0x20) throw JsonParseException()
                    out.append(ch)
                }
            }
        }
        throw JsonParseException()
    }

    private fun parseEscape(): Char {
        if (eof()) throw JsonParseException()
        return when (val esc = text[index++]) {
            '"', '\\', '/' -> esc
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) throw JsonParseException()
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toIntOrNull(16)?.toChar() ?: throw JsonParseException()
            }
            else -> throw JsonParseException()
        }
    }

    private fun expect(ch: Char) {
        if (eof() || text[index] != ch) throw JsonParseException()
        index++
    }

    private fun peek(ch: Char): Boolean = !eof() && text[index] == ch

    private fun expectLiteral(literal: String) {
        if (!text.startsWith(literal, index)) throw JsonParseException()
        index += literal.length
    }
}
