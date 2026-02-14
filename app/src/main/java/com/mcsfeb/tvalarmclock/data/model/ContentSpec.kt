package com.mcsfeb.tvalarmclock.data.model

/**
 * Represents the specific content to be launched, abstracting app-specific identifiers.
 */
data class ContentSpec(
    val app: StreamingApp,
    val contentType: ContentType,
    val identifiers: Map<String, String>, // e.g., "episodeId" -> "80057281", "titleId" -> "80189685", "channel" -> "ESPN"
    val searchQuery: String? = null // Optional search query if direct deep link isn't available
) {
    /** Generates a unique key for SharedPreferences based on app, type, and all identifiers. */
    fun toPersistentKey(): String {
        val sortedIdentifiers = identifiers.entries.sortedBy { it.key }.joinToString("_") { "${it.key}_${it.value}" }
        return "${app.name}_${contentType.name}_$sortedIdentifiers"
    }
}

enum class ContentType {
    EPISODE,
    MOVIE,
    LIVE
}
