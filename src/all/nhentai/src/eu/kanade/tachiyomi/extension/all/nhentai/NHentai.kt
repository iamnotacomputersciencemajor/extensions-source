package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.injectLazy

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : HttpSource(),
    ConfigurableSource {

    override val name = "NHentai"
    override val baseUrl = "https://nhentai.net"
    override val supportsLatest = true

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    private val apiUrl = "https://nhentai.net/api/v2"

    // Regex to pull gallery ID out of hrefs like /g/393878/
    private val galleryIdRegex = Regex("""/g/(\d+)/""")

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent(filterInclude = listOf("chrome"))

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")
            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = newValue == "full"
                true
            }
        }.also(screen::addPreference)
        screen.addRandomUAPreference()
    }

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET(buildWebUrl(page, sort = "popular"), headers)

    override fun popularMangaParse(response: Response) = parseHtmlGalleryList(response)

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int) = GET(buildWebUrl(page, sort = null), headers)

    override fun latestUpdatesParse(response: Response) = parseHtmlGalleryList(response)

    // ==================== SEARCH ====================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val idQuery = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> null
        }

        return if (idQuery != null) {
            // API call for specific ID searches
            client.newCall(GET("$apiUrl/galleries/$idQuery", headers))
                .asObservableSuccess()
                .map { response ->
                    val detail = json.decodeFromString<GalleryDetail>(response.body.string())
                    MangasPage(listOf(detail.toSManga(displayFullTitle)), false)
                }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        var sort = "date"
        val tagParts = mutableListOf<String>()

        if (nhLang.isNotBlank()) tagParts.add("language:$nhLang")

        filterList.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.toUriPart()
                is TagFilter -> filter.state.toTagParts("tag").forEach { tagParts.add(it) }
                is CategoryFilter -> filter.state.toTagParts("category").forEach { tagParts.add(it) }
                is ArtistFilter -> filter.state.toTagParts("artist").forEach { tagParts.add(it) }
                is GroupFilter -> filter.state.toTagParts("group").forEach { tagParts.add(it) }
                is ParodyFilter -> filter.state.toTagParts("parody").forEach { tagParts.add(it) }
                is CharactersFilter -> filter.state.toTagParts("character").forEach { tagParts.add(it) }
                is PagesFilter -> filter.state.trim().takeIf { it.isNotEmpty() }
                    ?.let { tagParts.add("pages:$it") }
                is UploadedFilter -> filter.state.trim().takeIf { it.isNotEmpty() }
                    ?.let { tagParts.add("uploaded:$it") }
                else -> {}
            }
        }

        val offsetPage = filterList.findInstance<OffsetPageFilter>()
            ?.state?.toIntOrNull()?.plus(page) ?: page

        val fullQuery = (listOf(query.trim()) + tagParts)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        return GET(
            "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", fullQuery)
                .addQueryParameter("sort", sort)
                .addQueryParameter("page", offsetPage.toString())
                .build().toString(),
            headers,
        )
    }

    private fun String.toTagParts(type: String): List<String> = trim().split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { tag ->
            val negated = tag.startsWith("-")
            val clean = tag.removePrefix("-")
            val formatted = "$type:\"$clean\""
            if (negated) "-$formatted" else formatted
        }

    override fun searchMangaParse(response: Response) = parseHtmlGalleryList(response)

    // ==================== DETAILS ====================

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/galleries/${manga.url.trimSlashes()}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = json.decodeFromString<GalleryDetail>(response.body.string())
        return detail.toSManga(displayFullTitle)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url.trimSlashes()}/"

    // ==================== CHAPTERS ====================

    override fun chapterListRequest(manga: SManga) = GET("$apiUrl/galleries/${manga.url.trimSlashes()}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = json.decodeFromString<GalleryDetail>(response.body.string())
        return listOf(
            SChapter.create().apply {
                name = "Chapter (${detail.num_pages} pages)"
                url = detail.id.toString()
                chapter_number = 1f
                date_upload = detail.upload_date * 1000
                scanlator = NHUtils.getGroups(detail.tags)
            },
        )
    }

    // ==================== PAGES ====================

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/galleries/${chapter.url}/pages", headers)

    override fun pageListParse(response: Response): List<Page> {
        val data = json.decodeFromString<GalleryPagesResponse>(response.body.string())
        return data.pages.mapIndexed { index, page ->
            // Prepend the high-res image CDN to the relative path from the DTO
            Page(index, "", "https://i.nhentai.net/${page.path}")
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ==================== FILTERS ====================

    override fun getFilterList() = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        ArtistFilter(),
        GroupFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded: valid units are h, d, w, m, y — e.g. >20d"),
        UploadedFilter(),
        Filter.Header("Pages: e.g. >20 or <100"),
        PagesFilter(),
        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("tag")
    class CategoryFilter : AdvSearchEntryFilter("category")
    class ArtistFilter : AdvSearchEntryFilter("artist")
    class GroupFilter : AdvSearchEntryFilter("group")
    class ParodyFilter : AdvSearchEntryFilter("parody")
    class CharactersFilter : AdvSearchEntryFilter("character")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ==================== HELPERS ====================

    private fun buildWebUrl(page: Int, sort: String?): String {
        val base = if (nhLang.isNotBlank()) "$baseUrl/language/$nhLang/" else "$baseUrl/"
        return base.toHttpUrl().newBuilder().apply {
            sort?.let { addQueryParameter("sort", it) }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build().toString()
    }

    private fun parseHtmlGalleryList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)

        val galleryElements = doc.select("div.gallery, div.gallerythumb")

        val mangas = galleryElements.mapNotNull { el ->
            val anchor = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href")

            val galleryId = galleryIdRegex.find(href)?.groupValues?.get(1)
            if (galleryId.isNullOrBlank()) return@mapNotNull null

            // FIX: NHentai hides the actual thumbnail inside a <noscript> tag!
            val noscriptImg = el.selectFirst("noscript img")
            val regularImg = el.selectFirst("img")

            val thumbUrl = noscriptImg?.attr("src")?.ifBlank { null }
                ?: regularImg?.attr("data-src")?.ifBlank { null }
                ?: regularImg?.attr("src")?.ifBlank { null }

            val rawTitle = el.selectFirst("div.caption")?.text()?.ifBlank { null }
                ?: regularImg?.attr("alt")?.ifBlank { null }
                ?: "Unknown"

            SManga.create().apply {
                url = galleryId
                thumbnail_url = thumbUrl // Now it will grab the real image immediately!
                title = if (displayFullTitle) rawTitle else rawTitle.shortenTitle()
                status = SManga.COMPLETED
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val lastPage = doc.selectFirst("section.pagination a.last")
            ?.attr("href")?.let { href ->
                runCatching { href.toHttpUrl().queryParameter("page")?.toIntOrNull() }.getOrNull()
                    ?: Regex("""page=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }

        val hasNextPage = when {
            lastPage != null -> currentPage < lastPage
            // Added a fallback to check for the literal "next" class button
            else -> doc.selectFirst("section.pagination a.next, section.pagination a[rel=next]") != null
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun GalleryDetail.toSManga(fullTitle: Boolean): SManga {
        val galleryTitle = title
        val titleStr = galleryTitle.english.takeIf { it.isNotBlank() }
            ?.let { if (fullTitle) it else it.shortenTitle() }
            ?: galleryTitle.pretty

        return SManga.create().apply {
            url = id.toString()
            this.title = titleStr
            // Prepend the thumbnail CDN to the relative path
            thumbnail_url = "https://t.nhentai.net/${thumbnail.path}"
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            artist = NHUtils.getArtists(tags)
            author = NHUtils.getGroups(tags) ?: NHUtils.getArtists(tags)
            genre = NHUtils.getTags(tags)
            description = buildString {
                append("Full Title: ${galleryTitle.english}\n")
                galleryTitle.japanese?.let { append("Japanese: $it\n") }
                append("\n")
                append("Pages: $num_pages\n")
                append("Favorites: $num_favorites\n")
                append(NHUtils.getTagDescription(tags))
            }
        }
    }

    private fun String.trimSlashes() = trim('/')

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}
