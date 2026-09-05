package com.example.tgserver

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val label: String? = null,
    // Sibling .srt/.vtt/.ass/.ssa files uploaded alongside this episode's
    // video, matched by filename (see subtitleBaseKeyCandidates() in
    // sync()). Empty when nothing matched - not every release has these.
    val subtitles: List<FilePart> = emptyList()
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
    val cast: List<String> = emptyList(),
    // Only meaningful for movies (type == MOVIE) - a series' subtitles
    // live per-episode on EpisodeEntry.subtitles instead, since each
    // episode is its own file with its own possible sibling subtitle.
    val subtitles: List<FilePart> = emptyList()
)

data class CatalogStats(
    val movieCount: Int,
    val seriesCount: Int,
    val episodeCount: Int,
    val totalSizeBytes: Long,
    val lastSyncTimestamp: Long
)

/**
 * Builds and maintains the movie/series catalog for a Telegram channel.
 *
 * IMPORTANT - incremental sync + on-disk persistence:
 *
 * The old version of this object re-walked the ENTIRE channel history and
 * re-ran TMDB/Gemini lookups for every single file on every single build -
 * including the automatic one that fired every time StreamService started.
 * That's slow and wastes API calls on titles we'd already resolved.
 *
 * Now the built catalog (including all TMDB/Gemini enrichment) is persisted
 * to disk as JSON, keyed by the highest Telegram message id seen so far. A
 * later sync only walks messages NEWER than that watermark, classifies just
 * those, and merges the result into the persisted items - new movies are
 * appended, new episodes are folded into their existing series entry by
 * title match, and a series that already has a resolved TMDB match doesn't
 * get re-queried. See sync()/classifyAndMerge() below.
 *
 * A true from-scratch walk is still available (fullRebuild = true in
 * getCatalog) for recovery, or for picking up edits/renames to messages
 * that were already synced in the past - the incremental path never
 * re-inspects a message id it has already processed, so it can't detect
 * those on its own.
 */
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

    private val subtitleExtension =
        Regex(
            """\.(srt|vtt|ass|ssa)$""",
            RegexOption.IGNORE_CASE
        )

    // Common trailing "which language is this" token some uploaders
    // append to a subtitle's filename that a video file never has, e.g.
    // "Movie.2024.1080p.English.srt" vs. the video's own
    // "Movie.2024.1080p.mkv". Stripped as a second attempt when a
    // subtitle's filename doesn't match a video's base key outright.
    private val subtitleLanguageTag =
        Regex(
            """\.(english|eng|hindi|hin|multi|dual|esub|esubs|sub|subs)$""",
            RegexOption.IGNORE_CASE
        )

    @Volatile
    private var appContext: Context? = null

    /** Call once (MainActivity/StreamService onCreate) so we can read/write the on-disk cache. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private var cache: List<ChannelItem>? = null

    private var cachedChatId: Long? = null

    private var cachedLastSyncTimestamp: Long = 0L

    private val buildLock = Mutex()

    @Volatile
    private var isBuilding = false

    fun peekCache(chatId: Long): List<ChannelItem>? {
        return if (cachedChatId == chatId) cache else null
    }

    fun getLastSyncTimestamp(): Long = cachedLastSyncTimestamp

    fun getCatalogStats(chatId: Long): CatalogStats? {
        val items = if (cachedChatId == chatId && cache != null) {
            cache
        } else {
            val persisted = loadPersisted(chatId) ?: return null
            cache = persisted.items
            cachedChatId = chatId
            cachedLastSyncTimestamp = persisted.lastSyncTimestamp
            persisted.items
        } ?: return null

        val movies = items.filter { it.type == ItemType.MOVIE }
        val series = items.filter { it.type == ItemType.SERIES }
        val totalMovieSize = movies.sumOf { it.totalSize }
        val totalSeriesSize = series.sumOf { s -> s.episodes.sumOf { it.totalSize } }
        val totalEpisodes = series.sumOf { it.episodes.size }
        return CatalogStats(
            movieCount = movies.size,
            seriesCount = series.size,
            episodeCount = totalEpisodes,
            totalSizeBytes = totalMovieSize + totalSeriesSize,
            lastSyncTimestamp = cachedLastSyncTimestamp
        )
    }

    fun isCurrentlyBuilding(): Boolean =
        isBuilding

    /**
     * @param forceRefresh Run a sync right now (blocking) instead of only
     *   returning whatever's already cached. This is now always an
     *   INCREMENTAL sync (fast) unless [fullRebuild] is also set.
     * @param fullRebuild Ignore the persisted cache/watermark entirely and
     *   walk the channel from scratch, exactly like the old behavior. Use
     *   sparingly - it re-spends every TMDB/Gemini call. Implies a sync
     *   runs even if forceRefresh is false.
     */
    suspend fun getCatalog(
        chatId: Long,
        forceRefresh: Boolean = false,
        fullRebuild: Boolean = false
    ): List<ChannelItem> {

        if (!forceRefresh &&
            !fullRebuild &&
            cachedChatId == chatId &&
            cache != null
        ) {
            return cache!!
        }

        return buildLock.withLock {
            if (!forceRefresh &&
                !fullRebuild &&
                cachedChatId == chatId &&
                cache != null
            ) {
                return@withLock cache!!
            }

            isBuilding = true

            try {

                val persisted =
                    if (fullRebuild) null else loadPersisted(chatId)

                // Instant warm start: if nothing is in memory yet for this
                // chat (fresh process), seed it from what we just loaded
                // off disk before touching Telegram at all, so a
                // concurrent /catalog request (peekCache) has something
                // real to serve while the sync below runs.
                if ((cachedChatId != chatId || cache == null) && persisted != null) {
                    cache = persisted.items
                    cachedChatId = chatId
                    cachedLastSyncTimestamp = persisted.lastSyncTimestamp
                    FileLogger.log(
                        "ChannelCatalogBuilder: warm-started from disk cache for chatId=$chatId (${persisted.items.size} item(s))"
                    )
                }

                val synced = sync(chatId, persisted)

                cache = synced.items
                cachedChatId = chatId
                cachedLastSyncTimestamp = synced.lastSyncTimestamp

                savePersisted(chatId, synced)

                synced.items

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

        val itemSubsArr = JSONArray()

        item.subtitles.forEach {
            itemSubsArr.put(partToJson(it))
        }

        put("subtitles", itemSubsArr)

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

            val epSubsArr = JSONArray()

            ep.subtitles.forEach {
                epSubsArr.put(partToJson(it))
            }

            epObj.put(
                "subtitles",
                epSubsArr
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

    // ------------------------------------------------------------
    // Deserialization - mirrors itemToJson()/partToJson() field for
    // field, since this is what lets a persisted catalog be loaded back
    // into real ChannelItem objects on the next app start.
    // ------------------------------------------------------------

    private fun partFromJson(o: JSONObject?): FilePart? {
        o ?: return null
        return try {
            FilePart(
                originalName = o.optString("original_name"),
                size = o.optLong("size"),
                chatId = o.optLong("chat_id"),
                messageId = o.optLong("message_id"),
                label = o.optString("label")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun episodeFromJson(o: JSONObject?): EpisodeEntry? {
        o ?: return null
        return try {
            val partsArr = o.optJSONArray("parts") ?: JSONArray()
            val parts = (0 until partsArr.length()).mapNotNull { partFromJson(partsArr.optJSONObject(it)) }
            val subsArr = o.optJSONArray("subtitles") ?: JSONArray()
            val subs = (0 until subsArr.length()).mapNotNull { partFromJson(subsArr.optJSONObject(it)) }
            EpisodeEntry(
                season = o.optInt("season", 1),
                episode = o.optInt("episode", 1),
                episodeEnd = if (o.isNull("episode_end")) null else o.optInt("episode_end"),
                totalSize = o.optLong("total_size"),
                parts = parts,
                label = if (o.isNull("label")) null else o.optString("label"),
                subtitles = subs
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun itemFromJson(o: JSONObject?): ChannelItem? {
        o ?: return null
        return try {
            val partsArr = o.optJSONArray("parts") ?: JSONArray()
            val parts = (0 until partsArr.length()).mapNotNull { partFromJson(partsArr.optJSONObject(it)) }

            val itemSubsArr = o.optJSONArray("subtitles") ?: JSONArray()
            val itemSubs = (0 until itemSubsArr.length()).mapNotNull { partFromJson(itemSubsArr.optJSONObject(it)) }

            val episodesArr = o.optJSONArray("episodes") ?: JSONArray()
            val episodes = (0 until episodesArr.length()).mapNotNull { episodeFromJson(episodesArr.optJSONObject(it)) }

            val genresArr = o.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until genresArr.length()).map { genresArr.optString(it) }

            val castArr = o.optJSONArray("cast") ?: JSONArray()
            val cast = (0 until castArr.length()).map { castArr.optString(it) }

            ChannelItem(
                type = if (o.optString("type") == "movie") ItemType.MOVIE else ItemType.SERIES,
                title = o.optString("title"),
                year = if (o.isNull("year")) null else o.optInt("year"),
                imdbId = if (o.isNull("imdb_id")) null else o.optString("imdb_id"),
                posterUrl = if (o.isNull("poster")) null else o.optString("poster"),
                totalSize = o.optLong("total_size"),
                parts = parts,
                episodes = episodes,
                subtitles = itemSubs,
                overview = if (o.isNull("overview")) null else o.optString("overview"),
                rating = if (o.isNull("rating")) null else o.optDouble("rating"),
                runtimeMinutes = if (o.isNull("runtime_minutes")) null else o.optInt("runtime_minutes"),
                genres = genres,
                cast = cast
            )
        } catch (e: Exception) {
            FileLogger.error("ChannelCatalogBuilder: failed to parse a cached item, skipping it", e)
            null
        }
    }

    private data class PersistedCatalog(
        val lastMessageId: Long,
        val items: List<ChannelItem>,
        val lastSyncTimestamp: Long = System.currentTimeMillis()
    )

    private fun cacheFile(chatId: Long): File? {
        val dir = appContext?.filesDir ?: return null
        return File(dir, "catalog_cache_$chatId.json")
    }

    private fun loadPersisted(chatId: Long): PersistedCatalog? {
        val file = cacheFile(chatId) ?: return null
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val lastMessageId = obj.optLong("last_message_id", 0L)
            val lastSyncTimestamp = obj.optLong("last_sync_timestamp", file.lastModified())
            val itemsArr = obj.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { itemFromJson(itemsArr.optJSONObject(it)) }
            FileLogger.log(
                "ChannelCatalogBuilder: loaded persisted cache for chatId=$chatId " +
                    "(${items.size} item(s), lastMessageId=$lastMessageId, lastSync=$lastSyncTimestamp)"
            )
            PersistedCatalog(lastMessageId, items, lastSyncTimestamp)
        } catch (e: Exception) {
            FileLogger.error("ChannelCatalogBuilder: failed to load persisted cache for chatId=$chatId, starting fresh", e)
            null
        }
    }

    private fun savePersisted(chatId: Long, catalog: PersistedCatalog) {
        val file = cacheFile(chatId) ?: return
        try {
            val obj = JSONObject()
            obj.put("last_message_id", catalog.lastMessageId)
            obj.put("last_sync_timestamp", catalog.lastSyncTimestamp)
            val arr = JSONArray()
            catalog.items.forEach { arr.put(itemToJson(it)) }
            obj.put("items", arr)
            file.writeText(obj.toString())
            FileLogger.log(
                "ChannelCatalogBuilder: persisted ${catalog.items.size} item(s) for chatId=$chatId " +
                    "(lastMessageId=${catalog.lastMessageId})"
            )
        } catch (e: Exception) {
            FileLogger.error("ChannelCatalogBuilder: failed to persist cache for chatId=$chatId", e)
        }
    }

    private data class LeafUnit(
        val parts: List<FilePart>,
        val totalSize: Long,
        val parsed: TitleParser.Parsed,
        val subtitles: List<FilePart> = emptyList()
    )

    /**
     * Walks Telegram chat history for new files - everything newer than
     * persisted.lastMessageId, or (if persisted is null) a bounded walk
     * from the very start, exactly like the old build() did - then
     * classifies just those new leaves and merges them into whatever was
     * already cached.
     */
    private suspend fun sync(
        chatId: Long,
        persisted: PersistedCatalog?
    ): PersistedCatalog {

        FileLogger.log(
            "ChannelCatalogBuilder: sync starting for chatId=$chatId " +
                if (persisted != null) {
                    "(incremental, since messageId=${persisted.lastMessageId}, ${persisted.items.size} cached item(s))"
                } else {
                    "(full build - no cache yet)"
                }
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

        // Subtitle files (.srt/.vtt/.ass/.ssa) get pulled out of the
        // normal grouping above and matched to a video's base key
        // separately, in a pass after the walk below - otherwise a
        // subtitle uploaded as its own message becomes its own bogus
        // "movie" entry (TitleParser has no idea it's not media).
        val rawSubtitles =
            mutableListOf<Triple<String, Long, Long>>()

        var fromMessageId = 0L
        var fetched = 0
        // A first-ever/full build still caps out at 1500 like before. An
        // incremental sync gets a much larger safety cap since it's meant
        // to stop naturally as soon as it reaches an already-known
        // message id - the cap here only guards against a corrupt/very
        // old watermark causing a runaway walk, it's not expected to be
        // hit in normal day-to-day use.
        val maxMessages = if (persisted != null) 5000 else 1500
        var newHighWaterMark = persisted?.lastMessageId ?: 0L
        var isFirstBatch = true
        var reachedKnownMessages = false

        while (fetched < maxMessages && !reachedKnownMessages) {

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

            if (isFirstBatch) {
                // The newest message id in the very first batch (fetched
                // from fromMessageId=0, i.e. "start from the newest") is
                // the new watermark for this sync, regardless of how far
                // back we actually need to walk this time.
                newHighWaterMark = maxOf(newHighWaterMark, batch.first().id)
                isFirstBatch = false
            }

            for (message in batch) {

                if (persisted != null && message.id <= persisted.lastMessageId) {
                    // Everything from here backwards was already covered
                    // by a previous sync - stop walking entirely.
                    reachedKnownMessages = true
                    break
                }

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

                if (subtitleExtension.containsMatchIn(fileName)) {
                    rawSubtitles.add(
                        Triple(
                            fileName,
                            fileSize.toLong(),
                            message.id
                        )
                    )
                    continue
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

            if (batch.isNotEmpty()) {
                fromMessageId = batch.last().id
            }
        }

        FileLogger.log(
            "ChannelCatalogBuilder: sync found ${grouped.values.sumOf { it.size }} new file message(s) " +
                "across ${grouped.size} group(s)"
        )

        // Match each subtitle to a video's group key: first try its
        // filename as-is (minus the subtitle extension), then try again
        // with a trailing language tag stripped too (see
        // subtitleLanguageTag's comment). Anything that still doesn't
        // match any group this sync knows about is dropped rather than
        // becoming its own bogus catalog entry.
        val subtitlesByGroupKey = LinkedHashMap<String, MutableList<FilePart>>()
        var unmatchedSubtitleCount = 0

        rawSubtitles.forEach { (fileName, fileSize, msgId) ->
            val withoutSubExt = subtitleExtension.replace(fileName, "")
            val candidateKeys =
                listOf(
                    withoutSubExt.lowercase(),
                    subtitleLanguageTag.replace(withoutSubExt, "").lowercase()
                ).distinct()

            val matchedKey = candidateKeys.firstOrNull { grouped.containsKey(it) }

            if (matchedKey != null) {
                subtitlesByGroupKey
                    .getOrPut(matchedKey) { mutableListOf() }
                    .add(
                        FilePart(
                            originalName = fileName,
                            size = fileSize,
                            chatId = chatId,
                            messageId = msgId,
                            label = ""
                        )
                    )
            } else {
                unmatchedSubtitleCount++
            }
        }

        if (rawSubtitles.isNotEmpty()) {
            FileLogger.log(
                "ChannelCatalogBuilder: matched ${rawSubtitles.size - unmatchedSubtitleCount}/${rawSubtitles.size} " +
                    "subtitle file(s) to a video; $unmatchedSubtitleCount unmatched (skipped, not a video's sibling)"
            )
        }

        val newLeaves =
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
                    parsed = parsed,
                    subtitles = subtitlesByGroupKey[baseKey] ?: emptyList()
                )
            }

        val mergedItems =
            classifyAndMerge(
                newLeaves,
                persisted?.items ?: emptyList()
            )

        FileLogger.log(
            "ChannelCatalogBuilder: sync complete for chatId=$chatId. " +
                "${mergedItems.size} total item(s), lastMessageId=$newHighWaterMark"
        )

        return PersistedCatalog(
            lastMessageId = newHighWaterMark,
            items = mergedItems,
            lastSyncTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Classifies only the NEW leaves found by sync() (movie vs series vs
     * "combined" vs Gemini-reclassified, same rules as always) and merges
     * the result into whatever items already existed:
     *  - existing movies are left untouched
     *  - new movie leaves become new movie items
     *  - a new episode/combined/Gemini-series leaf whose title matches an
     *    EXISTING series (case-insensitive) gets folded into that item's
     *    episode list instead of creating a duplicate series entry
     *  - a series that already has a resolved TMDB match (imdbId != null)
     *    is not re-queried - only brand-new series spend a TMDB call
     */
    private fun classifyAndMerge(
        newLeaves: List<LeafUnit>,
        existingItems: List<ChannelItem>
    ): List<ChannelItem> {

        if (newLeaves.isEmpty()) {
            return existingItems.sortedByDescending { item ->
                val movieMaxMsg = item.parts.maxOfOrNull { it.messageId } ?: 0L
                val seriesMaxMsg = item.episodes.flatMap { it.parts }.maxOfOrNull { it.messageId } ?: 0L
                maxOf(movieMaxMsg, seriesMaxMsg)
            }
        }

        val rawMovieLeaves =
            newLeaves.filter {
                !it.parsed.isEpisode
            }

        val episodeLeaves =
            newLeaves.filter {
                it.parsed.isEpisode
            }

        // --------------------------------------------------------
        // "Combined" reroute - see TitleParser.hasCombinedMarker. A
        // release that packages a WHOLE series/season as one single file
        // with no S0xE0x marker gets routed to the series bucket instead
        // of being treated (and TMDB-movie-searched) as a movie.
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

        // Existing movies never get touched by a sync - a "new" movie
        // leaf always becomes its own item, same as a full build always
        // treated one movie file as one item.
        result.addAll(
            existingItems.filter {
                it.type == ItemType.MOVIE
            }
        )

        // Existing series, keyed by lowercase title, so new episodes can
        // be folded into the right one instead of creating a duplicate.
        val existingSeriesByKey =
            existingItems
                .filter {
                    it.type == ItemType.SERIES
                }
                .associateBy {
                    it.title.lowercase().trim()
                }
                .toMutableMap()

        // --------------------------------------------------------
        // MOVIES (new leaves only)
        // --------------------------------------------------------

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
                    title = match?.title ?: geminiMatch?.title ?: parsed.cleanTitle,
                    year = match?.year ?: geminiMatch?.year ?: parsed.year,
                    imdbId = match?.imdbId,
                    posterUrl = match?.posterUrl,
                    totalSize = leaf.totalSize,
                    parts = leaf.parts,
                    episodes = emptyList(),
                    subtitles = leaf.subtitles,
                    overview = match?.overview,
                    rating = match?.rating,
                    runtimeMinutes = match?.runtimeMinutes,
                    genres = match?.genres ?: emptyList(),
                    cast = match?.cast ?: emptyList()
                )
            )
        }

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
        // SERIES - merge new episodes into an existing entry by title,
        // or create a fresh entry if none matched.
        // --------------------------------------------------------

        for ((key, leavesForSeries) in seriesGroups) {

            val existing = existingSeriesByKey[key]

            val displayTitle =
                existing?.title
                    ?: leavesForSeries.first().parsed.cleanTitle

            val displayYear =
                existing?.year
                    ?: leavesForSeries.mapNotNull { it.parsed.year }.firstOrNull()

            // Only spend a TMDB call if we don't already have a resolved
            // match for this series - the whole point of caching.
            val match =
                if (existing?.imdbId != null) {
                    null
                } else {
                    TmdbClient.searchTv(displayTitle, displayYear)
                }

            val newEpisodes =
                leavesForSeries
                    .sortedWith(
                        compareBy<LeafUnit> {
                            it.parsed.season ?: 1
                        }.thenBy {
                            it.parsed.episode ?: 1
                        }.thenBy {
                            it.parsed.episodeEnd ?: it.parsed.episode ?: 1
                        }
                    )
                    .map { leaf ->

                        val isWholeSeriesFile =
                            leaf.parsed.season == null &&
                                leaf.parsed.episode == null

                        EpisodeEntry(
                            season = leaf.parsed.season ?: 1,
                            episode = leaf.parsed.episode ?: 1,
                            episodeEnd = leaf.parsed.episodeEnd,
                            totalSize = leaf.totalSize,
                            parts = leaf.parts,
                            label =
                                if (isWholeSeriesFile) {
                                    "Full Series (Single File)"
                                } else {
                                    null
                                },
                            subtitles = leaf.subtitles
                        )
                    }

            val combinedEpisodes =
                ((existing?.episodes ?: emptyList()) + newEpisodes)
                    .sortedWith(
                        compareBy<EpisodeEntry> { it.season }
                            .thenBy { it.episode }
                            .thenBy { it.episodeEnd ?: it.episode }
                    )

            existingSeriesByKey[key] =
                ChannelItem(
                    type = ItemType.SERIES,
                    title = match?.title ?: displayTitle,
                    year = match?.year ?: displayYear,
                    imdbId = match?.imdbId ?: existing?.imdbId,
                    posterUrl = match?.posterUrl ?: existing?.posterUrl,
                    totalSize = 0L,
                    parts = emptyList(),
                    episodes = combinedEpisodes,
                    overview = match?.overview ?: existing?.overview,
                    rating = match?.rating ?: existing?.rating,
                    runtimeMinutes = match?.runtimeMinutes ?: existing?.runtimeMinutes,
                    genres = match?.genres ?: existing?.genres ?: emptyList(),
                    cast = match?.cast ?: existing?.cast ?: emptyList()
                )
        }

        result.addAll(existingSeriesByKey.values)

        // Sort items so newest uploads appear first (Telegram messageId is monotonically increasing)
        result.sortByDescending { item ->
            val movieMaxMsg = item.parts.maxOfOrNull { it.messageId } ?: 0L
            val seriesMaxMsg = item.episodes.flatMap { it.parts }.maxOfOrNull { it.messageId } ?: 0L
            maxOf(movieMaxMsg, seriesMaxMsg)
        }

        return result
    }
}