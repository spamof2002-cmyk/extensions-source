package eu.kanade.tachiyomi.extension.en.kensscan

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Kens Scan (kensscan.com), intentionally separate from the unrelated
 * "Ken Scans" source which points to kencomics.com.
 *
 * Page lists are generated from the first numbered page image. The chapter
 * HTML supplies the page count: all reader images for free chapters, or the
 * previews plus the explicitly advertised remaining count for VIP chapters.
 * No speculative image probes are performed; VIP entries are not filtered.
 */
@Source
abstract class KensScan :
    KeiSource(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALT_NAME_SEARCH
            title = "Alternative-name search"
            summary = "Search once during details fetch if alternative names are missing. Off prevents extra searches."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RESEARCH_EXISTING_NAMES
            title = "Re-search existing alternative names on details refresh"
            summary = "Search again even if names are saved. Requires Alternative-name search to be on."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CHAPTER_ALT_NAME_SEARCH
            title = "Search missing alternative names during chapter updates"
            summary = "When chapter updates have no saved names, allow one extra search. Requires Alternative-name search."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_ALT_NAME_SEARCH = "kensscan_alt_names_first_fetch"
        private const val PREF_RESEARCH_EXISTING_NAMES = "kensscan_alt_names_retry_details"
        private const val PREF_CHAPTER_ALT_NAME_SEARCH = "kensscan_alt_names_chapter_updates"
    }

    private val seriesPath = Regex("^/manga/[^/]+/?$", RegexOption.IGNORE_CASE)
    private val chapterPath = Regex("^/manga/[^/]+/chapter-[^/]+/?$", RegexOption.IGNORE_CASE)
    private val chapterLabel = Regex("(?i)chapter[-\\s]*([0-9]+(?:[.][0-9]+)?)")
    private val datePattern = Regex(
        "(?i)\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+[0-9]{1,2},\\s+[0-9]{4}\\b",
    )
    private val formatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)
    private val shortFormatter = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
    private val relativeDate = Regex("(?i)\\b([0-9]+)\\s+(minute|hour|day|week|month|year)s?\\s+ago\\b")
    private val shortDate = Regex(
        "(?i)\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+[0-9]{1,2},\\s+[0-9]{4}\\b",
    )

    // Only used for explicit searches when site search does not return results.
    // No catalogue scanning during normal reading, browsing or library updates.
    private val catalogueMutex = Mutex()
    private var catalogueCache: List<SManga>? = null
    private var catalogueCachedAt = 0L

    // Match the terminal Page number, not the chapter number in the URL.
    private val numberedPage = Regex(
        "^(https?://[^?#]+/)(Pages?)([0-9]+)(\\.(?:webp|png|jpe?g))(\\?[^#]*)?$",
        RegexOption.IGNORE_CASE,
    )

    private val remainingPages = Regex(
        "\\b([0-9]{1,4})\\s+more\\s+pages\\s+are\\s+waiting\\b",
        RegexOption.IGNORE_CASE,
    )

    // Use the website's Views ranking for Popular rather than its default catalogue.
    override suspend fun getPopularManga(page: Int): MangasPage = listing(page, ranking = true)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(page, order = "latest")

    private fun listingUrl(page: Int, order: String = "latest", ranking: Boolean = false): String {
        val section = if (ranking) "ranking" else "manga"
        val path = if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/page/$page/"
        val param = if (ranking) "orderby=views" else "m_orderby=$order"
        return "$path?$param"
    }

    private fun hasNextPage(document: Document, page: Int, section: String): Boolean = document.select("a[href]").any { a ->
        a.attr("abs:href").toHttpUrlOrNull()?.encodedPath?.trimEnd('/') ==
            "/$section/page/${page + 1}"
    }

    private suspend fun listing(page: Int, order: String = "latest", ranking: Boolean = false): MangasPage {
        val doc = client.get(listingUrl(page, order, ranking)).asJsoup()
        return MangasPage(listingFromDocument(doc), hasNextPage(doc, page, if (ranking) "ranking" else "manga"))
    }

    private class SortFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Latest", "A-Z", "Most Bookmarked", "Trending", "Most Views", "New"),
        ) {
        val key: String get() = arrayOf("latest", "alphabet", "bookmarks", "trending", "views", "new-manga")[state]
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(SortFilter())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val order = filters.filterIsInstance<SortFilter>().firstOrNull()?.key ?: "latest"
        if (query.isBlank()) return listing(page, order)

        val q = query.trim()
        val encoded = URLEncoder.encode(q, "UTF-8")
        // Madara / WP Manga search, including post_type so WordPress searches
        // manga records instead of blog posts. A fallback covers sites where
        // the search widget uses AJAX and ignores ordinary search URLs.
        val pagePath = if (page <= 1) "" else "page/$page/"
        val url = "$baseUrl/$pagePath?s=$encoded&post_type=wp-manga&m_orderby=$order"
        try {
            val doc = client.get(url).asJsoup()
            val found = listingFromDocument(doc)
            val exactMatches = found.filter { matchesQuery(it.title, q) }
            // The website can match alternative titles that are not visible as
            // the card's main title. Trust the site's results on a search page.
            // Otherwise, use only primary-title matches to avoid false hits
            // from sidebars on a redirected / non-search page.
            val searchPage = doc.select("h1, title").any {
                it.text().contains("search", ignoreCase = true)
            } || doc.selectFirst(".search-results, .search-page, .search-result-list") != null
            val mangas = if (searchPage) found else exactMatches
            if (mangas.isNotEmpty()) {
                return MangasPage(mangas, hasNextSearchPage(doc, page))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall back to the catalogue index below.
        }
        // The fallback is performed only for a user-initiated search, not for
        // automatic updates. A cached catalogue avoids repeat page requests.
        val results = indexedCatalogue().filter { matchesQuery(it.title, q) }
        val start = (page - 1) * 20
        return MangasPage(results.drop(start).take(20), results.size > start + 20)
    }

    private fun matchesQuery(title: String, query: String): Boolean = query.split(Regex("\\s+")).filter { it.isNotBlank() }.all { title.contains(it, ignoreCase = true) }

    private fun hasNextSearchPage(doc: Document, page: Int): Boolean = doc.select("a[href]").any { a ->
        val url = a.attr("abs:href").toHttpUrlOrNull()
        url != null && url.queryParameter("s") != null &&
            (
                url.encodedPath.trimEnd('/') == "/page/${page + 1}" ||
                    url.queryParameter("paged") == "${page + 1}"
                )
    }

    private suspend fun indexedCatalogue(): List<SManga> = catalogueMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = catalogueCache
        if (existing != null && now - catalogueCachedAt < 30 * 60 * 1000L) {
            return@withLock existing
        }

        val first = client.get(listingUrl(1)).asJsoup()
        val firstPage = listingFromDocument(first)
        val lastPage = first.select("a[href]").mapNotNull { a ->
            val path = a.attr("abs:href").toHttpUrlOrNull()?.encodedPath.orEmpty()
            Regex("^/manga/page/([0-9]+)/?$").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()?.coerceIn(1, 50) ?: 1

        // Fetch the remaining catalogue in small batches; only this explicit
        // search fallback can do so. The site currently has ~25 pages.
        val rest = (2..lastPage).toList().chunked(3).flatMap { batch ->
            coroutineScope {
                batch.map { page ->
                    async { listingFromDocument(client.get(listingUrl(page)).asJsoup()) }
                }.awaitAll()
            }
        }
        val titles = (firstPage + rest.flatten()).distinctBy { it.url }
        catalogueCache = titles
        catalogueCachedAt = now
        titles
    }

    private fun listingFromDocument(document: Document): List<SManga> = document
        .select("a[href]")
        .asSequence()
        .filter { it.closest("header, footer, nav") == null }
        .filter { seriesPath.matches(it.attr("abs:href").toHttpUrlOrNullPath()) }
        .mapNotNull { anchor ->
            val title = anchor.selectFirst("h2, h3, h4")?.text()
                ?: anchor.text().replace(Regex("(?i)^new\\s+"), "").trim()
            if (title.isBlank() || title.equals("new", true)) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(anchor.attr("abs:href"))
                this.title = title
                thumbnail_url = anchor.coverUrl()
                    ?: anchor.parent()?.coverUrl()
            }
        }
        .distinctBy { it.url }
        .toList()

    private fun String.toHttpUrlOrNullPath(): String = this.toHttpUrlOrNull()?.encodedPath.orEmpty()

    private fun Element.coverUrl(): String? {
        val img = selectFirst("img") ?: return null
        return listOf("data-src", "data-lazy-src", "src")
            .mapNotNull { key -> img.absUrl(key).ifBlank { null } }
            .firstOrNull { it.startsWith("http") }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !seriesPath.matches(url.encodedPath)) return null
        return detailWithSearchNames(client.get(url.toString()).asJsoup())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Single request for details and chapters; no duplicate fetch.
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        // A new/uninitialised series needs its first details fetch even if J2K
        // initially requests chapters. Normal chapter-only updates stay light.
        val details = when {
            fetchDetails || !manga.initialized -> detailWithSearchNames(doc, manga)
            fetchChapters && shouldSearchDuringChapterUpdate(manga) -> addMissingNamesDuringChapterUpdate(manga, doc)
            else -> manga
        }
        val resultChapters = if (fetchChapters) chaptersFromDocument(doc) else chapters
        return SMangaUpdate(details, resultChapters)
    }

    private val alternativeLabel = Regex(
        "(?i)^(?:(?:alternative|alternate|other|alt)(?:\\s+(?:titles?|names?))?|aliases?|synonyms?)\\s*:?$",
    )

    private val inlineAlternativeNames = Regex(
        "(?i)^(?:(?:alternative|alternate|other|alt)(?:\\s+(?:titles?|names?))?|aliases?|synonyms?)\\s*[:：]\\s*(.+)$",
    )

    /** Extract a names field from a search result card, never from a whole results page. */
    private fun alternativeNamesFromSearchResult(
        document: Document,
        mangaPath: String,
        primaryTitle: String,
    ): List<String> {
        val expectedPath = mangaPath.trimEnd('/')
        for (anchor in document.select("a[href]")) {
            val path = anchor.attr("abs:href").toHttpUrlOrNull()?.encodedPath?.trimEnd('/')
            if (path != expectedPath) continue

            // Walk only within this exact result's card. Stop before an ancestor
            // containing links to multiple series to avoid borrowing another
            // title's aliases (or labels from a sidebar).
            var card = anchor.parent()
            var depth = 0
            while (card != null && depth++ < 8 && card !is Document) {
                val seriesUrls = card.select("a[href]").mapNotNull { link ->
                    link.attr("abs:href").toHttpUrlOrNull()?.encodedPath?.trimEnd('/')
                        ?.takeIf { seriesPath.matches(it) }
                }.distinct()
                if (seriesUrls.any { it != expectedPath }) break

                val aliases = alternativeNamesFromDocument(card)
                    .filterNot { it.equals(primaryTitle, ignoreCase = true) }
                if (aliases.isNotEmpty()) return aliases
                card = card.parent()
            }
        }
        return emptyList()
    }

    // The site's search cards display comma-separated names. Legacy saved
    // descriptions may use commas, semicolons, bullets, or spaced middle dots.
    private fun splitAlternativeNames(raw: String): List<String> = raw
        .split(Regex("[,;|\\n]+|\\s+·\\s+"))
        .map { it.trim().trim('-', '•', '*', '·', ' ') }
        .filter { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }

    // Display one bullet per name; keep reading old comma- or dot-separated
    // saved descriptions through splitAlternativeNames when refreshing.
    private fun formatAlternativeNames(names: List<String>): String = "Alternative Names:\n" + names.joinToString("\n") { "• $it" }

    private val savedAlternativeHeader = Regex(
        "(?im)^\\s*(?:alternative\\s+(?:titles?|names?)|alt\\s+titles?|other\\s+names?|associated\\s+names?|synonyms|also\\s+known\\s+as)\\s*[:：][ \\t]*",
    )

    private fun savedAlternativeNames(description: String?): List<String> {
        val text = description.orEmpty()
        val header = savedAlternativeHeader.find(text) ?: return emptyList()
        val section = text.substring(header.range.last + 1).trimStart()
            .substringBefore("\n\n")
        return splitAlternativeNames(section)
    }

    // The chapter-only option never runs when the master switch is off or
    // any alternative names already exist in the saved description.
    private fun shouldSearchDuringChapterUpdate(manga: SManga): Boolean = preferences.getBoolean(PREF_ALT_NAME_SEARCH, true) &&
        preferences.getBoolean(PREF_CHAPTER_ALT_NAME_SEARCH, false) && savedAlternativeNames(manga.description).isEmpty()

    // Only amend the description on chapter-only updates; do not overwrite
    // other manga metadata or replace the synopsis if the lookup fails.
    private suspend fun addMissingNamesDuringChapterUpdate(manga: SManga, doc: Document): SManga {
        val pageNames = alternativeNamesFromDocument(doc)
        val searched = if (pageNames.isEmpty()) searchAlternativeNames(manga) else emptyList()
        val names = (pageNames + searched).flatMap(::splitAlternativeNames)
            .filterNot { it.equals(manga.title, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (names.isNotEmpty()) {
            manga.description = listOfNotNull(
                manga.description?.takeIf { it.isNotBlank() },
                formatAlternativeNames(names),
            ).joinToString("\n\n")
        }
        return manga
    }

    private suspend fun searchAlternativeNames(manga: SManga): List<String> = try {
        val query = URLEncoder.encode(manga.title, "UTF-8")
        val searchDoc = client.get("$baseUrl/?s=$query&post_type=wp-manga&m_orderby=latest").asJsoup()
        val path = getMangaUrl(manga).toHttpUrl().encodedPath
        alternativeNamesFromSearchResult(searchDoc, path, manga.title)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun shouldSearchAlternativeNames(hasSavedNames: Boolean, isDetailsRefresh: Boolean): Boolean = preferences.getBoolean(PREF_ALT_NAME_SEARCH, true) &&
        (!hasSavedNames || (isDetailsRefresh && preferences.getBoolean(PREF_RESEARCH_EXISTING_NAMES, false)))

    private suspend fun detailWithSearchNames(doc: Document, oldManga: SManga? = null): SManga {
        val manga = detailFromDocument(doc)
        val savedNames = savedAlternativeNames(oldManga?.description)
        val pageNames = savedAlternativeNames(manga.description)
        val allKnown = (pageNames + savedNames)
            .filterNot { it.equals(manga.title, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val shouldSearch = shouldSearchAlternativeNames(allKnown.isNotEmpty(), oldManga?.initialized == true)
        // Existing names are retained, whether the master search switch is on
        // or off. A failed search must not erase them.
        val searched = if (shouldSearch) searchAlternativeNames(manga) else emptyList()

        val finalNames = (allKnown + searched.flatMap(::splitAlternativeNames))
            .filterNot { it.equals(manga.title, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (finalNames.isNotEmpty()) {
            // Remove only the automatically generated alias block from the new
            // details description; retain the synopsis and everything else.
            val current = manga.description.orEmpty()
            val header = savedAlternativeHeader.find(current)
            val synopsis = if (header != null) current.substring(0, header.range.first).trimEnd() else current
            manga.description = listOfNotNull(
                synopsis.takeIf { it.isNotBlank() },
                formatAlternativeNames(finalNames),
            ).joinToString("\n\n")
        }
        return manga
    }

    // Uses the HTML already fetched for series details; no additional requests.
    // SManga has no dedicated alternative-title field, so expose them in the
    // description, where J2K can display and index them after details refresh.
    private fun alternativeNamesFromDocument(doc: Element): List<String> {
        val values = mutableListOf<String>()

        fun collect(raw: String) {
            val cleaned = raw.trim().replace(
                Regex("(?i)^(?:alternative|alternate|other|alt)\\s+(?:names?|titles?)\\s*:\\s*"),
                "",
            )
            splitAlternativeNames(cleaned).forEach(values::add)
        }

        // Madara/WordPress details, including heading/value pairs.
        doc.select(".post-content_item, .series-info-item, .manga-info-item, .info-item, tr, dl > div").forEach { row ->
            val heading = row.selectFirst(".summary-heading, .info-label, .label, dt, th, h4, h5, h6")
            if (heading != null && alternativeLabel.matches(heading.text().trim())) {
                val value = row.selectFirst(".summary-content, .info-value, .value, dd, td")
                if (value != null) collect(value.text())
            }
        }

        // Search cards may use inline fields (for example, Alt Names: Foo, Bar)
        // rather than the dedicated details rows.
        doc.select("span, small, p, div, li, b, strong").forEach { element ->
            val inline = inlineAlternativeNames.matchEntire(element.ownText().trim())
            if (inline != null) collect(inline.groupValues[1])
            if (alternativeLabel.matches(element.ownText().trim())) {
                element.nextElementSibling()?.let { collect(it.text()) }
                // Also handle <strong>Alternative Names:</strong> Alias in
                // a single paragraph, where the alias is a plain text node.
                val label = element.text().trim()
                element.parent()?.text()?.trim()?.takeIf {
                    it.length < 300 && it.startsWith(label, ignoreCase = true)
                }?.substring(label.length)?.trim()?.trimStart(':', '：', ' ')?.takeIf {
                    it.isNotBlank()
                }?.let(::collect)
            }
        }

        // Other common layouts, including a standalone alternative-title block.
        doc.select(
            ".alternative-titles, .alternative-title, .alternate-titles, .alt-titles, " +
                ".other-titles, .other-names, [itemprop=alternateName]",
        )
            .forEach { collect(it.text().ifBlank { it.attr("content") }) }
        doc.select("meta[name=alternative-title], meta[name=alternative-titles], meta[name=alternate-title]")
            .forEach { collect(it.attr("content")) }
        doc.select("[data-alt-titles], [data-alt-names], [data-alternative-names]").forEach { node ->
            listOf("data-alt-titles", "data-alt-names", "data-alternative-names")
                .forEach { key -> node.attr(key).takeIf { it.isNotBlank() }?.let(::collect) }
        }

        return values.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun detailFromDocument(doc: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw IllegalStateException("Series title not present")
        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("main img, article img")?.absUrl("src")
        genre = doc.select("a[href*='/manga-genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val synopsis = doc.select("main p, article p, .description p, .summary p")
            .map { it.text().trim() }.firstOrNull { it.length >= 80 }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val alternateNames = alternativeNamesFromDocument(doc)
            .filterNot { it.equals(title, ignoreCase = true) }
        description = if (alternateNames.isEmpty()) {
            synopsis
        } else {
            listOfNotNull(
                synopsis?.takeIf { it.isNotBlank() },
                formatAlternativeNames(alternateNames),
            ).joinToString("\n\n")
        }
        val text = doc.text()
        status = when {
            Regex("(?i)\\bcompleted\\b").containsMatchIn(text) -> SManga.COMPLETED
            Regex("(?i)\\bongoing\\b").containsMatchIn(text) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterDate(a: Element): Long {
        val row = a.closest("li, .wp-manga-chapter, .chapter-item, .chapter-row, tr") ?: a.parent() ?: a
        val candidates = buildList {
            row.select("time[datetime]").forEach { add(it.attr("datetime")) }
            row.select("time, .chapter-release-date, .chapter-date, .date, .post-on").forEach { add(it.text()) }
            add(row.text())
        }
        val zone = ZoneId.systemDefault()
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            runCatching { Instant.parse(candidate).toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { OffsetDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            val date = (datePattern.find(candidate) ?: shortDate.find(candidate))?.value
            if (date != null) {
                val day = runCatching { LocalDate.parse(date, formatter) }.getOrNull()
                    ?: runCatching { LocalDate.parse(date, shortFormatter) }.getOrNull()
                if (day != null) return day.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            val relative = relativeDate.find(candidate)
            if (relative != null) {
                val count = relative.groupValues[1].toLongOrNull() ?: continue
                val now = java.time.ZonedDateTime.now(zone)
                val past = when (relative.groupValues[2].lowercase(Locale.US)) {
                    "minute" -> now.minusMinutes(count)
                    "hour" -> now.minusHours(count)
                    "day" -> now.minusDays(count)
                    "week" -> now.minusWeeks(count)
                    "month" -> now.minusMonths(count)
                    "year" -> now.minusYears(count)
                    else -> null
                }
                if (past != null) return past.toInstant().toEpochMilli()
            }
            if (Regex("(?i)\\byesterday\\b").containsMatchIn(candidate)) {
                return LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            if (Regex("(?i)\\btoday\\b").containsMatchIn(candidate)) {
                return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        return 0L
    }

    private fun chaptersFromDocument(doc: Document): List<SChapter> = doc
        .select("a[href]")
        .asSequence()
        .filter { chapterPath.matches(it.attr("abs:href").toHttpUrlOrNullPath()) }
        .distinctBy { it.attr("abs:href").substringBefore('?').trimEnd('/') }
        .map { a ->
            val url = a.attr("abs:href")
            val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            val label = a.text().takeIf { it.contains("chapter", ignoreCase = true) }
                ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
            SChapter.create().apply {
                setUrlWithoutDomain(url)
                name = label.trim() // VIP stays in the list, with no source-side block.
                chapter_number = chapterLabel.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = parseChapterDate(a)
            }
        }
        .sortedByDescending { it.chapter_number }
        .toList()

    private data class NumberedImage(val element: Element, val match: MatchResult) {
        val number: Int get() = match.groupValues[3].toIntOrNull() ?: -1
        val url: String get() = match.value
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = pagesFromDocument(client.get(getChapterUrl(chapter)).asJsoup())

    private fun pagesFromDocument(document: Document): List<Page> {
        // Image URLs come from the current chapter HTML, not guessed CDN roots.
        // Reading an img's attributes locally does not trigger image requests.
        val numberedImages = document.select("img[src], img[data-src], img[data-lazy-src], img[data-original]")
            .mapNotNull { element ->
                listOf("data-src", "data-lazy-src", "data-original", "src")
                    .firstNotNullOfOrNull { attribute ->
                        numberedPage.matchEntire(element.absUrl(attribute))?.let { NumberedImage(element, it) }
                    }
            }

        if (numberedImages.isEmpty()) return emptyList()

        val first = numberedImages.firstOrNull { it.number == 1 } ?: numberedImages.first()
        val reference = first.match.groupValues
        val related = numberedImages.filter { image ->
            val groups = image.match.groupValues
            groups[1] == reference[1] &&
                groups[2].equals(reference[2], ignoreCase = true) &&
                groups[4].equals(reference[4], ignoreCase = true) &&
                groups[5] == reference[5] &&
                image.number > 0
        }

        if (related.isEmpty()) return numberedImages.mapIndexed { i, image -> Page(i, imageUrl = image.url) }

        // VIP chapter HTML announces how many images come after its previews.
        // Count only images appearing BEFORE that notice, since the page may
        // also include noscript / unrelated later images after the paywall.
        val vipNotice = document.select("h1, h2, h3, h4, p, div, span")
            .firstNotNullOfOrNull { element ->
                remainingPages.find(element.ownText())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { element to it }
            }

        val totalPages = if (vipNotice != null) {
            val (marker, remaining) = vipNotice
            val previewImages = related.associateBy { it.element }
            var previewLastPage = 0
            for (element in document.allElements) {
                if (element === marker) break
                previewImages[element]?.number?.let { page ->
                    previewLastPage = maxOf(previewLastPage, page)
                }
            }
            if (previewLastPage > 0) {
                previewLastPage + remaining
            } else {
                related.maxOf { it.number } // If the notice precedes all images, fail conservatively.
            }
        } else {
            // Free chapters expose their page images in the chapter HTML.
            // Their highest numbered image gives the same count without probes.
            related.maxOf { it.number }
        }

        if (totalPages !in 1..1000) {
            return related.mapIndexed { i, image -> Page(i, imageUrl = image.url) }
        }

        // Preserve original filename padding (Page01.webp must not become Page1.webp).
        val digits = reference[3].length
        val prefix = reference[1] + reference[2]
        val suffix = reference[4] + reference[5]
        return (1..totalPages).mapIndexed { i, number ->
            Page(i, imageUrl = prefix + number.toString().padStart(digits, '0') + suffix)
        }
    }
}
