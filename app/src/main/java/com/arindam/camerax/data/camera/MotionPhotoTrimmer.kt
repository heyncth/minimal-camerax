package com.arindam.camerax.data.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Data: keyframe-aligned trim of the rolling motion pre-roll clip to the live-photo
 * window ([startMs, endMs] around the shutter). Video track only — motion photos are
 * silent. Snaps the start back to the previous sync sample, so the clip may run a bit
 * longer than requested (callers derive the presentation timestamp from [TrimmedClip.startMs]).
 * Blocking decode/mux — call on `cameraExecutor`, never the main thread.
 */
internal object MotionPhotoTrimmer {

    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024

    /** Result of a successful trim: the output file and its actual start time in ms. */
    data class TrimmedClip(val file: File, val startMs: Long)

    /**
     * Writes the video samples of [input] whose timestamps fall in [startMs, endMs]
     * into an MP4 at [output]. Returns null on any failure (output removed).
     */
    fun trim(input: File, startMs: Long, endMs: Long, output: File): TrimmedClip? {
        if (endMs <= startMs || !input.exists()) return null
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            val videoFormat = format ?: return null
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            // Keyframe-aligned: land on the sync sample at or before the window start.
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime
            if (actualStartUs < 0) return null

            val outMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outMuxer
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                outMuxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            val outTrack = outMuxer.addTrack(videoFormat)
            outMuxer.start()

            val maxInputSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                MAX_SAMPLE_BYTES
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceIn(256 * 1024, MAX_SAMPLE_BYTES))
            val info = MediaCodec.BufferInfo()
            val endUs = endMs * 1000
            var wrote = false
            while (true) {
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0 || sampleUs > endUs) break
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = sampleUs
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                outMuxer.writeSampleData(outTrack, buffer, info)
                wrote = true
                if (!extractor.advance()) break
            }
            outMuxer.stop()
            outMuxer.release()
            muxer = null
            if (wrote) {
                TrimmedClip(output, actualStartUs / 1000)
            } else {
                output.delete()
                null
            }
        } catch (error: Throwable) {
            runCatching { muxer?.release() }
            output.delete()
            null
        } finally {
            extractor.release()
        }
    }
}
