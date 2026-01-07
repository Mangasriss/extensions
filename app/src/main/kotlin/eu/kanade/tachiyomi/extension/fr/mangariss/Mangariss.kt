package eu.kanade.tachiyomi.extension.fr.mangariss

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class Mangariss : HttpSource() {

    override val name = "Mangariss"
    override val baseUrl = "https://mangasriss.github.io/mangariss-bot"
    override val lang = "fr"
    override val supportsLatest = true

    private val apiBaseUrl = "$baseUrl/api"

    private val json = Json { ignoreUnknownKeys = true }

    // =======================
    // POPULAIRES
    // =======================
    override fun popularMangaRequest(page: Int): Request =
        GET("$apiBaseUrl/mangas.json", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val list = json.decodeFromString<List<MangaDto>>(response.body.string())

        val mangas = list.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = it.cover
                url = "/api/details/${it.id}.json"
                author = "Mangariss"
            }
        }
        return MangasPage(mangas, false)
    }

    // =======================
    // DÉTAILS
    // =======================
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val d = json.decodeFromString<MangaDetailsDto>(response.body.string())
        return SManga.create().apply {
            title = d.title
            author = d.author
            thumbnail_url = d.cover
            description = "Source Mangariss"
            status = SManga.ONGOING
        }
    }

    // =======================
    // CHAPITRES
    // =======================
    override fun chapterListParse(response: Response): List<SChapter> {
        val d = json.decodeFromString<MangaDetailsDto>(response.body.string())
        return d.chapters.map {
            SChapter.create().apply {
                name = it.title
                chapter_number = it.number.toFloatOrNull() ?: -1f
                url = "${it.folder_url}:::${it.pages_count}"
            }
        }
    }

    // =======================
    // PAGES
    // =======================
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val (folder, count) = chapter.url.split(":::")
        val pages = (1..count.toInt()).map {
            val file = "%02d.png".format(it)
            Page(it, "", "$folder$file")
        }
        return Observable.just(pages)
    }

    // =======================
    // DTO
    // =======================
    @Serializable
    data class MangaDto(
        val id: String,
        val title: String,
        val cover: String,
    )

    @Serializable
    data class MangaDetailsDto(
        val title: String,
        val author: String = "Inconnu",
        val cover: String,
        val chapters: List<ChapterDto>,
    )

    @Serializable
    data class ChapterDto(
        val number: String,
        val title: String,
        val folder_url: String,
        val pages_count: Int,
    )

    // =======================
    // NON UTILISÉS
    // =======================
    override fun pageListParse(response: Response) =
        throw Exception("Unused")

    override fun imageUrlParse(response: Response) =
        throw Exception("Unused")

    override fun latestUpdatesRequest(page: Int) =
        popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) =
        popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        popularMangaRequest(page)

    override fun searchMangaParse(response: Response) =
        popularMangaParse(response)
}
