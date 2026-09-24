package com.aarogyamandir.reels.model

/**
 * One image that the user has picked and labelled with keywords.
 * fileName points to a copy stored inside the app's private storage
 * (so it keeps working even if the original is deleted from the gallery).
 */
data class TaggedImage(
    val fileName: String,
    val tags: List<String>
)
