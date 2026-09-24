package com.aarogyamandir.reels

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aarogyamandir.reels.store.ImageStore

/**
 * Lets the user build their keyword -> image library:
 * pick a photo from the gallery, then type one or more keywords
 * (comma separated). During recording, whichever tag is heard in the
 * live speech makes that image appear on screen automatically.
 */
class ImageTagActivity : AppCompatActivity() {

    private lateinit var store: ImageStore
    private lateinit var listContainer: LinearLayout
    private var pendingUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingUri = uri
            askForTags()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_tag)
        store = ImageStore(this)
        listContainer = findViewById(R.id.listContainer)

        findViewById<Button>(R.id.btnAddImage).setOnClickListener {
            pickImage.launch("image/*")
        }

        refreshList()
    }

    private fun askForTags() {
        val input = EditText(this)
        input.hint = "उदा: jumping jacks, jump"

        AlertDialog.Builder(this)
            .setTitle("या Image साठी Keyword टाका")
            .setMessage("Voice मध्ये हा शब्द बोलल्यावर ही Image दाखवली जाईल. एकापेक्षा जास्त शब्द Comma ने वेगळे लिहा.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val tags = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val uri = pendingUri
                if (tags.isEmpty() || uri == null) {
                    Toast.makeText(this, "किमान एक keyword लिहा", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.addImage(uri, tags)
                pendingUri = null
                refreshList()
            }
            .setNegativeButton("Cancel") { _, _ -> pendingUri = null }
            .show()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val images = store.listImages()
        for (img in images) {
            val row = layoutInflater.inflate(R.layout.item_tagged_image, listContainer, false)
            row.findViewById<ImageView>(R.id.itemImage).setImageURI(Uri.fromFile(store.fileFor(img)))
            row.findViewById<TextView>(R.id.itemTags).text = img.tags.joinToString(", ")
            row.findViewById<Button>(R.id.itemDelete).setOnClickListener {
                store.removeImage(img)
                refreshList()
            }
            listContainer.addView(row)
        }
        if (images.isEmpty()) {
            val empty = TextView(this)
            empty.text = "अजून कुठलीही Image Tag केलेली नाही. वर '+ Add Image' दाबा."
            empty.setPadding(24, 24, 24, 24)
            listContainer.addView(empty)
        }
    }
}
