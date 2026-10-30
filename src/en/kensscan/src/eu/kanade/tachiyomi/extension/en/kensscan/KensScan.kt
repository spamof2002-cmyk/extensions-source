package eu.kanade.tachiyomi.extension.en.kensscan

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.LocalDate
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
abstract class KensScan : KeiSource() {

    private val seriesPath = Regex("^/manga/[^/]+/?$", RegexOption.IGNORE_CASE)
    private val chapterPath = Regex("^/manga/[^/]+/chapter-[^/]+/?$", RegexOption.IGNORE_CASE)
    private val chapterLabel = Regex("(?i)chapter[-\\s]*([0-9]+(?:[.][0-9]+)?)")
    private val datePattern = Regex(
        "(?i)\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+[0-9]{1,2},\\s+[0-9]{4}\\b",
    )
    private val formatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)

    // Match the terminal Page number, not the chapter number in the URL.
    private val numberedPage = Regex(
        "^(https?://[^?#]+/)(Page)([0-9]+)(\\.(?:webp|png|jpe?g))(\\?[^#]*)?$",
        RegexOption.IGNORE_CASE,
    )

    private val remainingPages = Regex(
        "\\b([0-9]{1,4})\\s+more\\s+pages\\s+are\\s+waiting\\b",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun getPopularManga(page: Int): MangasPage = listing(page)

    // The site's /manga/ listing defaults to newest; no separate latest
    // endpoint is needed, and regular library updates do not call this.
    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(page)

    private suspend fun listing(page: Int): MangasPage {
        val url = if (page <= 1) "$baseUrl/manga/" else "$baseUrl/manga/page/$page/"
        val document = client.get(url).asJsoup()
        val mangas = listingFromDocument(document)
        val next = document.select("a[href]").any { a ->
            a.attr("abs:href").trimEnd('/').substringBefore('?')
                .endsWith("/manga/page/${page + 1}")
        }
        return MangasPage(mangas, next)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return listing(page)
        // The site's server-side search markup isn't confirmed. This is the
        // standard WordPress search path; only matching series are returned.
        val encoded = URLEncoder.encode(query, "UTF-8")
        val pagePart = if (page > 1) "&paged=$page" else ""
        val doc = client.get("$baseUrl/?s=$encoded$pagePart").asJsoup()
        val mangas = listingFromDocument(doc)
            .filter { it.title.contains(query.trim(), ignoreCase = true) }
        val next = doc.select("a[rel=next], .pagination a.next, a.next").isNotEmpty()
        return MangasPage(mangas, next)
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
        return detailFromDocument(client.get(url.toString()).asJsoup())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Single request for details and chapters; no duplicate fetch.
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        val details = if (fetchDetails) detailFromDocument(doc) else manga
        val resultChapters = if (fetchChapters) chaptersFromDocument(doc) else chapters
        return SMangaUpdate(details, resultChapters)
    }

    private fun detailFromDocument(doc: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw IllegalStateException("Series title not present")
        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("main img, article img")?.absUrl("src")
        genre = doc.select("a[href*='/manga-genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        description = doc.select("main p, article p, .description p, .summary p")
            .map { it.text().trim() }.firstOrNull { it.length >= 80 }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val text = doc.text()
        status = when {
            Regex("(?i)\\bcompleted\\b").containsMatchIn(text) -> SManga.COMPLETED
            Regex("(?i)\\bongoing\\b").containsMatchIn(text) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
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
            val date = datePattern.find(a.parent()?.text().orEmpty())?.value
            SChapter.create().apply {
                setUrlWithoutDomain(url)
                name = label.trim() // VIP stays in the list, with no source-side block.
                chapter_number = chapterLabel.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = date?.let { dateString ->
                    runCatching {
                        LocalDate.parse(dateString, formatter)
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                } ?: 0L
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
