package com.aarogyamandir.reels.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Turns a sequence of still images (with per-segment start times) plus a
 * recorded narration track into one vertical MP4, suitable for Reels.
 *
 * How it works:
 *  1. A hardware H.264 encoder is created with a Surface as its input.
 *  2. For every output frame we decide which image is "active" at that
 *     moment (from [timeline]) and draw it onto that Surface with a
 *     Canvas, scaled to fill the frame (center-crop).
 *  3. Frames are posted at real wall-clock pace, because the Surface/
 *     Canvas path timestamps each frame with the time it was posted.
 *  4. The encoder's compressed output is written into a MediaMuxer.
 *  5. The narration audio (already AAC, recorded via MediaRecorder) is
 *     copied track-for-track into the same muxer with MediaExtractor.
 *
 * This runs entirely on a background thread and takes roughly as long
 * as the video itself (a 60s reel takes ~60s to render) — show a
 * progress bar to the user while it runs.
 */
class VideoRenderer(
    private val width: Int = 1080,
    private val height: Int = 1920,
    private val frameRate: Int = 15,
    private val bitRate: Int = 5_000_000
) {

    fun render(
        timeline: List<Pair<Long, File>>,
        totalDurationMs: Long,
        audioFile: File,
        outFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        require(timeline.isNotEmpty()) { "Timeline can't be empty" }
        val sortedTimeline = timeline.sortedBy { it.first }
        val durationMs = max(totalDurationMs, sortedTimeline.last().first + 500)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        // Prepare the audio track up front so both tracks exist before muxer.start().
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(audioFile.absolutePath)
        var audioTrackIn = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until audioExtractor.trackCount) {
            val f = audioExtractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIn = i
                audioFormat = f
                break
            }
        }
        if (audioTrackIn >= 0) audioExtractor.selectTrack(audioTrackIn)
        val muxerAudioTrackIndex = if (audioFormat != null) muxer.addTrack(audioFormat) else -1

        var muxerStarted = false

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return else continue
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (!muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedData: ByteBuffer? = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted
                        ) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        val totalFrames = max(1, (durationMs * frameRate / 1000L).toInt())
        var currentPath: String? = null
        var currentBitmap: Bitmap? = null
        var timelineIdx = 0
        val startNanos = System.nanoTime()

        try {
            var frameCount = 0
            while (frameCount < totalFrames) {
                // Pace real time so each posted frame's implicit timestamp is correct.
                val targetNanos = startNanos + frameCount.toLong() * 1_000_000_000L / frameRate
                val waitNanos = targetNanos - System.nanoTime()
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
                }

                val nowMs = frameCount * 1000L / frameRate
                while (timelineIdx + 1 < sortedTimeline.size && sortedTimeline[timelineIdx + 1].first <= nowMs) {
                    timelineIdx++
                }
                val path = sortedTimeline[timelineIdx].second.absolutePath
                if (path != currentPath) {
                    currentBitmap?.recycle()
                    currentBitmap = decodeSampled(path, width, height)
                    currentPath = path
                }

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    currentBitmap?.let { drawCoverFit(canvas, it, width, height) }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(false)
                frameCount++
                onProgress(frameCount.toFloat() / totalFrames * 0.9f)
            }
            currentBitmap?.recycle()
            drainEncoder(true)
        } finally {
            encoder.stop()
            encoder.release()
            inputSurface.release()
        }

        // Copy the narration audio samples into the same muxer.
        if (muxerAudioTrackIndex >= 0) {
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = audioExtractor.sampleTime
                info.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrackIndex, buffer, info)
                audioExtractor.advance()
            }
        }
        audioExtractor.release()

        muxer.stop()
        muxer.release()
        onProgress(1f)
    }

    private fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w / (sample * 2) >= reqWidth && h / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        val realOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, realOpts)
            ?: throw IllegalStateException("Could not decode image: $path")
    }

    private fun drawCoverFit(canvas: Canvas, bmp: Bitmap, targetW: Int, targetH: Int) {
        canvas.drawColor(Color.BLACK)
        val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        val scale: Float
        val dx: Float
        val dy: Float
        if (bmpRatio > targetRatio) {
            scale = targetH.toFloat() / bmp.height.toFloat()
            dx = (targetW - bmp.width * scale) / 2f
            dy = 0f
        } else {
            scale = targetW.toFloat() / bmp.width.toFloat()
            dx = 0f
            dy = (targetH - bmp.height * scale) / 2f
        }
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, matrix, paint)
    }
}
