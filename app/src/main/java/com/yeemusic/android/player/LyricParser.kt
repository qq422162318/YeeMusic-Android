package com.yeemusic.android.player

import java.util.Comparator
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A single timed lyric line. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Parses the lyric formats the backend actually returns.
 *
 * 1. Classic LRC — `[mm:ss.xxx]text`, any number of stamps per line.
 * 2. NetEase's "new LRC" — one JSON object per line,
 *    `{"t":14300,"c":[{"tx":"text"}]}`, used for official lyrics. It has no
 *    `[mm:ss]` stamps at all, so without the JSON branch the whole lyric is
 *    dropped and a single stray `}` is what the UI ends up showing.
 * 3. Plain prose with no timestamps at all — shown as an un-timed sheet.
 *
 * Lines with no timestamp and no readable text are dropped; they are metadata,
 * not lyrics. `[ti:]`/`[ar:]`/`[al:]` tags are kept as metadata only.
 */
class LyricParser {

    companion object {
        /**
         * `[mm:ss]` plus the separators real-world LRC uses: `.`, `,` (NetEase),
         * `:` and `-`. The comma was missing from the pattern before, which made
         * every NetEase lyric line fail to parse.
         */
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[:,.-](\d{1,3}))?\]""")
        private val META = Regex("""\[(ti|ar|al|by|re|ve|la|language)\s*:\s*([^]]*)]""")

        /** Play order, then text so lines that share a timestamp stay stable. */
        private val LINE_ORDER: Comparator<LyricLine> = Comparator { a, b ->
            val byTime = a.timeMs.compareTo(b.timeMs)
            if (byTime != 0) byTime else a.text.compareTo(b.text)
        }

        private const val MAX_MS = 8_640_000_000L

        /** NetEase answers songs without lyrics with a single placeholder line. */
        private const val NO_LYRIC_PLACEHOLDER = "暂无歌词"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * `{"t":14300,"c":[{"tx":"作词: "},{"tx":"周杰伦"}]}` → one lyric line.
         * The text runs are joined in order; hyperlinks are ignored.
         */
        private fun parseJsonLine(line: String): LyricLine? = runCatching {
            val root = json.parseToJsonElement(line).jsonObject
            val timeMs = root["t"]?.jsonPrimitive?.longOrNull ?: return null
            if (timeMs !in 0..MAX_MS) return null

            val text = root["c"]?.jsonArray.orEmpty()
                .joinToString("") { it.jsonObject["tx"]?.jsonPrimitive?.content.orEmpty() }
                .trim()
            if (text.isEmpty() || text == NO_LYRIC_PLACEHOLDER) return null
            LyricLine(timeMs, text)
        }.getOrNull()

        fun parse(text: String?): ParsedLyric {
            if (text.isNullOrBlank()) return ParsedLyric()

            val lines = mutableListOf<LyricLine>()
            val plain = mutableListOf<String>()
            val meta = LinkedHashMap<String, String>()

            for (raw in text.lines()) {
                val line = raw.trim().trimEnd('\r')
                if (line.isEmpty()) continue

                META.findAll(line).forEach { m -> meta[m.groupValues[1]] = m.groupValues[2].trim() }

                if (line.startsWith("{")) {
                    parseJsonLine(line)?.let { lines += it }
                    continue
                }

                val stamps = mutableListOf<Long>()
                STAMP.findAll(line).forEach { m ->
                    val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                    val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                    val fracRaw = m.groupValues[3]
                    val frac = if (fracRaw.isEmpty()) 0L else fracRaw.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    val ms = min * 60_000 + sec * 1_000 + frac
                    if (ms in 0..MAX_MS) stamps.add(ms)
                }

                val textStart = line.indexOf(']')
                val textContent = if (textStart >= 0) line.substring(textStart + 1).trim() else ""

                if (textContent == NO_LYRIC_PLACEHOLDER) continue

                if (stamps.isEmpty()) {
                    // Prose with no timestamps. Only worth showing if nothing else
                    // parsed at all, so a stray header cannot become a lyric line.
                    if (textContent.isNotEmpty() && !line.startsWith("[")) plain += textContent
                    continue
                }

                if (textContent.isEmpty()) {
                    // Empty lines are explicit pauses in some lyric sources; the
                    // empty-block filter below decides whether they survive.
                    stamps.forEach { lines += LyricLine(it, "") }
                } else {
                    stamps.forEach { lines += LyricLine(it, textContent) }
                }
            }

            val hasVisible = lines.any { it.text.isNotBlank() }
            val finalLines = when {
                // A block with no visible text at all is junk: NetEase answers some
                // songs with a timing-only translation (`[00:00.950]` and nothing
                // else), which used to fill the sheet with a screen of dots.
                hasVisible -> lines
                plain.isNotEmpty() -> plain.map { LyricLine(0L, it) }
                else -> emptyList()
            }

            return ParsedLyric(
                lines = finalLines.sortedWith(LINE_ORDER),
                title = meta["ti"] ?: meta["la"],
                artist = meta["ar"],
                album = meta["al"],
                source = meta["by"],
                // Only a sheet that is entirely un-timed is shown statically; a
                // single un-timed block mixed with timed lines keeps its sync.
                unTimed = plain.isNotEmpty() && !hasVisible,
            )
        }

        /**
         * Parses the separate lyric blocks the backend returns and stitches them
         * together. Blocks are filtered individually, so an empty or timing-only
         * translation cannot dilute the real lyric.
         */
        fun parseBlocks(vararg blocks: String?): ParsedLyric {
            val lines = mutableListOf<LyricLine>()
            var unTimed = false
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var source: String? = null

            for (block in blocks) {
                val parsed = parse(block)
                title = title ?: parsed.title
                artist = artist ?: parsed.artist
                album = album ?: parsed.album
                source = source ?: parsed.source
                if (parsed.lines.isEmpty()) continue
                lines += parsed.lines
                unTimed = unTimed || parsed.unTimed
            }

            return ParsedLyric(
                lines = lines.sortedWith(LINE_ORDER),
                title = title,
                artist = artist,
                album = album,
                source = source,
                unTimed = unTimed,
            )
        }

        /** Index of the line active at [posMs], or -1 before the first line. */
        fun indexOfAt(lines: List<LyricLine>, posMs: Long): Int {
            if (lines.isEmpty()) return -1
            var lo = 0
            var hi = lines.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].timeMs <= posMs) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }

        /** Human-readable duration, e.g. 3:47. */
        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}

data class ParsedLyric(
    val lines: List<LyricLine> = emptyList(),
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: String? = null,
    /** True when nothing in this sheet is time-synced, so it is shown statically. */
    val unTimed: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
