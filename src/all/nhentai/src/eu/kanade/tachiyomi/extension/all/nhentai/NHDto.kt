package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
data class GalleryDetail(
    val id: Int,
    val title: GalleryTitle,
    val thumbnail: GalleryImage,
    val tags: List<Tag> = emptyList(),
    val num_pages: Int,
    val num_favorites: Int,
    val upload_date: Long,
)

@Serializable
data class GalleryTitle(
    val english: String = "",
    val japanese: String? = null,
    val pretty: String = "",
)

@Serializable
data class GalleryImage(
    val path: String,
)

// API v2 PaginatedResponse does NOT include a "page" field — only num_pages.
// We track current page ourselves in the request.
@Serializable
data class GalleryListResponse(
    val result: List<GalleryListItem>,
    val num_pages: Int,
)

@Serializable
data class GalleryListItem(
    val id: Int,
    val english_title: String = "",
    val japanese_title: String? = null,
    val thumbnail: String,
)

@Serializable
data class GalleryPagesResponse(
    val pages: List<GalleryPage>,
)

@Serializable
data class GalleryPage(
    val path: String,
)

@Serializable
data class Tag(
    val name: String,
    val type: String,
)
