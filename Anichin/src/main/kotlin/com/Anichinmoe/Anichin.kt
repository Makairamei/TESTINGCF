package com.Anichinmoe

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.getAndUnpack

import java.net.URLDecoder
import kotlinx.coroutines.runBlocking

class Anichin : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get("${mainUrl}/${request.data}&page=$page").document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(fixUrl(url)).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".eplister li").map { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty())
                val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
                val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()

                val cleanTitle = epTitle
                    .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                    .replace("Subtitle Indonesia", "")
                    .trim()

                val name = "— $cleanTitle $epSub Indonesia".trim()
                val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

                newEpisode(link) {
                    this.name = name
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) } ?: url
            newMovieLoadResponse(title, movieHref, TvType.Movie, movieHref) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.requireLicense(name, "PLAY", data)
        val cfg = LicenseClient.getSelectors(name)
            ?: throw RuntimeException("[PREMIUM] ${LicenseClient.getBlockMessage().ifEmpty { "Lisensi tidak valid atau habis masa berlakunya." }}")
        val playerSelector = cfg.playerSelector ?: ".mobius option"
        val playerAttr = cfg.playerAttr ?: "value"
        val useBase64 = cfg.useBase64 ?: true
        val document = app.get(fixUrl(data)).document
        val visitedUrls = linkedSetOf<String>()
        val playerPath = "$mainUrl/utils/player/"

        document.select(playerSelector).forEach { server ->
            val value = server.attr(playerAttr)
            if (value.isNotBlank()) {
                val href = if (useBase64) {
                    val decoded = runCatching { base64Decode(value) }.getOrNull() ?: value
                    val doc = Jsoup.parse(decoded)
                    fixUrl(doc.select("iframe").attr("src"))
                } else {
                    fixUrl(value)
                }
                if (href.isNotBlank()) {
                    val serverName = server.text().trim().split(" ").firstOrNull()?.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    } ?: name
                    
                    val quality = Regex("(\\d{3,4})[pP]").find(server.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    
                    val candidates = decodeMirrorCandidates(href)
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
        return true
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
}
