package us.adfoc.gradle

/** Just enough JSON for AdFoc.us's answers and the plugin's own files, so the plugin needs nothing on its classpath. */
internal object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.space()
        val v = p.value()
        p.space()
        if (p.i != text.length) throw IllegalArgumentException("JSON with more after the end")
        return v
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${quote(it.key.toString())}:${write(it.value)}" }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> quote(v.toString())
    }

    private class Parser(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            space()
            if (i >= s.length) throw IllegalArgumentException("JSON ends too soon")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("not JSON at $i")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            space()
            if (s.getOrNull(i) == '}') return m.also { i++ }
            while (true) {
                space()
                val k = str()
                space()
                if (s.getOrNull(i) != ':') throw IllegalArgumentException("not JSON at $i")
                i++
                m[k] = value()
                space()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> return m.also { i++ }
                    else -> throw IllegalArgumentException("not JSON at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            space()
            if (s.getOrNull(i) == ']') return l.also { i++ }
            while (true) {
                l.add(value())
                space()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> return l.also { i++ }
                    else -> throw IllegalArgumentException("not JSON at $i")
                }
            }
        }

        private fun str(): String {
            if (s.getOrNull(i) != '"') throw IllegalArgumentException("not JSON at $i")
            i++
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s.getOrNull(i++) ?: break
                        when (e) {
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'u' -> {
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
            throw IllegalArgumentException("JSON text not closed")
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) throw IllegalArgumentException("not JSON at $start")
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
