package com.example.tgserver

/**
 * Parses raw Telegram filenames into clean titles, release years,
 * seasons, episode numbers, and optional episode ranges.
 */
object TitleParser {

    data class Parsed(
        val cleanTitle: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val episodeEnd: Int? = null
    ) {
        val isEpisode: Boolean
            get() = season != null && episode != null

        val isEpisodeRange: Boolean
            get() = episode != null &&
                episodeEnd != null &&
                episodeEnd > episode
    }

    // ------------------------------------------------------------
    // Single-episode formats
    // ------------------------------------------------------------

    // S01E02, s1.e2, S01 E02, s01e002
    private val seasonEpisodeA =
        Regex("""[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})""")

    // 1x02, 01x02 (guarded against resolutions like 1920x1080)
    private val seasonEpisodeB =
        Regex("""(?<![0-9])(\d{1,2})x(\d{1,3})(?![0-9])""")

    // Season 1 Episode 2, Season 01 Ep 2
    private val seasonEpisodeC = Regex(
        """Season[\s._-]*(\d{1,2})[^0-9]{0,15}?Ep(?:isode)?[\s._-]*(\d{1,3})""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------
    // Episode-range formats
    // ------------------------------------------------------------

    // S01E01-E03
    // S01E01-03
    // S01E01–E03
    // S01E01–03
    // S01E01-S01E03
    // S01E01 to E03
    private val seasonEpisodeRangeA = Regex(
        """[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})\s*(?:[-–—]|to|through)\s*(?:(?:[Ss]\d{1,2}[\s._-]*)?[Ee]\s*)?(\d{1,3})""",
        RegexOption.IGNORE_CASE
    )

    // S01E01E02
    private val seasonEpisodeRangeAE =
        Regex("""[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})[\s._-]*[Ee](\d{1,3})""")

    // 1x01-03
    // 1x01–03
    // 1x01 to 03
    private val seasonEpisodeRangeB = Regex(
        """(?<![0-9])(\d{1,2})x(\d{1,3})\s*(?:[-–—]|to|through)\s*(\d{1,3})(?![0-9])""",
        RegexOption.IGNORE_CASE
    )

    // Season 1 Episode 1-3
    private val seasonEpisodeRangeC = Regex(
        """Season[\s._-]*(\d{1,2})[^0-9]{0,15}?Ep(?:isode)?[\s._-]*(\d{1,3})\s*(?:[-–—]|to|through)\s*(\d{1,3})""",
        RegexOption.IGNORE_CASE
    )

    // Four-digit year not followed by p/i
    // to exclude 1080p, 2160p, etc.
    private val yearRegex =
        Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9pPiI])""")

    private val bracketContent =
        Regex("""[\[({][^\])}]*[\])}]""")

    private val nonAlnumRun =
        Regex("""[._]+""")

    private val whitespace =
        Regex("""\s+""")

    private val noiseTokens = listOf(
        "2160p",
        "1080p",
        "720p",
        "480p",
        "360p",
        "4k",
        "uhd",
        "hdr10",
        "hdr",
        "dolby vision",
        "dv",
        "x264",
        "x265",
        "h264",
        "h265",
        "hevc",
        "avc",
        "10bit",
        "8bit",
        "web-dl",
        "webdl",
        "webrip",
        "web",
        "bluray",
        "blu-ray",
        "brrip",
        "bdrip",
        "dvdrip",
        "dvdscr",
        "hdrip",
        "hdtv",
        "hdcam",
        "predvd",
        "proper",
        "repack",
        "extended",
        "uncut",
        "remastered",
        "dual audio",
        "dual",
        "multi",
        "aac",
        "ddp5 1",
        "dd5 1",
        "dts",
        "atmos",
        "5 1",
        "7 1",
        "esubs",
        "esub",
        "hindi",
        "english",
        "telugu",
        "tamil",
        "sub"
    ).sortedByDescending { it.length }

    fun parse(input: String): Parsed {
        val working = nonAlnumRun.replace(input, " ")

        var season: Int? = null
        var episode: Int? = null
        var episodeEnd: Int? = null
        var cutIndex = working.length

        // --------------------------------------------------------
        // Highest-priority: explicit episode ranges
        // --------------------------------------------------------

        seasonEpisodeRangeA.find(working)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            episodeEnd = it.groupValues[3].toIntOrNull()

            cutIndex = minOf(cutIndex, it.range.first)
        }

        if (season == null) {
            seasonEpisodeRangeAE.find(working)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                episodeEnd = it.groupValues[3].toIntOrNull()

                cutIndex = minOf(cutIndex, it.range.first)
            }
        }

        if (season == null) {
            seasonEpisodeRangeB.find(working)?.let {
                val parsedSeason = it.groupValues[1].toIntOrNull()

                if (parsedSeason != null && parsedSeason in 1..50) {
                    season = parsedSeason
                    episode = it.groupValues[2].toIntOrNull()
                    episodeEnd = it.groupValues[3].toIntOrNull()

                    cutIndex = minOf(cutIndex, it.range.first)
                }
            }
        }

        if (season == null) {
            seasonEpisodeRangeC.find(working)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                episodeEnd = it.groupValues[3].toIntOrNull()

                cutIndex = minOf(cutIndex, it.range.first)
            }
        }

        // --------------------------------------------------------
        // Single episode fallback
        // --------------------------------------------------------

        if (season == null) {
            seasonEpisodeA.find(working)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()

                cutIndex = minOf(cutIndex, it.range.first)
            }
        }

        if (season == null) {
            seasonEpisodeB.find(working)?.let {
                val parsedSeason = it.groupValues[1].toIntOrNull()

                if (parsedSeason != null && parsedSeason in 1..50) {
                    season = parsedSeason
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

        // --------------------------------------------------------
        // Validate ranges
        // --------------------------------------------------------

        if (
            episode == null ||
            episodeEnd == null ||
            episodeEnd!! <= episode!!
        ) {
            episodeEnd = null
        }

        // --------------------------------------------------------
        // Year
        // --------------------------------------------------------

        val yearMatch = yearRegex.find(working)

        val year =
            yearMatch?.groupValues?.get(1)?.toIntOrNull()

        yearMatch?.let {
            cutIndex = minOf(cutIndex, it.range.first)
        }

        // --------------------------------------------------------
        // Clean title
        // --------------------------------------------------------

        var title = working.substring(0, cutIndex)

        title = bracketContent.replace(title, " ")

        var titleLower = title.lowercase()

        for (token in noiseTokens) {
            titleLower = titleLower.replace(
                Regex("""\b${Regex.escape(token)}\b"""),
                " "
            )
        }

        val cleaned =
            whitespace.replace(titleLower, " ").trim()

        val titleCased =
            cleaned
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }

        return Parsed(
            cleanTitle = titleCased.ifBlank {
                whitespace.replace(working, " ").trim()
            },
            year = year,
            season = season,
            episode = episode,
            episodeEnd = episodeEnd
        )
    }
}