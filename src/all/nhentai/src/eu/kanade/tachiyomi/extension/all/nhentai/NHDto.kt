package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
data class GalleryListResponse(
    val result: List<GalleryListItem>,
    val num_pages: Int,
    val per_page: Int = 25,
    val page: Int = 1,
    val total: Int? = null,
)

@Serializable
data class GalleryListItem(
    val id: Int,
    val media_id: String,
    val thumbnail: String,
    val english_title: String,
    val japanese_title: String? = null,
    val tag_ids: List<Int> = emptyList(),
)

@Serializable
data class GalleryDetail(
    val id: Int,
    val media_id: String,
    val title: GalleryTitle,
    val cover: CoverInfo,
    val thumbnail: CoverInfo,
    val upload_date: Long,
    val tags: List<TagResponse>,
    val num_pages: Int,
    val num_favorites: Int,
)

@Serializable
data class GalleryTitle(
    val english: String,
    val japanese: String? = null,
    val pretty: String,
)

@Serializable
data class CoverInfo(
    val path: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class TagResponse(
    val id: Int,
    val type: String,
    val name: String,
    val slug: String,
    val url: String,
    val count: Int,
)

@Serializable
data class GalleryPagesResponse(
    val gallery_id: Int,
    val media_id: String,
    val num_pages: Int,
    val pages: List<PageInfo>,
)

@Serializable
data class PageInfo(
    val number: Int,
    val path: String,
    val width: Int,
    val height: Int,
    val thumbnail: String,
)