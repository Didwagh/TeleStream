package com.example.tgserver

/**
 * Parses raw Telegram filenames into clean titles, release years,
 * seasons, and episode numbers for TMDB/IMDb lookup.
 */
object TitleParser {

    data class Parsed(
        val cleanTitle: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?
    ) {
        val isEpisode: Boolean get() = season != null && episode != null
    }

    // Patterns: S01E02, s1.e2, S01 E02, s01e002
    private val seasonEpisodeA = Regex("""[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})""")

    // Pattern: 1x02, 01x02 (guarded against resolutions like 1920x1080)
    private val seasonEpisodeB = Regex("""(?<![0-9])(\d{1,2})x(\d{1,3})(?![0-9])""")

    // Pattern: Season 1 Episode 2, Season 01 Ep 2
    private val seasonEpisodeC = Regex(
        """Season[\s._-]*(\d{1,2})[^0-9]{0,15}?Ep(?:isode)?[\s._-]*(\d{1,3})""",
        RegexOption.IGNORE_CASE
    )

    // Four-digit year not followed by p/i (to exclude 1080p, 2160p)
    private val yearRegex = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9pPiI])""")

    private val bracketContent = Regex("""[\[({][^\])}]*[\])}]""")
    private val nonAlnumRun = Regex("""[._]+""")
    private val whitespace = Regex("""\s+""")

    private val noiseTokens = listOf(
        "2160p", "1080p", "720p", "480p", "360p", "4k", "uhd", "hdr10", "hdr", "dolby vision", "dv",
        "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit",
        "web-dl", "webdl", "webrip", "web", "bluray", "blu-ray", "brrip", "bdrip",
        "dvdrip", "dvdscr", "hdrip", "hdtv", "hdcam", "predvd", "proper", "repack",
        "extended", "uncut", "remastered", "dual audio", "dual", "multi",
        "aac", "ddp5 1", "dd5 1", "dts", "atmos", "5 1", "7 1",
        "esubs", "esub", "hindi", "english", "telugu", "tamil", "sub"
    ).sortedByDescending { it.length }

    fun parse(input: String): Parsed {
        val working = nonAlnumRun.replace(input, " ")

        var season: Int? = null
        var episode: Int? = null
        var cutIndex = working.length

        seasonEpisodeA.find(working)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            cutIndex = minOf(cutIndex, it.range.first)
        }
        if (season == null) {
            seasonEpisodeB.find(working)?.let {
                val s = it.groupValues[1].toIntOrNull()
                if (s != null && s in 1..50) {
                    season = s
                    episode = it.groupValues[2].toIntOrNull()
                    cutIndex = minOf(cutIndex, it.range.first)
                }
            }
        }
        if (season == null) {
            seasonEpisodeC.find(working)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                cutIndex = minOf(cutIndex, it.range.first)
            }
        }

        val yearMatch = yearRegex.find(working)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        yearMatch?.let { cutIndex = minOf(cutIndex, it.range.first) }

        var title = working.substring(0, cutIndex)
        title = bracketContent.replace(title, " ")

        var titleLower = title.lowercase()
        for (token in noiseTokens) {
            titleLower = titleLower.replace(Regex("""\b${Regex.escape(token)}\b"""), " ")
        }

        val cleaned = whitespace.replace(titleLower, " ").trim()
        val titleCased = cleaned.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

        return Parsed(
            cleanTitle = titleCased.ifBlank { whitespace.replace(working, " ").trim() },
            year = year,
            season = season,
            episode = episode
        )
    }
}