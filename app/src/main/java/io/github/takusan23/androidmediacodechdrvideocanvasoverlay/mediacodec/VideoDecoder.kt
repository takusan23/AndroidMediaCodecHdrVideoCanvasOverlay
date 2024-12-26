package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.yield


/** MediaCodec を使って動画をデコードする */
class VideoDecoder {

    private var decodeMediaCodec: MediaCodec? = null
    private var mediaExtractor: MediaExtractor? = null

    /**
     * デコードの準備をする
     *
     * @param context [Context]
     * @param uri PhotoPicker 等で選んだやつ
     * @param outputSurface デコードした映像の出力先
     * @param toToneMapSdr SDR にトーンマッピングする場合は true
     */
    fun prepare(
        context: Context,
        uri: Uri,
        outputSurface: Surface,
        toToneMapSdr: Boolean = false
    ) {
        val mediaExtractor = MediaExtractor().apply {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                setDataSource(it.fileDescriptor)
            }
        }
        this.mediaExtractor = mediaExtractor

        // 動画トラックを探す
        val (trackIndex, mediaFormat) = (0 until mediaExtractor.trackCount)
            .map { mediaExtractor.getTrackFormat(it) }
            .withIndex()
            .first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        mediaExtractor.selectTrack(trackIndex)

        // SDR に変換する場合は、MediaFormat にオプション指定
        if (toToneMapSdr) {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        // MediaCodec を作る
        var codecName = mediaFormat.getString(MediaFormat.KEY_MIME)!!

        // ドルビービジョンの動画で、ドルビービジョンのデコーダーがない場合は HEVC としてデコードさせる
        // ただ、本物のドルビービジョンは HLG と互換性がないので、HLG と互換性があるドルビービジョンのみ対応できる
        if (codecName == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION && !hasDolbyVisionDecoder()) {
            codecName = MediaFormat.MIMETYPE_VIDEO_HEVC
        }

        decodeMediaCodec = MediaCodec.createDecoderByType(codecName).apply {
            configure(mediaFormat, outputSurface, null, 0)
        }
        decodeMediaCodec?.start()
    }

    /** ドルビービジョンのデコーダーが存在するか */
    private fun hasDolbyVisionDecoder(): Boolean {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)

        val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val decoder = mediaCodecList.findDecoderForFormat(format)

        // true if a Dolby Vision decoder is found, false otherwise.
        return decoder != null
    }

    /** 破棄する */
    fun destroy() {
        decodeMediaCodec?.stop()
        decodeMediaCodec?.release()
        mediaExtractor?.release()
    }

    /**
     * 次の時間のフレームを取得する
     *
     * @param seekToMs 欲しいフレームの時間
     * @return 次のフレームがない場合は null。そうじゃない場合は動画フレームの時間
     */
    suspend fun seekNextFrame(seekToMs: Long): Long? {
        val decodeMediaCodec = decodeMediaCodec!!
        val mediaExtractor = mediaExtractor!!

        // advance() で false を返したことがある場合、もうデータがない。getSampleTime も -1 になる。
        if (mediaExtractor.sampleTime == -1L) {
            return null
        }

        var isRunning = true
        val bufferInfo = MediaCodec.BufferInfo()
        var returnValue: Long? = null
        while (isRunning) {

            // キャンセル時
            yield()

            // コンテナフォーマットからサンプルを取り出し、デコーダーに渡す
            // シークしないことで、連続してフレームを取得する場合にキーフレームまで戻る必要がなくなり、早くなる
            val inputBufferIndex = decodeMediaCodec.dequeueInputBuffer(TIMEOUT_US)
            if (0 <= inputBufferIndex) {
                // デコーダーへ流す
                val inputBuffer = decodeMediaCodec.getInputBuffer(inputBufferIndex)!!
                val size = mediaExtractor.readSampleData(inputBuffer, 0)
                decodeMediaCodec.queueInputBuffer(inputBufferIndex, 0, size, mediaExtractor.sampleTime, 0)
            }

            // デコーダーから映像を受け取る部分
            var isDecoderOutputAvailable = true
            while (isDecoderOutputAvailable) {

                // キャンセル時
                yield()

                // デコード結果が来ているか
                val outputBufferIndex = decodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // リトライが必要
                        isDecoderOutputAvailable = false
                    }

                    0 <= outputBufferIndex -> {
                        // ImageReader ( Surface ) に描画する
                        val doRender = bufferInfo.size != 0
                        decodeMediaCodec.releaseOutputBuffer(outputBufferIndex, doRender)
                        if (doRender) {
                            // 欲しいフレームの時間に到達した場合、ループを抜ける
                            val presentationTimeMs = bufferInfo.presentationTimeUs / 1000
                            if (seekToMs <= presentationTimeMs) {
                                isRunning = false
                                isDecoderOutputAvailable = false
                                returnValue = presentationTimeMs
                            }
                        }
                    }
                }
            }

            // 次に進める。デコーダーにデータを入れた事を確認してから。
            // advance() が false の場合はもうデータがないので、break
            if (0 <= inputBufferIndex) {
                val isEndOfFile = !mediaExtractor.advance()
                if (isEndOfFile) {
                    // return で false（フレームが取得できない旨）を返す
                    returnValue = null
                    break
                }
            }
        }

        return returnValue
    }

    companion object {
        /** MediaCodec タイムアウト */
        private const val TIMEOUT_US = 0L
    }
}