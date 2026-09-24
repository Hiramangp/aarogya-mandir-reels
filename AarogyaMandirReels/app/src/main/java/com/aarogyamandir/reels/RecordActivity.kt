package com.aarogyamandir.reels

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aarogyamandir.reels.model.TaggedImage
import com.aarogyamandir.reels.model.TimelineEvent
import com.aarogyamandir.reels.speech.SpeechEngine
import com.aarogyamandir.reels.store.ImageStore
import java.io.File

/**
 * The core "automatic" screen: records narration audio while live speech
 * recognition matches spoken words against each image's tags and swaps
 * the on-screen image automatically. Every switch (automatic or manual
 * tap) is logged with its timestamp, so ExportActivity can rebuild the
 * exact same sequence as a real video afterwards.
 */
class RecordActivity : AppCompatActivity() {

    private lateinit var store: ImageStore
    private lateinit var images: List<TaggedImage>
    private lateinit var imageView: ImageView
    private lateinit var captionText: TextView
    private lateinit var recordButton: Button
    private lateinit var timerText: TextView

    private var speechEngine: SpeechEngine? = null
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var recordStartElapsed = 0L
    private var currentImageIndex = 0
    private val timeline = mutableListOf<TimelineEvent>()
    private lateinit var audioFile: File
    private val timerHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)
        store = ImageStore(this)
        images = store.listImages()

        imageView = findViewById(R.id.imageView)
        captionText = findViewById(R.id.captionText)
        recordButton = findViewById(R.id.recordButton)
        timerText = findViewById(R.id.timerText)

        if (images.isEmpty()) {
            Toast.makeText(this, "आधी Images Tag करा (Step 1)", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        showImage(0)

        imageView.setOnClickListener {
            if (recording) advanceImageManually()
        }
        recordButton.setOnClickListener {
            if (!recording) startRecording() else stopRecording()
        }
    }

    private fun showImage(index: Int) {
        currentImageIndex = index
        val file = store.fileFor(images[index])
        imageView.setImageURI(Uri.fromFile(file))
    }

    private fun advanceImageManually() {
        val next = (currentImageIndex + 1) % images.size
        showImage(next)
        logTimelineEvent()
    }

    private fun logTimelineEvent() {
        val elapsed = SystemClock.elapsedRealtime() - recordStartElapsed
        val file = store.fileFor(images[currentImageIndex])
        timeline.add(TimelineEvent(elapsed, file.absolutePath))
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        timeline.clear()
        audioFile = File(cacheDir, "narration_${System.currentTimeMillis()}.m4a")

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(audioFile.absolutePath)
            prepare()
            start()
        }

        recordStartElapsed = SystemClock.elapsedRealtime()
        showImage(0)
        logTimelineEvent()

        speechEngine = SpeechEngine(this) { text, _ ->
            runOnUiThread {
                captionText.text = text
                matchAndSwitch(text)
            }
        }
        speechEngine?.start()

        recording = true
        recordButton.text = "Stop Kara"
        startTimer()
    }

    /** Picks the tagged image whose (longest) tag appears in the recognized text. */
    private fun matchAndSwitch(spokenText: String) {
        val lower = spokenText.lowercase()
        var bestIndex = -1
        var bestTagLength = 0
        for ((idx, img) in images.withIndex()) {
            for (tag in img.tags) {
                if (tag.isNotBlank() && lower.contains(tag) && tag.length > bestTagLength) {
                    bestTagLength = tag.length
                    bestIndex = idx
                }
            }
        }
        if (bestIndex != -1 && bestIndex != currentImageIndex) {
            showImage(bestIndex)
            logTimelineEvent()
        }
    }

    private fun startTimer() {
        val startTime = SystemClock.elapsedRealtime()
        val update = object : Runnable {
            override fun run() {
                if (!recording) return
                val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000
                timerText.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                timerHandler.postDelayed(this, 500)
            }
        }
        timerHandler.post(update)
    }

    private fun stopRecording() {
        recording = false
        recordButton.text = "Record Kara"
        speechEngine?.stop()
        speechEngine = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Can throw if stop() is called almost immediately after start(); safe to ignore.
        }
        recorder?.release()
        recorder = null

        val totalDuration = SystemClock.elapsedRealtime() - recordStartElapsed
        if (totalDuration < 1000 || timeline.isEmpty()) {
            Toast.makeText(this, "Recording खूप छोटी आहे, पुन्हा प्रयत्न करा", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, ExportActivity::class.java).apply {
            putExtra(ExportActivity.EXTRA_AUDIO_PATH, audioFile.absolutePath)
            putExtra(ExportActivity.EXTRA_TOTAL_DURATION_MS, totalDuration)
            putParcelableArrayListExtra(ExportActivity.EXTRA_TIMELINE, ArrayList(timeline))
        }
        startActivity(intent)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            Toast.makeText(this, "Recording साठी Microphone Permission आवश्यक आहे", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine?.stop()
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
    }
}
