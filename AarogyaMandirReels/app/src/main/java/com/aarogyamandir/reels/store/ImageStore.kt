package com.aarogyamandir.reels.store

import android.content.Context
import android.net.Uri
import com.aarogyamandir.reels.model.TaggedImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persists the user's tagged-image library to the app's private storage
 * as a small JSON index, and keeps a private copy of every picked image.
 */
class ImageStore(private val context: Context) {

    private val imagesDir: File by lazy {
        File(context.filesDir, "tagged_images").apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File by lazy { File(context.filesDir, "tagged_images_index.json") }

    fun listImages(): List<TaggedImage> {
        if (!indexFile.exists()) return emptyList()
        val text = indexFile.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val out = mutableListOf<TaggedImage>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val fileName = obj.getString("fileName")
            val tagsArr = obj.getJSONArray("tags")
            val tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }
            out.add(TaggedImage(fileName, tags))
        }
        return out
    }

    private fun saveIndex(images: List<TaggedImage>) {
        val arr = JSONArray()
        for (img in images) {
            val obj = JSONObject()
            obj.put("fileName", img.fileName)
            val tagsArr = JSONArray()
            img.tags.forEach { tagsArr.put(it) }
            obj.put("tags", tagsArr)
            arr.put(obj)
        }
        indexFile.writeText(arr.toString())
    }

    fun addImage(sourceUri: Uri, tags: List<String>): TaggedImage {
        val fileName = "img_${UUID.randomUUID()}.jpg"
        val destFile = File(imagesDir, fileName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val newImage = TaggedImage(fileName, cleanTags)
        val current = listImages().toMutableList()
        current.add(newImage)
        saveIndex(current)
        return newImage
    }

    fun removeImage(image: TaggedImage) {
        val current = listImages().toMutableList()
        current.removeAll { it.fileName == image.fileName }
        saveIndex(current)
        File(imagesDir, image.fileName).delete()
    }

    fun fileFor(image: TaggedImage): File = File(imagesDir, image.fileName)
}
