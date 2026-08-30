package com.example.tgserver

/**
 * Parses Telegram media filenames into normalized metadata.
 *
 * Examples supported:
 *
 * House.of.the.Dragon.S03E01
 * House.of.the.Dragon.S03E01-E04
 * House of the Dragon S03 [E01-04] COMBINED 1080p
 * House of the Dragon S03 [E01 - E04] COMBINED
 * House of the Dragon S03 [E01–E04]
 * Show 1x01
 * Show 1x01-04
 * Show Season 1 Episode 1
 * Show Season 1 Episode 1-4
 */
object TitleParser {

    data class Parsed(
        val cleanTitle: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val episodeEnd: Int? = null,
        // True when the raw filename contains the word "combined" -
        // a strong signal (in this library's release naming) that a
        // WHOLE series/season was packaged as one single video file,
        // even though no S0xE0x marker is present to prove it's an
        // episode. See ChannelCatalogBuilder: leaves with this flag
        // set are rerouted into the series bucket instead of being
        // treated (and TMDB-movie-searched) as a movie.
        val hasCombinedMarker: Boolean = false
    ) {
        val isEpisode: Boolean
            get() =
                season != null &&
                    episode != null

        val isEpisodeRange: Boolean
            get() =
                episode != null &&
                    episodeEnd != null &&
                    episodeEnd > episode
    }

    // ------------------------------------------------------------
    // IMPORTANT:
    //
    // This is the format from your real Telegram file:
    //
    // S03 [E01-04]
    //
    // The '[' / '(' / '{' between season and episode is optional.
    // ------------------------------------------------------------

    private val seasonEpisodeRangeBracketed =
        Regex(
            """\b[Ss](\d{1,2})\s*[\[\(\{]?\s*[Ee](\d{1,3})\s*(?:[-–—]|to|through)\s*(?:[Ee]\s*)?(\d{1,3})"""
        )

    // S01E01-E04
    // S01E01-04
    // S01E01–E04
    // S01E01–04
    // S01E01-S01E04
    private val seasonEpisodeRangeA =
        Regex(
            """\b[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})\s*(?:[-–—]|to|through)\s*(?:(?:[Ss]\d{1,2}[\s._-]*)?[Ee]\s*)?(\d{1,3})""",
            RegexOption.IGNORE_CASE
        )

    // S01E01E04
    private val seasonEpisodeRangeAE =
        Regex(
            """\b[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})[\s._-]*[Ee](\d{1,3})""",
            RegexOption.IGNORE_CASE
        )

    // 1x01-04
    // 1x01–04
    // 1x01 to 04
    private val seasonEpisodeRangeB =
        Regex(
            """\b(\d{1,2})x(\d{1,3})\s*(?:[-–—]|to|through)\s*(\d{1,3})\b""",
            RegexOption.IGNORE_CASE
        )

    // Season 1 Episode 1-4
    private val seasonEpisodeRangeC =
        Regex(
            """\bSeason[\s._-]*(\d{1,2})[^0-9]{0,15}?Ep(?:isode)?[\s._-]*(\d{1,3})\s*(?:[-–—]|to|through)\s*(\d{1,3})\b""",
            RegexOption.IGNORE_CASE
        )

    // ------------------------------------------------------------
    // Single episode formats
    // ------------------------------------------------------------

    // S01E02
    // S1.E2
    // S01 E02
    // S01 [E02]
    private val seasonEpisodeBracketed =
        Regex(
            """\b[Ss](\d{1,2})\s*[\[\(\{]?\s*[Ee](\d{1,3})\b"""
        )

    private val seasonEpisodeA =
        Regex(
            """\b[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})\b"""
        )

    // 1x02 / 01x02
    //
    // Guarded by boundaries so things such as 1920x1080 are not
    // interpreted as season 19 episode 20.
    private val seasonEpisodeB =
        Regex(
            """\b(\d{1,2})x(\d{1,3})\b""",
            RegexOption.IGNORE_CASE
        )

    // Season 1 Episode 2
    // Season 01 Ep 2
    private val seasonEpisodeC =
        Regex(
            """\bSeason[\s._-]*(\d{1,2})[^0-9]{0,15}?Ep(?:isode)?[\s._-]*(\d{1,3})\b""",
            RegexOption.IGNORE_CASE
        )

    // ------------------------------------------------------------
    // Season-only fallback.
    //
    // We intentionally DO NOT classify this as a playable episode,
    // because without an episode number we cannot truthfully create
    // a CloudStream EpisodeEntry.
    // ------------------------------------------------------------

    private val seasonOnlyRegex =
        Regex(
            """\b[Ss](\d{1,2})\b"""
        )

    // Detected independently of the noise-token pass (which strips
    // "combined" out of the display title) so the signal survives
    // even though the word itself disappears from cleanTitle.
    private val combinedMarkerRegex =
        Regex(
            """\bcombined\b""",
            RegexOption.IGNORE_CASE
        )

    // ------------------------------------------------------------
    // Year
    // ------------------------------------------------------------

    // Matches 1990-2099.
    //
    // Avoids:
    // 1080p
    // 2160p
    // etc.
    private val yearRegex =
        Regex(
            """(?<![0-9])(19\d{2}|20\d{2})(?![0-9pPiI])"""
        )

    // Remove bracketed metadata after the meaningful title.
    private val bracketContent =
        Regex(
            """[\[({][^\])}]*[\])}]"""
        )

    // Normalize dots and underscores to spaces.
    private val nonAlnumRun =
        Regex(
            """[._]+"""
        )

    private val whitespace =
        Regex(
            """\s+"""
        )

    // ------------------------------------------------------------
    // Release/noise tokens.
    // These are only applied while constructing the display title.
    // ------------------------------------------------------------

    private val noiseTokens =
        listOf(
            "2160p",
            "1080p",
            "720p",
            "480p",
            "360p",
            "4k",
            "uhd",
            "hdr10+",
            "hdr10",
            "hdr",
            "dolby vision",
            "dolby",
            "vision",
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
            "dl",
            "hqrip",
            "camrip",
            "hdts",
            "hc",
            "predvdrip",
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
            "combined",
            "complete",
            "full",
            "dual audio",
            "dual",
            "multi",
            "aac",
            "ddp5 1",
            "ddp 5 1",
            "dd5 1",
            "dd 5 1",
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
            "malayalam",
            "bengali",
            "punjabi",
            "marathi",
            "kannada",
            "jhs",
            "subs",
            "sub"
        )
            .sortedByDescending {
                it.length
            }

    fun parse(input: String): Parsed {

        if (input.isBlank()) {
            return Parsed(
                cleanTitle = "",
                year = null,
                season = null,
                episode = null,
                episodeEnd = null
            )
        }

        // Normalize only separators that should behave like spaces.
        val working =
            nonAlnumRun.replace(
                input,
                " "
            )

        var season: Int? = null
        var episode: Int? = null
        var episodeEnd: Int? = null

        // This is the point where the title ends.
        //
        // For:
        //
        // House of the Dragon S03 [E01-04] COMBINED...
        //
        // we want cutIndex to point at S03, not E01.
        var cutIndex =
            working.length

        // --------------------------------------------------------
        // 1. Explicit bracketed episode range.
        //
        // Handles the user's exact filename:
        //
        // S03 [E01-04]
        // --------------------------------------------------------

        seasonEpisodeRangeBracketed
            .find(working)
            ?.let { match ->

                season =
                    match.groupValues[1]
                        .toIntOrNull()

                episode =
                    match.groupValues[2]
                        .toIntOrNull()

                episodeEnd =
                    match.groupValues[3]
                        .toIntOrNull()

                // IMPORTANT:
                // Cut before S03.
                cutIndex =
                    minOf(
                        cutIndex,
                        match.range.first
                    )
            }

        // --------------------------------------------------------
        // 2. Normal S01E01-E04 form
        // --------------------------------------------------------

        if (season == null) {

            seasonEpisodeRangeA
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    episodeEnd =
                        match.groupValues[3]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        // --------------------------------------------------------
        // 3. S01E01E04
        // --------------------------------------------------------

        if (season == null) {

            seasonEpisodeRangeAE
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    episodeEnd =
                        match.groupValues[3]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        // --------------------------------------------------------
        // 4. 1x01-04
        // --------------------------------------------------------

        if (season == null) {

            seasonEpisodeRangeB
                .find(working)
                ?.let { match ->

                    val parsedSeason =
                        match.groupValues[1]
                            .toIntOrNull()

                    if (
                        parsedSeason != null &&
                        parsedSeason in 1..50
                    ) {

                        season =
                            parsedSeason

                        episode =
                            match.groupValues[2]
                                .toIntOrNull()

                        episodeEnd =
                            match.groupValues[3]
                                .toIntOrNull()

                        cutIndex =
                            minOf(
                                cutIndex,
                                match.range.first
                            )
                    }
                }
        }

        // --------------------------------------------------------
        // 5. Season 1 Episode 1-4
        // --------------------------------------------------------

        if (season == null) {

            seasonEpisodeRangeC
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    episodeEnd =
                        match.groupValues[3]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        // --------------------------------------------------------
        // Single episode fallback
        // --------------------------------------------------------

        if (season == null) {

            seasonEpisodeBracketed
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        if (season == null) {

            seasonEpisodeA
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        if (season == null) {

            seasonEpisodeB
                .find(working)
                ?.let { match ->

                    val parsedSeason =
                        match.groupValues[1]
                            .toIntOrNull()

                    if (
                        parsedSeason != null &&
                        parsedSeason in 1..50
                    ) {

                        season =
                            parsedSeason

                        episode =
                            match.groupValues[2]
                                .toIntOrNull()

                        cutIndex =
                            minOf(
                                cutIndex,
                                match.range.first
                            )
                    }
                }
        }

        if (season == null) {

            seasonEpisodeC
                .find(working)
                ?.let { match ->

                    season =
                        match.groupValues[1]
                            .toIntOrNull()

                    episode =
                        match.groupValues[2]
                            .toIntOrNull()

                    cutIndex =
                        minOf(
                            cutIndex,
                            match.range.first
                        )
                }
        }

        // --------------------------------------------------------
        // Validate the range.
        // --------------------------------------------------------

        if (
            episode == null ||
            episodeEnd == null ||
            episodeEnd!! <= episode!!
        ) {
            episodeEnd = null
        }

        // --------------------------------------------------------
        // Year.
        //
        // We search the COMPLETE filename, not just the title,
        // because title cutting happens before release metadata.
        // --------------------------------------------------------

        val yearMatch =
            yearRegex.find(
                working
            )

        val year =
            yearMatch
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        // We don't use the year to move cutIndex (which only exists
        // to protect season/episode markers) - instead we strip the
        // year directly out of the title text below. This matters
        // for MOVIES: "Title.2026.WEB-DL.DDP5.1..." has no season/
        // episode marker at all, so cutIndex stays at the end of the
        // string and the year (plus everything after it) would
        // otherwise leak straight into cleanTitle, breaking the TMDB
        // search query and losing the poster/metadata match.
        //
        // This is different from the previous parser implementation,
        // which could accidentally make a year determine the title.
        // --------------------------------------------------------

        // --------------------------------------------------------
        // Clean title.
        // --------------------------------------------------------

        var title =
            working
                .substring(
                    0,
                    cutIndex
                )

        title =
            bracketContent.replace(
                title,
                " "
            )

        // Cut the title at a release year, if one remains in it.
        //
        // Release names are almost universally "Title Year Tags...",
        // so once bracketed metadata is gone, a leftover year marks
        // the real end of the title - along with whatever unknown
        // release-group/quality tags follow it (which we can never
        // fully enumerate in noiseTokens below).
        //
        // Guarded to only cut when the year isn't the very first
        // token, so a genuine numeric title like "1917" survives.
        yearRegex
            .find(title)
            ?.let { titleYearMatch ->
                if (titleYearMatch.range.first > 0) {
                    title =
                        title.substring(
                            0,
                            titleYearMatch.range.first
                        )
                }
            }

        var titleLower =
            title.lowercase()

        for (token in noiseTokens) {

            titleLower =
                titleLower.replace(
                    Regex(
                        """\b${Regex.escape(token)}\b""",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
        }

        // Remove trailing season markers that can remain in unusual
        // filenames when no range parser consumed them.
        titleLower =
            titleLower.replace(
                Regex(
                    """\b[Ss]\d{1,2}\b"""
                ),
                " "
            )

        val cleaned =
            whitespace
                .replace(
                    titleLower,
                    " "
                )
                .trim()

        val titleCased =
            cleaned
                .split(" ")
                .filter {
                    it.isNotBlank()
                }
                .joinToString(" ") {
                    word ->
                    word.replaceFirstChar {
                        it.uppercase()
                    }
                }

        return Parsed(
            cleanTitle =
                titleCased.ifBlank {
                    whitespace
                        .replace(
                            working,
                            " "
                        )
                        .trim()
                },

            year =
                year,

            season =
                season,

            episode =
                episode,

            episodeEnd =
                episodeEnd,

            hasCombinedMarker =
                combinedMarkerRegex.containsMatchIn(working)
        )
    }
}