package com.kuronime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.USER_AGENT
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.ArrayList

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    private var animekuUrl = "https://animeku.org"
    override var name = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val KEY = "3&!Z0M,VIZ;dZW=="
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/%d/?status=ongoing&order=update" to "Ongoing Anime",
        "$mainUrl/anime/page/%d/?status=completed&order=update" to "Complete Anime",
        "$mainUrl/anime/page/%d/?order=latest" to "New Anime Series",
        "$mainUrl/anime/page/%d/?order=popular" to "Most Popular",
        "$mainUrl/anime/page/%d/?type=Movie&order=update" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        val url = request.data.replace("%d", page.toString())
        val req = app.get(url)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = document.select(".listupd article").map {
            it.toSearchResult(mainUrl)
        }
        
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun getProperAnimeLink(uri: String, baseUrl: String): String {
        if (uri.contains("/anime/")) return uri
        
        val slug = uri.trimEnd('/').substringAfterLast("/")
        val title = when {
            slug.contains("-episode") && !slug.contains("-movie") -> 
                Regex("nonton-(.+)-episode").find(slug)?.groupValues?.get(1) ?: slug
            slug.contains("-movie") -> 
                Regex("nonton-(.+)-movie").find(slug)?.groupValues?.get(1) ?: slug
            else -> slug
        }

        return "$baseUrl/anime/$title"
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResult(baseUrl: String): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString(), baseUrl)
        val title = this.selectFirst("h2, .bsuxtt, .tt > h4, .entry-title")?.text()?.trim() ?: "Unknown"
        
        val img = this.selectFirst("img[itemprop=image]") ?: this.select("img").lastOrNull()
        val posterUrl = fixUrlNull(img?.getImageAttr())
        
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span, .bt > .type")?.text().toString())
        
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        LicenseClient.trackActivity(name, "SEARCH", query)
        val currentBaseUrl = app.get(mainUrl).url
        return app.post(
            "$currentBaseUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle ?: "",
                it.postLink ?: return@mapNotNull null,
                TvType.Anime
            ) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.requireLicense(name, "LOAD", url)
        val document = app.get(url).document
        val currentBaseUrl = getBaseUrl(url)

        val title = document.selectFirst(".entry-title")?.text().toString().trim()
        val poster = document.selectFirst("div.l[itemprop=image] > img, .l > img")?.getImageAttr()
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val typeString = document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")?.trim() ?: "tv"
        val type = getType(typeString.lowercase())

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = Regex("\\d, (\\d*)").find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        
        val statusElement = document.selectFirst(".infodetail > ul > li:nth-child(3)")
        val statusText = statusElement?.ownText()?.replace(Regex("\\W"), "") ?: ""
        val status = getStatus(statusText)
        
        val description = document.select("span.const > p").text()
        
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.bixbox.bxcl > ul > li").amap { element ->
            val link = element.selectFirst("a")?.attr("href") ?: return@amap null
            val name = element.selectFirst("a")?.text() ?: return@amap null
            var episodeNum = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            
            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
            }

            newEpisode(link) { 
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.filterNotNull().reversed()

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview
        
        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch(_:Throwable){}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.requireLicense(name, "PLAY", data)

        val req = app.get(data)
        val document = req.document
        val currentBaseUrl = getBaseUrl(req.url)
        val visitedUrls = linkedSetOf<String>()
        val playerPath = "$currentBaseUrl/utils/player/"

        // 1. Try parsing JSON API sources from animeku.org
        // Search multiple script patterns for the episode ID
        val scriptData = document.select("script").map { it.data() }
            .firstOrNull {
                it.contains("_0xa100d42aa") ||
                it.contains("is_singular") ||
                it.contains("postID")
            }

        val id = scriptData
            ?.let {
                it.substringAfter("_0xa100d42aa = \"", "").substringBefore("\";")
                    .takeIf { v -> v.isNotBlank() }
                    ?: it.substringAfter("\"postID\":\"", "").substringBefore("\"").takeIf { v -> v.isNotBlank() }
            }

        if (!id.isNullOrBlank()) {
            val servers = safeApiCall {
                app.post(
                    "$animekuUrl/api/v9/sources",
                    requestBody = """{"id":"$id"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
                    referer = "$currentBaseUrl/"
                ).parsedSafe<Servers>()
            }

            if (servers != null) {
                runAllAsync(
                    {
                        // Handle direct M3U8 stream source
                        val decrypt = AesHelper.cryptoAESHandler(
                            base64Decode(servers.src ?: return@runAllAsync),
                            KEY.toByteArray(),
                            false,
                            "AES/CBC/NoPadding"
                        )
                        val source = tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                        M3u8Helper.generateM3u8(
                            this.name,
                            source ?: return@runAllAsync,
                            "$animekuUrl/",
                            headers = mapOf("Origin" to animekuUrl)
                        ).forEach { link -> callback(link) }
                    },
                    {
                        // Handle mirror/embed providers — FIX: parse decrypt directly (no .toJsonFormat())
                        // Using .toJsonFormat() strips the outer JSON braces causing parse failure
                        val rawDecrypt = AesHelper.cryptoAESHandler(
                            base64Decode(servers.mirror ?: return@runAllAsync),
                            KEY.toByteArray(),
                            false,
                            "AES/CBC/NoPadding"
                        ) ?: return@runAllAsync

                        // Try direct parse first (like CS01.1), fallback to toJsonFormat
                        val mirrors = tryParseJson<Mirrors>(rawDecrypt)
                            ?: tryParseJson<Mirrors>(rawDecrypt.toJsonFormat())

                        mirrors?.embed?.map { embed ->
                            embed.value.amap { entry ->
                                val quality = getIndexQuality(embed.key.removePrefix("v"))
                                val serverName = entry.key
                                resolveMirrorLink(
                                    rawUrl = entry.value,
                                    referer = "$currentBaseUrl/",
                                    playerPath = playerPath,
                                    serverName = serverName,
                                    quality = quality,
                                    visitedUrls = visitedUrls,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )
                            }
                        }
                    }
                )
            }
        }

        // 2. Selector-based mirror extraction as additional source
        // Try server config first, then fall back to known kuronime selectors
        val cfg = LicenseClient.getSelectors(name)
        // Comprehensive selectors covering different kuronime site layouts
        val playerSelector = cfg?.playerSelector
            ?: ".mobius option, select.mirror option, .server option, #player option, .player-option option, option[data-em], option[data-iframe], option[value]"
        val playerAttr = cfg?.playerAttr ?: "value"
        // Default to true: kuronime typically base64-encodes mirror URLs in option values
        val useBase64 = cfg?.useBase64 ?: true

        document.select(playerSelector).amap { element ->
            safeApiCall {
                val rawText = element.text().trim()
                if (rawText.isBlank() || rawText.equals("select", ignoreCase = true)) return@safeApiCall

                val quality = getIndexQuality(rawText)
                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                val rawValue = element.attr(playerAttr).ifBlank {
                    // Also check alternative attributes if primary is blank
                    element.attr("data-em").ifBlank { element.attr("data-iframe") }
                }
                if (rawValue.isNotBlank()) {
                    val decodedUrl = if (useBase64) {
                        runCatching { base64Decode(rawValue) }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawValue
                    } else {
                        rawValue
                    }
                    if (decodedUrl.isNotBlank()) {
                        val candidates = decodeMirrorCandidates(decodedUrl)
                        candidates.forEach { candidate ->
                            resolveMirrorLink(
                                rawUrl = candidate,
                                referer = data,
                                playerPath = playerPath,
                                serverName = serverName,
                                quality = quality,
                                visitedUrls = visitedUrls,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }
            }
        }

        return true
    }

    private fun String.toJsonFormat(): String {
        val clean = this.replace("\u0000", "").trim()
        return if (clean.startsWith("\"")) clean.substringAfter("\"").substringBeforeLast("\"")
            .replace("\\\"", "\"") else clean
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        referer: String,
        playerPath: String,
        serverName: String,
        quality: Int?,
        visitedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = normalizeMirrorUrl(rawUrl) ?: return
        if (normalized.contains("statistic", true)) return
        if (!visitedUrls.add(normalized)) return

        when {
            isDirectMediaUrl(normalized) -> {
                emitDirectMediaLink(normalized, serverName, quality, referer, callback)
            }

            normalized.contains("${playerPath}popup", true) -> {
                val encodedUrl = normalized.substringAfter("url=", "").substringBefore("&")
                if (encodedUrl.isBlank()) return
                val realUrl = runCatching { URLDecoder.decode(encodedUrl, "UTF-8") }.getOrNull() ?: return
                resolveMirrorLink(
                    rawUrl = realUrl,
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("aghanim.xyz/tools/redirect/", true) -> {
                val id = normalized.substringAfter("id=").substringBefore("&token")
                if (id.isBlank()) return
                resolveMirrorLink(
                    rawUrl = "https://rasa-cintaku-semakin-berantai.xyz/v/$id",
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("player-kodir.aghanim.xyz", true) ||
                normalized.contains("${playerPath}kodir2", true) ||
                normalized.contains("${playerPath}framezilla", true) ||
                normalized.contains("uservideo.xyz", true) ||
                normalized.contains(playerPath, true) -> {
                val response = app.get(normalized, referer = referer)
                val text = response.text
                val playerDoc = response.document

                if (isCustomManagedServer(serverName) && tryPassMd5PatternDirect(
                        normalized,
                        serverName,
                        quality,
                        referer,
                        callback,
                        prefetchedPageText = text
                    )
                ) {
                    return
                }

                val nestedLinks = linkedSetOf<String>()

                val packedHtml = text.substringAfter("= `", "").substringBefore("`;", "")
                if (packedHtml.isNotBlank()) {
                    nestedLinks.addAll(
                        Jsoup.parse(packedHtml)
                            .select("source[src], video[src], iframe[src], a[href]")
                            .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                    )
                }

                nestedLinks.addAll(
                    playerDoc.select("source[src], video[src], iframe[src], a[href]")
                        .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                )

                Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .map { it.value.trim() }
                    .filter { shouldFollowNestedLink(it, playerPath) }
                    .forEach { nestedLinks.add(it) }

                if (nestedLinks.isEmpty()) {
                    loadFixedExtractor(
                        url = normalized,
                        serverName = serverName,
                        quality = quality,
                        referer = referer,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return
                }

                nestedLinks.forEach { nested ->
                    resolveMirrorLink(
                        rawUrl = nested,
                        referer = normalized,
                        playerPath = playerPath,
                        serverName = serverName,
                        quality = quality,
                        visitedUrls = visitedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }

            else -> {
                loadFixedExtractor(
                    url = normalized,
                    serverName = serverName,
                    quality = quality,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }
    }

    private fun Element.extractMirrorCandidates(): List<String> {
        val rawCandidates = listOf(
            attr("data-em"),
            attr("value"),
            attr("data-iframe"),
            attr("data-url"),
            attr("data-src")
        ).filter { it.isNotBlank() }

        val results = linkedSetOf<String>()
        rawCandidates.forEach { encoded ->
            results.addAll(decodeMirrorCandidates(encoded))
        }
        return results.toList()
    }

    private fun decodeMirrorCandidates(encodedData: String): List<String> {
        if (encodedData.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val clean = encodedData.trim().replace("\\u0026", "&")

        fun addUrl(raw: String?) {
            normalizeMirrorUrl(raw)?.let { candidates.add(it) }
        }

        fun parseBlob(blob: String) {
            if (blob.isBlank()) return
            addUrl(blob)
            val doc = Jsoup.parse(blob)
            doc.select("iframe[src], source[src], video[src], a[href]").forEach { el ->
                addUrl(el.attr("src").ifBlank { el.attr("href") })
            }
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(blob)
                .forEach { addUrl(it.value) }
        }

        parseBlob(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let(::parseBlob)
        runCatching { base64Decode(clean.replace("\\s".toRegex(), "")) }.getOrNull()?.let(::parseBlob)
        return candidates.toList()
    }

    private fun normalizeMirrorUrl(raw: String?): String? {
        return normalizeUrlFromBase(raw, mainUrl)
    }

    private fun normalizeUrlFromBase(raw: String?, baseUrl: String?): String? {
        val clean = raw?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.replace("\\u0026", "&")
            ?.trim()
            ?: return null
        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) {
            return null
        }

        fun resolveWithBase(path: String): String? {
            if (baseUrl.isNullOrBlank()) return null
            return runCatching { URI(baseUrl).resolve(path).toString() }.getOrNull()
        }

        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> resolveWithBase(clean) ?: runCatching { fixUrl(clean) }.getOrNull()
            else -> resolveWithBase(clean)
        }
    }

    private fun shouldFollowNestedLink(url: String, playerPath: String): Boolean {
        val lower = url.lowercase()
        if (isDirectMediaUrl(lower)) return true
        if (lower.contains(playerPath.lowercase())) return true
        val hostHints = listOf(
            "yourupload",
            "pixeldrain",
            "pompom",
            "pancal",
            "myvidplay",
            "mixdrop",
            "mp4upload",
            "uservideo",
            "aghanim"
        )
        return hostHints.any { lower.contains(it) }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)
    }

    private suspend fun emitDirectMediaLink(
        mediaUrl: String,
        serverName: String,
        quality: Int?,
        refererHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMp4UploadDirect = mediaUrl.contains("mp4upload.com", ignoreCase = true)
        val directReferer = if (isMp4UploadDirect) "https://www.mp4upload.com/" else (refererHint ?: mainUrl)
        val directHeaders = if (isMp4UploadDirect) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer,
                "Origin" to "https://www.mp4upload.com"
            )
        } else {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer
            )
        }

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = mediaUrl,
                type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = directReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = directHeaders
            }
        )
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeYourUploadUrl(url)
        if (isCustomManagedHost(normalizedUrl) || isCustomManagedServer(serverName)) {
            tryCustomLocalExtractor(normalizedUrl, serverName, quality, referer, callback)
            return
        }
        if (tryCustomLocalExtractor(normalizedUrl, serverName, quality, referer, callback)) return
        if (tryLoadMp4UploadDirect(normalizedUrl, serverName, quality, callback)) return

        loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
            val finalName =
                if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"

            callback.invoke(
                newExtractorLink(
                    source = link.name,
                    name = finalName,
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer ?: mainUrl
                    this.quality =
                        if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                            ?: Qualities.Unknown.value
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    private fun normalizeYourUploadUrl(url: String): String {
        if (!url.contains("yourupload.com", true)) return url
        return if (url.contains("/watch/", true)) {
            url.replace("/watch/", "/embed/", true)
        } else {
            url
        }
    }

    private fun isCustomManagedHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pixeldrain.com") ||
            lower.contains("pompom") ||
            lower.contains("pancal") ||
            lower.contains("myvidplay.com")
    }

    private fun isCustomManagedServer(serverName: String): Boolean {
        val lower = serverName.lowercase()
        return lower.contains("pompom") || lower.contains("pancal")
    }

    private suspend fun tryCustomLocalExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lower = url.lowercase()
        return when {
            lower.contains("myvidplay.com") -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            lower.contains("pixeldrain.com") -> {
                tryPixeldrainDirect(url, serverName, quality, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            lower.contains("yourupload.com") -> {
                tryMirrorCrawlerExtractor(normalizeYourUploadUrl(url), serverName, quality, referer, callback)
            }
            lower.contains("pompom") || lower.contains("pancal") -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            isCustomManagedServer(serverName) -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            else -> false
        }
    }

    private suspend fun tryPixeldrainDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = listOf(
            Regex("""pixeldrain\.com/(?:u|l)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""pixeldrain\.com/api/file/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { rgx ->
            rgx.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } ?: return false

        val directUrl = "https://pixeldrain.com/api/file/$id?download"
        val pixeldrainReferer = "https://pixeldrain.com/"
        val probe = runCatching {
            app.get(
                directUrl,
                referer = pixeldrainReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pixeldrainReferer,
                    "Origin" to "https://pixeldrain.com",
                    "Range" to "bytes=0-1023"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (contentType.contains("text/html")) return false

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = pixeldrainReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pixeldrainReferer,
                    "Origin" to "https://pixeldrain.com"
                )
            }
        )
        return true
    }

    private suspend fun tryPassMd5PatternDirect(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        prefetchedPageText: String? = null
    ): Boolean {
        val pageUrl = normalizeUrlFromBase(url, referer ?: mainUrl) ?: return false
        val pageText = prefetchedPageText ?: runCatching {
            app.get(pageUrl, referer = referer ?: mainUrl).text
        }.getOrNull() ?: return false

        val passPath = Regex("""/pass_md5/[^"'\\s<]+""", RegexOption.IGNORE_CASE)
            .find(pageText)
            ?.value
            ?.trim()
            ?: return false

        val passUrl = normalizeUrlFromBase(passPath, pageUrl) ?: return false
        val token = passPath.substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: Regex("""token=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
            ?: return false

        val passResponse = runCatching { app.get(passUrl, referer = pageUrl) }.getOrNull() ?: return false

        val baseStream = passResponse.text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return false

        val resolvedBaseStream = normalizeUrlFromBase(baseStream, pageUrl) ?: return false
        val randomPad = randomAlphaNum(10)
        val expiry = System.currentTimeMillis()
        val separator = if (resolvedBaseStream.contains("?")) "&" else "?"
        val finalUrl = "${resolvedBaseStream}${randomPad}${separator}token=$token&expiry=$expiry"

        val probe = runCatching {
            app.get(
                finalUrl,
                referer = pageUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Range" to "bytes=0-4095"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (!(contentType.contains("video") || contentType.contains("octet-stream"))) return false

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                this.referer = pageUrl
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl
                )
            }
        )
        return true
    }

    private suspend fun tryMirrorCrawlerExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val queue = ArrayDeque<Pair<String, String?>>()
        val visited = linkedSetOf<String>()
        queue.add(url to referer)
        var safety = 0

        while (queue.isNotEmpty() && safety++ < 12) {
            val (currentUrl, currentReferer) = queue.removeFirst()
            val current = normalizeUrlFromBase(currentUrl, currentReferer ?: mainUrl) ?: continue
            if (!visited.add(current)) continue

            if (isDirectMediaUrl(current)) {
                emitDirectMediaLink(current, serverName, quality, currentReferer ?: referer, callback)
                return true
            }

            val response = runCatching {
                app.get(
                    current,
                    referer = currentReferer ?: mainUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (currentReferer ?: mainUrl)
                    )
                )
            }.getOrNull() ?: continue

            val discovered = linkedSetOf<String>()
            response.document.select("source[src], video[src], iframe[src], a[href], script[src]").forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("href") }
                normalizeUrlFromBase(raw, current)?.let(discovered::add)
            }
            discovered.addAll(extractCandidatesFromText(response.text, current))

            response.document.select("script").forEach { script ->
                val data = script.data().trim()
                if (data.isBlank()) return@forEach
                discovered.addAll(extractCandidatesFromText(data, current))
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(data) }
                        .getOrNull()
                        ?.let { unpacked -> discovered.addAll(extractCandidatesFromText(unpacked, current)) }
                }
            }

            val direct = discovered.firstOrNull { isDirectMediaUrl(it) }
            if (!direct.isNullOrBlank()) {
                emitDirectMediaLink(direct, serverName, quality, current, callback)
                return true
            }

            discovered
                .filter { shouldQueueCustomCandidate(it, current) }
                .forEach { next -> queue.add(next to current) }
        }

        return false
    }

    private fun extractCandidatesFromText(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { rgx ->
            rgx.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrlFromBase(raw, baseUrl)?.let(out::add)
            }
        }
        return out
    }

    private fun shouldQueueCustomCandidate(candidate: String, currentUrl: String): Boolean {
        val lower = candidate.lowercase()
        if (isDirectMediaUrl(lower)) return false
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) return false
        if (lower.contains("/utils/player/")) return true

        val hints = listOf("yourupload", "pixeldrain", "pompom", "pancal", "myvidplay", "aghanim", "uservideo", "mp4upload")
        if (hints.any { lower.contains(it) }) return true

        val currentHost = runCatching { URI(currentUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val nextHost = runCatching { URI(candidate).host?.lowercase().orEmpty() }.getOrDefault("")
        return currentHost.isNotBlank() && currentHost == nextHost
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) {
                append(chars.random())
            }
        }
    }

    private suspend fun tryLoadMp4UploadDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val downloadUrl = "https://www.mp4upload.com/dl?op=download2&id=$id"
        val watchReferer = "https://www.mp4upload.com/"
        val redirect = runCatching {
            app.get(
                downloadUrl,
                referer = watchReferer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            )
        }.getOrNull() ?: return false

        val location = redirect.headers["Location"] ?: redirect.headers["location"]
        val finalUrl = when {
            location.isNullOrBlank() -> return false
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> "https://www.mp4upload.com$location"
            else -> return false
        }

        val probe = runCatching {
            app.get(
                finalUrl,
                referer = watchReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com",
                    "Range" to "bytes=0-4095"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (!(contentType.contains("octet-stream") || contentType.contains("video"))) return false

        callback.invoke(
            newExtractorLink(
                source = "Mp4Upload",
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                this.referer = watchReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            }
        )
        return true
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, MetaAnimeData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )

    data class Sources(
        @JsonProperty("src") var src: String? = null,
    )

    data class Servers(
        @JsonProperty("src") var src: String? = null,
        @JsonProperty("mirror") var mirror: String? = null,
    )

    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_image_html") var postImageHtml: String? = null,
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_genres") var postGenres: String? = null,
        @JsonProperty("post_type") var postType: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_sub") var postSub: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

    data class Search(
        @JsonProperty("anime") var anime: ArrayList<Anime> = arrayListOf()
    )
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    return null
}
