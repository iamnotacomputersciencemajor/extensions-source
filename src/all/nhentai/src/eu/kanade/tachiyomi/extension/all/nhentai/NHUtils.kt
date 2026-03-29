package eu.kanade.tachiyomi.extension.all.nhentai

object NHUtils {
    fun getArtists(tags: List<TagResponse>): String =
        tags.filter { it.type == "artist" }.joinToString(", ") { it.name }

    fun getGroups(tags: List<TagResponse>): String? =
        tags.filter { it.type == "group" }
            .joinToString(", ") { it.name }
            .takeIf { it.isNotBlank() }

    fun getTags(tags: List<TagResponse>): String =
        tags.filter { it.type == "tag" }.joinToString(", ") { it.name }

    fun getTagDescription(tags: List<TagResponse>): String {
        val grouped = tags.groupBy { it.type }
        return buildString {
            grouped["category"]?.joinToString { it.name }?.let { append("Categories: $it\n") }
            grouped["parody"]?.joinToString { it.name }?.let { append("Parodies: $it\n") }
            grouped["character"]?.joinToString { it.name }?.let { append("Characters: $it\n") }
            grouped["language"]?.joinToString { it.name }?.let { append("Language: $it\n") }
        }
    }
}