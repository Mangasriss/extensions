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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
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
        val payload = json.decodeFromString<MangasResponse>(response.body.string())
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
        val payload = json.decodeFromString<LatestChaptersResponse>(response.body.string())
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
        val payload = json.decodeFromString<MangasResponse>(response.body.string())
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

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("#manga-title, .title-display")?.text()?.trim().orEmpty()
            author = document.selectFirst("#manga-author")?.text()?.trim()
            description = document.selectFirst("#manga-desc")?.text()?.trim()
            status = parseStatus(document.selectFirst("#manga-status")?.text().orEmpty())
            thumbnail_url = document.selectFirst("#manga-cover")?.absUrl("src").orEmpty().ifBlank { null }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapters-list .chapter-item").map { chapter: Element ->
            val numberText = chapter.selectFirst(".ch-num")?.text()?.trim()?.removePrefix("#").orEmpty()
            val titleText = chapter.selectFirst(".ch-name")?.text()?.trim().orEmpty()
            val dateText = chapter.selectFirst(".ch-date")?.text()?.trim().orEmpty()

            SChapter.create().apply {
                url = chapter.attr("href").trim()
                name = buildString {
                    if (numberText.isNotBlank()) append("Chapitre ").append(numberText)
                    if (titleText.isNotBlank()) {
                        if (isNotEmpty()) append(" - ")
                        append(titleText.toString())
                    }
                }.ifBlank { numberText.ifBlank { titleText } }
                chapter_number = numberText.toFloatOrNull() ?: -1f
                date_upload = parseDate(dateText)
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val imageUrls = document.select("#vertical img").mapNotNull { image: Element ->
            when {
                image.hasAttr("src") && image.attr("src").isNotBlank() -> image.absUrl("src")
                image.hasAttr("data-src") && image.attr("data-src").isNotBlank() -> image.absUrl("data-src")
                else -> null
            }?.ifBlank { null }
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
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
}
