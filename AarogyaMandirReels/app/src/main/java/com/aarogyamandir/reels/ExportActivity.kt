package com.aarogyamandir.reels

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aarogyamandir.reels.model.TimelineEvent
import com.aarogyamandir.reels.video.VideoRenderer
import java.io.File

/**
 * Renders the recorded narration + timed image sequence into a final
 * MP4 in the background, saves it to Movies/AarogyaMandirReels, and
 * offers a direct share button.
 */
class ExportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_TOTAL_DURATION_MS = "total_duration_ms"
        const val EXTRA_TIMELINE = "timeline"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var shareButton: Button
    private var resultUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        shareButton = findViewById(R.id.shareButton)
        shareButton.visibility = View.GONE
        shareButton.setOnClickListener { shareVideo() }

        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        val totalDuration = intent.getLongExtra(EXTRA_TOTAL_DURATION_MS, 0)
        val timelineEvents: ArrayList<TimelineEvent> = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_TIMELINE, TimelineEvent::class.java) ?: arrayListOf()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_TIMELINE) ?: arrayListOf()
        }

        if (audioPath == null || timelineEvents.isEmpty()) {
            statusText.text = "Recording data सापडली नाही."
            return
        }

        statusText.text = "Video तयार होत आहे... याला थोडा वेळ लागू शकतो."
        progressBar.progress = 0

        Thread {
            try {
                val timelinePairs = timelineEvents.map { it.timeMs to File(it.imagePath) }
                val outFile = File(cacheDir, "reel_${System.currentTimeMillis()}.mp4")
                val renderer = VideoRenderer()
                renderer.render(
                    timeline = timelinePairs,
                    totalDurationMs = totalDuration,
                    audioFile = File(audioPath),
                    outFile = outFile,
                    onProgress = { p ->
                        runOnUiThread { progressBar.progress = (p * 100).toInt() }
                    }
                )
                val uri = saveToGallery(outFile)
                runOnUiThread {
                    resultUri = uri
                    if (uri != null) {
                        statusText.text = "Video तयार झाला! Movies/AarogyaMandirReels मध्ये Save झालाय."
                        shareButton.visibility = View.VISIBLE
                    } else {
                        statusText.text = "Video तयार झाला, पण Save करताना अडचण आली."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Export मध्ये Error आली: ${e.message}"
                }
            }
        }.start()
    }

    private fun saveToGallery(file: File): Uri? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AarogyaMandirReels")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun shareVideo() {
        val uri = resultUri ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Reel"))
    }
}
