package eu.kanade.tachiyomi.extension.fr.mangamoins

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class MangaMoins : HttpSource() {

    override val name = "Manga Moins"
    override val baseUrl = "https://mangamoins.com"
    override val lang = "fr"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .build()
    }

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/v1/mangas?page=$page&limit=30", apiHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val payload = json.decodeFromString(MangasResponse.serializer(), response.body.string())
        val mangas = payload.data.map { manga ->
            SManga.create().apply {
                title = manga.title
                url = "/manga/${toSlug(manga.title)}"
                thumbnail_url = "$baseUrl/files/scans/${manga.cover_folder}/thumbnail.webp"
            }
        }
        val hasNextPage = payload.page * payload.limit < payload.total
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * 30
        return GET("$baseUrl/api/v1/latest-chapters?offset=$offset", apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val payload = json.decodeFromString(LatestChaptersResponse.serializer(), response.body.string())
        val mangas = payload.items
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .map { item ->
                SManga.create().apply {
                    title = item.title
                    url = "/manga/${toSlug(item.title)}"
                    thumbnail_url = "$baseUrl/files/scans/${item.folder}/thumbnail.webp"
                    author = item.author
                }
            }
        return MangasPage(mangas, payload.hasMore)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        if (q.isBlank()) return popularMangaRequest(1)
        val encoded = URLEncoder.encode(q, "UTF-8")
        return GET("$baseUrl/api/v1/mangas?page=$page&limit=30&q=$encoded", apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val payload = json.decodeFromString(MangasResponse.serializer(), response.body.string())
        val mangas = payload.data.map { manga ->
            SManga.create().apply {
                title = manga.title
                url = "/manga/${toSlug(manga.title)}"
                thumbnail_url = "$baseUrl/files/scans/${manga.cover_folder}/thumbnail.webp"
            }
        }
        val hasNextPage = payload.page * payload.limit < payload.total
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val query = mangaApiQueryFromUrl(manga.url)
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/api/v1/manga?manga=$encoded", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val payload = json.decodeFromString(MangaDetailResponse.serializer(), response.body.string())
        val info = payload.info

        return SManga.create().apply {
            title = info.title
            author = info.author
            description = info.description?.takeIf { it.isNotBlank() } ?: "Aucune description."
            status = parseStatus(info.status.orEmpty())
            thumbnail_url = absolutizePath(info.cover)
        }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val payload = json.decodeFromString(MangaDetailResponse.serializer(), response.body.string())
        return payload.chapters.map { chapter ->
            val numberText = chapter.num.trim().removePrefix("#")
            val titleText = chapter.title.trim()
            SChapter.create().apply {
                url = "/scan/${chapter.folder}"
                name = buildString {
                    if (numberText.isNotBlank()) append("Chapitre ").append(numberText)
                    if (titleText.isNotBlank()) {
                        if (isNotEmpty()) append(" - ")
                        append(titleText)
                    }
                }.ifBlank { numberText.ifBlank { titleText } }
                chapter_number = numberText.toFloatOrNull() ?: -1f
                date_upload = normalizeTimestamp(chapter.time)
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val preloadUrls = document
            .select("link[rel=preload][as=image][href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isBlank()) null else absolutizePath(href)
            }
            .filter { it.contains("/files/scans/") }
            .distinct()

        if (preloadUrls.isNotEmpty()) {
            return preloadUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        }

        val inlineUrls = document
            .select("#vertical img, #image img")
            .mapNotNull { image ->
                when {
                    image.hasAttr("src") && image.attr("src").isNotBlank() -> absolutizePath(image.attr("src"))
                    image.hasAttr("data-src") && image.attr("data-src").isNotBlank() -> absolutizePath(image.attr("data-src"))
                    else -> null
                }
            }
            .distinct()

        if (inlineUrls.isNotEmpty()) {
            return inlineUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        }

        val scriptUrls = Regex(
            """["'](\./files/scans/[^"' ]+\.(?:png|jpg|jpeg|webp)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(document.html())
            .mapNotNull { absolutizePath(it.groupValues[1]) }
            .distinct()
            .toList()

        return scriptUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    private fun parseStatus(status: String): Int {
        val value = status.trim().lowercase(Locale.FRANCE)
        return when {
            value.contains("en cours") -> SManga.ONGOING
            value.contains("termin") || value.contains("fin") -> SManga.COMPLETED
            value.contains("annul") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(dateText: String): Long {
        return try {
            dateFormat.parse(dateText)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun toSlug(title: String): String =
        title.lowercase(Locale.ROOT).trim().replace("\\s+".toRegex(), "+")

    private fun mangaApiQueryFromUrl(url: String): String {
        val slugPart = url.substringAfter("/manga/", "")
        val decoded = URLDecoder.decode(slugPart, "UTF-8")
        return decoded.replace("+", " ").trim().ifBlank { decoded }
    }

    private fun absolutizePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path

        var clean = path.trim()
        while (clean.startsWith("../")) clean = clean.removePrefix("../")
        clean = clean.removePrefix("./").removePrefix("/")
        return "$baseUrl/$clean"
    }

    private fun normalizeTimestamp(value: Long?): Long {
        val time = value ?: return 0L
        return if (time < 10_000_000_000L) time * 1000 else time
    }

    @Serializable
    data class MangaApiItem(
        val title: String,
        val folder: String,
        val cover_folder: String,
    )

    @Serializable
    data class MangasResponse(
        val total: Int = 0,
        val page: Int = 1,
        val limit: Int = 30,
        val data: List<MangaApiItem> = emptyList(),
    )

    @Serializable
    data class LatestChapterItem(
        val folder: String,
        val title: String,
        val author: String? = null,
    )

    @Serializable
    data class LatestChaptersResponse(
        val items: List<LatestChapterItem> = emptyList(),
        val hasMore: Boolean = false,
    )

    @Serializable
    data class MangaDetailInfo(
        val title: String,
        val author: String? = null,
        val status: String? = null,
        val cover: String? = null,
        val description: String? = null,
    )

    @Serializable
    data class MangaDetailChapter(
        val folder: String,
        val num: String,
        val title: String,
        val time: Long? = null,
    )

    @Serializable
    data class MangaDetailResponse(
        val info: MangaDetailInfo,
        val chapters: List<MangaDetailChapter> = emptyList(),
    )
}
