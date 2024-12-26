package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.yield
import java.nio.ByteBuffer

object MediaMuxerTool {

    /** それぞれ音声トラック、映像トラックを取り出して、2つのトラックを一つの mp4 にする */
    @SuppressLint("WrongConstant")
    suspend fun mixAvTrack(
        context: Context,
        audioTrackUri: Uri,
        videoTrackFilePath: String,
        resultFilePath: String
    ) {
        // 出力先
        val mediaMuxer = MediaMuxer(resultFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 音声は元の動画データから
        // 映像は作った仮ファイルから
        val (audioExtractor, audioFormat) = createMediaExtractor(context, audioTrackUri, "audio/")
        val (videoExtractor, videoFormat) = createMediaExtractor(videoTrackFilePath, "video/")

        // MediaMuxer に追加して開始
        val audioTrackIndex = mediaMuxer.addTrack(audioFormat)
        val videoTrackIndex = mediaMuxer.addTrack(videoFormat)
        mediaMuxer.start()

        // MediaExtractor からトラックを取り出して、MediaMuxer に入れ直す処理
        listOf(
            audioExtractor to audioTrackIndex,
            videoExtractor to videoTrackIndex
        ).forEach { (extractor, trackIndex) ->
            val byteBuffer = ByteBuffer.allocate(1024 * 4096)
            val bufferInfo = MediaCodec.BufferInfo()
            // データが無くなるまで回す
            while (true) {
                yield()
                // データを読み出す
                val offset = byteBuffer.arrayOffset()
                bufferInfo.size = extractor.readSampleData(byteBuffer, offset)
                // もう無い場合
                if (bufferInfo.size < 0) break
                // 書き込む
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags // Lintがキレるけど黙らせる
                mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                // 次のデータに進める
                extractor.advance()
            }
            // あとしまつ
            extractor.release()
        }
        // 後始末
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    /** [Uri]から[MediaExtractor]を作る */
    private fun createMediaExtractor(context: Context, uri: Uri, mimeType: String): Pair<MediaExtractor, MediaFormat> {
        val mediaExtractor = MediaExtractor().apply {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                setDataSource(it.fileDescriptor)
            }
        }
        val (trackIndex, mediaFormat) = (0 until mediaExtractor.trackCount)
            .map { index -> mediaExtractor.getTrackFormat(index) }
            .withIndex()
            .first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith(mimeType) == true }
        mediaExtractor.selectTrack(trackIndex)
        return mediaExtractor to mediaFormat
    }

    /** [filePath]から[MediaExtractor]を作る */
    private fun createMediaExtractor(filePath: String, mimeType: String): Pair<MediaExtractor, MediaFormat> {
        val mediaExtractor = MediaExtractor().apply {
            setDataSource(filePath)
        }
        val (trackIndex, mediaFormat) = (0 until mediaExtractor.trackCount)
            .map { index -> mediaExtractor.getTrackFormat(index) }
            .withIndex()
            .first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith(mimeType) == true }
        mediaExtractor.selectTrack(trackIndex)
        return mediaExtractor to mediaFormat
    }
}