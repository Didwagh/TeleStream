package com.example.tgserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ItemType { MOVIE, SERIES }

data class FilePart(
    val originalName: String,
    val size: Long,
    val chatId: Long,
    val messageId: Long,
    val label: String
)

data class EpisodeEntry(
    val season: Int,
    val episode: Int,
    val episodeEnd: Int? = null,
    val totalSize: Long,
    val parts: List<FilePart>,
    // Set for a leaf that had no real S0xE0x numbering but was rerouted
    // into the series bucket anyway (a "combined" whole-series/season
    // file, or a Gemini-classified series). Lets the client show
    // something more honest than a fake "Episode 1".
    val label: String? = null
)

data class ChannelItem(
    val type: ItemType,
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val posterUrl: String?,
    val totalSize: Long,
    val parts: List<FilePart>,
    val episodes: List<EpisodeEntry>,
    val overview: String? = null,
    val rating: Double? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList()
)

object ChannelCatalogBuilder {
    private val splitFileSuffix =
        Regex(
            """\.(part\d+|00\d+|r\d+|mp4|mkv)$""",
            RegexOption.IGNORE_CASE
        )

    private val trailingContainerExt =
        Regex(
            """\.(mkv|mp4|avi|mov|ts|webm|m4v)$""",
            RegexOption.IGNORE_CASE
        )

    private var cache: List<ChannelItem>? = null

    private var cachedChatId: Long? = null

    private val buildLock = Mutex()

    @Volatile
    private var isBuilding = false

    fun peekCache(chatId: Long): List<ChannelItem>? {
        return if (cachedChatId == chatId) cache else null
    }

    fun isCurrentlyBuilding(): Boolean =
        isBuilding

    suspend fun getCatalog(
        chatId: Long,
        forceRefresh: Boolean = false
    ): List<ChannelItem> {

        if (!forceRefresh &&
            cachedChatId == chatId &&
            cache != null
        ) {
            return cache!!
        }

        return buildLock.withLock {
            if (!forceRefresh &&
                cachedChatId == chatId &&
                cache != null
            ) {
                return@withLock cache!!
            }

            isBuilding = true

            try {

                val items = build(chatId)

                cache = items
                cachedChatId = chatId

                items

            } finally {

                isBuilding = false
            }
        }
    }

    fun toJson(items: List<ChannelItem>): String {
        val arr = JSONArray()

        items.forEach { item ->
            arr.put(itemToJson(item))
        }

        return arr.toString()
    }

    private fun partToJson(
        p: FilePart
    ): JSONObject = JSONObject().apply {

        put("original_name", p.originalName)
        put("size", p.size)
        put("chat_id", p.chatId)
        put("message_id", p.messageId)
        put("label", p.label)
    }

    private fun itemToJson(
        item: ChannelItem
    ): JSONObject = JSONObject().apply {

        put(
            "type",
            if (item.type == ItemType.MOVIE) {
                "movie"
            } else {
                "series"
            }
        )

        put("title", item.title)

        put(
            "year",
            item.year ?: JSONObject.NULL
        )

        put(
            "imdb_id",
            item.imdbId ?: JSONObject.NULL
        )

        put(
            "poster",
            item.posterUrl ?: JSONObject.NULL
        )

        put(
            "total_size",
            item.totalSize
        )

        put(
            "overview",
            item.overview ?: JSONObject.NULL
        )

        put(
            "rating",
            item.rating ?: JSONObject.NULL
        )

        put(
            "runtime_minutes",
            item.runtimeMinutes ?: JSONObject.NULL
        )

        val genresArr = JSONArray()

        item.genres.forEach {
            genresArr.put(it)
        }

        put("genres", genresArr)

        val castArr = JSONArray()

        item.cast.forEach {
            castArr.put(it)
        }

        put("cast", castArr)

        val partsArr = JSONArray()

        item.parts.forEach {
            partsArr.put(partToJson(it))
        }

        put("parts", partsArr)

        val episodesArr = JSONArray()

        item.episodes.forEach { ep ->

            val epObj = JSONObject()

            epObj.put(
                "season",
                ep.season
            )

            epObj.put(
                "episode",
                ep.episode
            )

            if (
                ep.episodeEnd != null &&
                ep.episodeEnd > ep.episode
            ) {
                epObj.put(
                    "episode_end",
                    ep.episodeEnd
                )
            } else {
                epObj.put(
                    "episode_end",
                    JSONObject.NULL
                )
            }

            epObj.put(
                "total_size",
                ep.totalSize
            )

            epObj.put(
                "label",
                ep.label ?: JSONObject.NULL
            )

            val epParts =
                JSONArray()

            ep.parts.forEach {
                epParts.put(
                    partToJson(it)
                )
            }

            epObj.put(
                "parts",
                epParts
            )

            episodesArr.put(
                epObj
            )
        }

        put(
            "episodes",
            episodesArr
        )
    }

    private data class LeafUnit(
        val parts: List<FilePart>,
        val totalSize: Long,
        val parsed: TitleParser.Parsed
    )

    private suspend fun build(
        chatId: Long
    ): List<ChannelItem> {

        FileLogger.log(
            "ChannelCatalogBuilder: starting build for chatId=$chatId"
        )

        val client =
            TelegramClient.rawClient()

        suspendCancellableCoroutine<Unit> { cont ->
            client.send(
                TdApi.GetChat(chatId)
            ) {
                cont.resume(Unit)
            }
        }

        val grouped =
            LinkedHashMap<
                String,
                MutableList<Triple<String, Long, Long>>
            >()

        var fromMessageId = 0L

        var fetched = 0

        val maxMessages = 1500

        while (fetched < maxMessages) {

            val batchSize =
                minOf(
                    100,
                    maxMessages - fetched
                )

            val messages =
                suspendCancellableCoroutine<TdApi.Messages> { cont ->

                    client.send(
                        TdApi.GetChatHistory(
                            chatId,
                            fromMessageId,
                            0,
                            batchSize,
                            false
                        )
                    ) { result ->

                        if (
                            result is TdApi.Messages
                        ) {
                            cont.resume(result)
                        } else {
                            cont.resumeWithException(
                                RuntimeException(
                                    "GetChatHistory failed: $result"
                                )
                            )
                        }
                    }
                }

            val batch =
                messages.messages
                    ?.filterNotNull()
                    ?: emptyList()

            FileLogger.log(
                "ChannelCatalogBuilder: fetched batch of ${batch.size} messages"
            )

            if (batch.isEmpty()) {
                break
            }

            for (message in batch) {

                val (fileName, fileSize) =
                    when (
                        val content =
                            message.content
                    ) {

                        is TdApi.MessageVideo -> {
                            (
                                content.video.fileName
                                    .ifBlank {
                                        "unknown.mp4"
                                    }
                            ) to
                                content.video.video.size
                        }

                        is TdApi.MessageDocument -> {
                            (
                                content.document.fileName
                                    .ifBlank {
                                        "unknown"
                                    }
                            ) to
                                content.document.document.size
                        }

                        else -> continue
                    }

                val baseName =
                    splitFileSuffix.replace(
                        fileName,
                        ""
                    )

                val key =
                    baseName.lowercase()

                grouped
                    .getOrPut(key) {
                        mutableListOf()
                    }
                    .add(
                        Triple(
                            fileName,
                            fileSize.toLong(),
                            message.id
                        )
                    )
            }

            fetched += batch.size

            fromMessageId =
                batch.last().id
        }

        val leaves =
            grouped.map { (baseKey, fileEntries) ->

                val sorted =
                    fileEntries.sortedBy {
                        it.first
                    }

                val multi =
                    sorted.size > 1

                val parts =
                    sorted.mapIndexed {
                        index,
                        (name, size, msgId) ->

                        FilePart(
                            originalName = name,
                            size = size,
                            chatId = chatId,
                            messageId = msgId,
                            label =
                                if (multi) {
                                    "Part ${index + 1}"
                                } else {
                                    ""
                                }
                        )
                    }

                val titleInput =
                    trailingContainerExt.replace(
                        baseKey,
                        ""
                    )

                val parsed =
                    TitleParser.parse(
                        titleInput
                    )

                LeafUnit(
                    parts = parts,
                    totalSize =
                        parts.sumOf {
                            it.size
                        },
                    parsed = parsed
                )
            }

        val rawMovieLeaves =
            leaves.filter {
                !it.parsed.isEpisode
            }

        val episodeLeaves =
            leaves.filter {
                it.parsed.isEpisode
            }

        // --------------------------------------------------------
        // "Combined" reroute.
        //
        // Some releases package a WHOLE series/season as one single
        // video file with no S0xE0x marker anywhere in the name, only
        // the word "combined" (e.g. how the user's actual "Pritam And
        // Pedro ... COMBINED" file showed up). Without this, such a
        // leaf has isEpisode == false and silently gets treated - and
        // TMDB-movie-searched - as a movie, which is exactly why it
        // never gets a poster or plot: it isn't one.
        //
        // These leaves have season == null / episode == null, which
        // the series-building loop below already falls back to
        // season=1/episode=1 for, so no further special-casing is
        // needed there - we just need to route them into
        // seriesGroups instead of movieLeaves.
        // --------------------------------------------------------

        val movieLeaves =
            rawMovieLeaves.filter {
                !it.parsed.hasCombinedMarker
            }

        val combinedAsSeriesLeaves =
            rawMovieLeaves.filter {
                it.parsed.hasCombinedMarker
            }

        val seriesGroups =
            LinkedHashMap<
                String,
                MutableList<LeafUnit>
            >()

        (episodeLeaves + combinedAsSeriesLeaves).forEach { leaf ->

            val key =
                leaf.parsed.cleanTitle
                    .lowercase()
                    .trim()

            seriesGroups
                .getOrPut(key) {
                    mutableListOf()
                }
                .add(leaf)
        }

        val result =
            mutableListOf<ChannelItem>()

        // --------------------------------------------------------
        // MOVIES
        // --------------------------------------------------------

        // Leaves that Gemini reclassifies as series get diverted here
        // instead of being added as (badly-matched) movies below.
        val geminiReroutedToSeries =
            mutableListOf<
                Pair<LeafUnit, GeminiClient.Classification>
            >()

        for (leaf in movieLeaves) {

            val parsed =
                leaf.parsed

            var match =
                TmdbClient.searchMovie(
                    parsed.cleanTitle,
                    parsed.year
                )

            // Optional, opt-in second opinion. Only runs when a Gemini
            // API key has been configured AND the plain TMDB movie
            // search above came up empty - never on the happy path, so
            // this never adds cost/latency to the common case.
            var geminiMatch: GeminiClient.Classification? = null

            if (match == null && GeminiClient.isConfigured()) {

                val rawName =
                    leaf.parts.firstOrNull()?.originalName
                        ?: parsed.cleanTitle

                geminiMatch =
                    GeminiClient.classify(rawName)

                if (
                    geminiMatch != null &&
                    geminiMatch.type == GeminiClient.MediaType.SERIES
                ) {
                    geminiReroutedToSeries.add(
                        leaf to geminiMatch
                    )
                    continue
                }

                if (geminiMatch != null) {
                    match =
                        TmdbClient.searchMovie(
                            geminiMatch.title,
                            geminiMatch.year
                                ?: parsed.year
                        )
                }
            }

            result.add(
                ChannelItem(
                    type = ItemType.MOVIE,

                    title =
                        match?.title
                            ?: geminiMatch?.title
                            ?: parsed.cleanTitle,

                    year =
                        match?.year
                            ?: geminiMatch?.year
                            ?: parsed.year,

                    imdbId =
                        match?.imdbId,

                    posterUrl =
                        match?.posterUrl,

                    totalSize =
                        leaf.totalSize,

                    parts =
                        leaf.parts,

                    episodes =
                        emptyList(),

                    overview =
                        match?.overview,

                    rating =
                        match?.rating,

                    runtimeMinutes =
                        match?.runtimeMinutes,

                    genres =
                        match?.genres
                            ?: emptyList(),

                    cast =
                        match?.cast
                            ?: emptyList()
                )
            )
        }

        // --------------------------------------------------------
        // Fold Gemini-reclassified "actually a series" leaves into
        // the same seriesGroups map the combined-reroute above uses,
        // so they go through identical grouping/TMDB-TV logic rather
        // than a separate one-off code path.
        // --------------------------------------------------------

        geminiReroutedToSeries.forEach { (leaf, classification) ->

            val key =
                classification.title
                    .lowercase()
                    .trim()

            seriesGroups
                .getOrPut(key) {
                    mutableListOf()
                }
                .add(leaf)
        }

        // --------------------------------------------------------
        // SERIES
        // --------------------------------------------------------

        for (
            (_, leavesForSeries)
            in seriesGroups
        ) {

            val displayTitle =
                leavesForSeries
                    .first()
                    .parsed
                    .cleanTitle

            val displayYear =
                leavesForSeries
                    .mapNotNull {
                        it.parsed.year
                    }
                    .firstOrNull()

            val match =
                TmdbClient.searchTv(
                    displayTitle,
                    displayYear
                )

            val episodes =
                leavesForSeries
                    .sortedWith(
                        compareBy<LeafUnit> {
                            it.parsed.season
                                ?: 1
                        }.thenBy {
                            it.parsed.episode
                                ?: 1
                        }.thenBy {
                            it.parsed.episodeEnd
                                ?: it.parsed.episode
                                ?: 1
                        }
                    )
                    .map { leaf ->

                        // No real season/episode numbers means this leaf
                        // only got here via the "combined" reroute or the
                        // Gemini series-reclassification above - label it
                        // honestly instead of pretending it's "Episode 1".
                        val isWholeSeriesFile =
                            leaf.parsed.season == null &&
                                leaf.parsed.episode == null

                        EpisodeEntry(
                            season =
                                leaf.parsed.season
                                    ?: 1,

                            episode =
                                leaf.parsed.episode
                                    ?: 1,

                            episodeEnd =
                                leaf.parsed.episodeEnd,

                            totalSize =
                                leaf.totalSize,

                            parts =
                                leaf.parts,

                            label =
                                if (isWholeSeriesFile) {
                                    "Full Series (Single File)"
                                } else {
                                    null
                                }
                        )
                    }

            result.add(
                ChannelItem(
                    type =
                        ItemType.SERIES,

                    title =
                        match?.title
                            ?: displayTitle,

                    year =
                        match?.year
                            ?: displayYear,

                    imdbId =
                        match?.imdbId,

                    posterUrl =
                        match?.posterUrl,

                    totalSize =
                        0L,

                    parts =
                        emptyList(),

                    episodes =
                        episodes,

                    overview =
                        match?.overview,

                    rating =
                        match?.rating,

                    runtimeMinutes =
                        match?.runtimeMinutes,

                    genres =
                        match?.genres
                            ?: emptyList(),

                    cast =
                        match?.cast
                            ?: emptyList()
                )
            )
        }

        FileLogger.log(
            "ChannelCatalogBuilder: complete. " +
                "${movieLeaves.size} movies, " +
                "${seriesGroups.size} series."
        )

        return result
    }
}