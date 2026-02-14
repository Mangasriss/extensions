package eu.kanade.tachiyomi.extension.fr.mangamoins

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class MangaMoins : HttpSource() {

    override val name = "Manga Moins"
    override val baseUrl = "https://mangamoins.com"
    override val lang = "fr"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#mangaCarousel .manga-card").mapNotNull { card: Element ->
            val url = card.attr("href").trim()
            val title = card.selectFirst(".manga-info h3")?.text()?.trim().orEmpty()
            val thumbnail = card.selectFirst(".manga-cover img")?.absUrl("src").orEmpty().ifBlank { null }

            if (url.isBlank() || title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                this.url = url
                this.title = title
                this.thumbnail_url = thumbnail
            }
        }

        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        if (q.isBlank()) return GET(baseUrl, headers)
        val slug = q.replace(" ", "+")
        return GET("$baseUrl/manga/$slug", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val path = response.request.url.encodedPath
        if (path == "/" || path.isBlank()) return popularMangaParse(response)

        val document = response.asJsoup()
        val title = document.selectFirst("#manga-title, .title-display")?.text()?.trim().orEmpty()
        if (title.isBlank()) return MangasPage(emptyList(), false)

        val manga = SManga.create().apply {
            this.title = title
            this.url = path
            this.thumbnail_url = document.selectFirst("#manga-cover")?.absUrl("src").orEmpty().ifBlank { null }
        }
        return MangasPage(listOf(manga), false)
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
}
