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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.lib.randomua.addRandomUAPreferenceToScreen
import keiyoushi.lib.randomua.getPrefCustomUA
import keiyoushi.lib.randomua.getPrefUAType
import keiyoushi.lib.randomua.setRandomUserAgent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : HttpSource(), ConfigurableSource {

    override val name = "NHentai"
    override val baseUrl = "https://nhentai.net"
    override val supportsLatest = true

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    private val apiUrl = "https://nhentai.net/api/v2"

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(2)
            .build()
    }

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

        addRandomUAPreferenceToScreen(screen)
    }

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int) =
        buildSearchRequest(page, "", "popular")

    override fun popularMangaParse(response: Response) = parseGalleryList(response)

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int) =
        buildSearchRequest(page, "", "date")

    override fun latestUpdatesParse(response: Response) = parseGalleryList(response)

    // ==================== SEARCH ====================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        // Support searching by ID directly e.g. "id:177013" or just "177013"
        val idQuery = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> null
        }

        return if (idQuery != null) {
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

        val fullQuery = (listOf(query.trim()) + tagParts)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val offsetPage = filterList.findInstance<OffsetPageFilter>()
            ?.state?.toIntOrNull()?.plus(page) ?: page

        return buildSearchRequest(offsetPage, fullQuery, sort)
    }

    // Converts "big breasts, -loli" → ["tag:\"big breasts\"", "-tag:\"loli\""]
    private fun String.toTagParts(type: String): List<String> {
        val isUnquoted = type == "Pages" || type == "Uploaded"
        return trim().split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { tag ->
                val negated = tag.startsWith("-")
                val clean = tag.removePrefix("-")
                val formatted = if (isUnquoted) "$type:$clean" else "$type:\"$clean\""
                if (negated) "-$formatted" else formatted
            }
    }

    override fun searchMangaParse(response: Response) = parseGalleryList(response)

    // ==================== DETAILS ====================

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$apiUrl/galleries/${manga.url.trimSlashes()}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = json.decodeFromString<GalleryDetail>(response.body.string())
        return detail.toSManga(displayFullTitle)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url.trimSlashes()}/"

    // ==================== CHAPTERS ====================

    override fun chapterListRequest(manga: SManga) =
        GET("$apiUrl/galleries/${manga.url.trimSlashes()}", headers)

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

    override fun pageListRequest(chapter: SChapter) =
        GET("$apiUrl/galleries/${chapter.url}/pages", headers)

    override fun pageListParse(response: Response): List<Page> {
        val data = json.decodeFromString<GalleryPagesResponse>(response.body.string())
        return data.pages.mapIndexed { index, page ->
            Page(index, "", page.path)
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

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Popular: All Time", "popular"),
            Pair("Popular: Month", "popular-month"),
            Pair("Popular: Week", "popular-week"),
            Pair("Popular: Today", "popular-today"),
            Pair("Recent", "date"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ==================== HELPERS ====================

    private fun buildSearchRequest(page: Int, query: String, sort: String): Request {
        val langPart = if (nhLang.isNotBlank()) "language:$nhLang" else ""
        val fullQuery = listOf(query.trim(), langPart)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "*" }

        return GET(
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", fullQuery)
                .addQueryParameter("sort", sort)
                .addQueryParameter("page", page.toString())
                .build(),
            headers,
        )
    }

    private fun parseGalleryList(response: Response): MangasPage {
        val data = json.decodeFromString<GalleryListResponse>(response.body.string())
        val mangas = data.result.map { item ->
            SManga.create().apply {
                url = item.id.toString()
                title = item.english_title.takeIf { it.isNotBlank() }
                    ?.let { if (displayFullTitle) it else it.shortenTitle() }
                    ?: item.japanese_title
                    ?: "Unknown"
                thumbnail_url = item.thumbnail
                status = SManga.COMPLETED
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
        return MangasPage(mangas, data.page < data.num_pages)
    }

    private fun GalleryDetail.toSManga(fullTitle: Boolean): SManga {
        val titleStr = title.english.takeIf { it.isNotBlank() }
            ?.let { if (fullTitle) it else it.shortenTitle() }
            ?: title.pretty

        return SManga.create().apply {
            url = id.toString()
            this.title = titleStr
            thumbnail_url = thumbnail.path
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            artist = NHUtils.getArtists(tags)
            author = NHUtils.getGroups(tags) ?: NHUtils.getArtists(tags)
            genre = NHUtils.getTags(tags)
            description = buildString {
                append("Full Title: ${title.english}\n")
                title.japanese?.let { append("Japanese: $it\n") }
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