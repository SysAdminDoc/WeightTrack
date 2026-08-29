package com.weighttrack.data.io

/** A parsed delimited file: the header row, and every data row padded to the header's width. */
data class CsvTable(
    val header: List<String>,
    val rows: List<List<String>>,
    val delimiter: Char,
) {
    fun columnIndex(name: String): Int =
        header.indexOfFirst { it.equals(name, ignoreCase = true) }

    fun value(row: List<String>, index: Int): String? =
        row.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * A small RFC 4180 reader.
 *
 * Exports from other trackers routinely contain quoted notes with commas and embedded newlines
 * in them, so splitting on the delimiter is not good enough; a naive split silently shifts
 * every column after the note and imports garbage.
 */
object Csv {

    private val CANDIDATE_DELIMITERS = charArrayOf(',', ';', '\t')

    fun parse(text: String): CsvTable? {
        val cleaned = text.removePrefix("\uFEFF")
        if (cleaned.isBlank()) return null
        val delimiter = detectDelimiter(cleaned)
        val records = parseRecords(cleaned, delimiter)
        if (records.isEmpty()) return null
        val header = records.first().map { it.trim() }
        val rows = records.drop(1).filter { row -> row.any { it.isNotBlank() } }
        return CsvTable(header, rows, delimiter)
    }

    /** Picks whichever delimiter yields the most columns on the first line. */
    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return CANDIDATE_DELIMITERS.maxByOrNull { candidate ->
            parseRecords(firstLine, candidate).firstOrNull()?.size ?: 0
        } ?: ','
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            records.add(fields)
            fields = ArrayList()
        }

        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' -> {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                }
                inQuotes -> field.append(char)
                char == '"' -> inQuotes = true
                char == delimiter -> endField()
                char == '\r' -> {
                    // Swallow the CR of a CRLF pair; a lone CR still ends the record.
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRecord()
                }
                char == '\n' -> endRecord()
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    /** Quotes a value only when it needs it, so a plain export stays easy to read. */
    fun escape(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun row(values: List<String>): String = values.joinToString(",") { escape(it) }
}
