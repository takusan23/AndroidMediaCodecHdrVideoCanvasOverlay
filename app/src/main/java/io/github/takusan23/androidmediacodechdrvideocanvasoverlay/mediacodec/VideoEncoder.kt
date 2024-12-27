package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.yield

/** MediaCodec を使って動画をエンコードする */
class VideoEncoder {
    private var encodeMediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    /**
     * MediaCodec エンコーダーの準備をする
     *
     * @param videoFilePath 保存先
     * @param codecName コーデック名
     * @param bitRate ビットレート
     * @param frameRate フレームレート
     * @param keyframeInterval キーフレームの間隔
     * @param outputVideoWidth 動画の高さ
     * @param outputVideoHeight 動画の幅
     * @param tenBitHdrParametersOrNullSdr SDR 動画の場合は null。HDR でエンコードする場合は[TenBitHdrParameters]を埋めてください
     */
    fun prepare(
        videoFilePath: String,
        outputVideoWidth: Int = 1280,
        outputVideoHeight: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = 1_000_000,
        keyframeInterval: Int = 1,
        codecName: String = MediaFormat.MIMETYPE_VIDEO_HEVC,
        tenBitHdrParametersOrNullSdr: TenBitHdrParameters? = null
    ) {
        // エンコーダーにセットするMediaFormat
        // コーデックが指定されていればそっちを使う
        val videoMediaFormat = MediaFormat.createVideoFormat(codecName, outputVideoWidth, outputVideoHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // 10-bit HDR のパラメーターをセット
            if (tenBitHdrParametersOrNullSdr != null) {
                setInteger(MediaFormat.KEY_PROFILE, tenBitHdrParametersOrNullSdr.codecProfile)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, tenBitHdrParametersOrNullSdr.colorStandard)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, tenBitHdrParametersOrNullSdr.colorTransfer)
                setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
            }
        }

        // マルチプレクサ
        mediaMuxer = MediaMuxer(videoFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encodeMediaCodec = MediaCodec.createEncoderByType(codecName).apply {
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** エンコーダーの入力になる[Surface]を取得する */
    fun getInputSurface(): Surface = encodeMediaCodec!!.createInputSurface()

    /** エンコーダーを開始する */
    suspend fun start() {
        val encodeMediaCodec = encodeMediaCodec ?: return
        val mediaMuxer = mediaMuxer ?: return

        var videoTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        encodeMediaCodec.start()

        try {
            while (true) {
                // yield() で 占有しないよう
                yield()

                // Surface経由でデータを貰って保存する
                val encoderStatus = encodeMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (0 <= encoderStatus) {
                    if (bufferInfo.size > 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val encodedData = encodeMediaCodec.getOutputBuffer(encoderStatus)!!
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    encodeMediaCodec.releaseOutputBuffer(encoderStatus, false)
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // MediaMuxerへ映像トラックを追加するのはこのタイミングで行う
                    // このタイミングでやると固有のパラメーターがセットされたMediaFormatが手に入る(csd-0 とか)
                    // 映像がぶっ壊れている場合（緑で塗りつぶされてるとか）は多分このあたりが怪しい
                    val newFormat = encodeMediaCodec.outputFormat
                    videoTrackIndex = mediaMuxer.addTrack(newFormat)
                    mediaMuxer.start()
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                }
            }
        } finally {
            // エンコーダー終了
            encodeMediaCodec.signalEndOfInputStream()
            encodeMediaCodec.stop()
            encodeMediaCodec.release()
            // コンテナフォーマットに書き込む処理も終了
            mediaMuxer.stop()
            mediaMuxer.release()
        }
    }

    /**
     * 10-bit HDR の動画を作成するためのパラメーター。
     * 色空間とガンマカーブを指定してください。
     *
     * HLG 形式の HDR の場合は[MediaFormat.COLOR_STANDARD_BT2020]と[MediaFormat.COLOR_TRANSFER_HLG]と[MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10]。
     * デフォルト引数は HLG。
     *
     * 定数自体は Android 7 からありますが、10-bit HDR の動画編集が（MediaCodec が？） 13 以上なので。
     *
     * @param colorStandard 色空間
     * @param colorTransfer ガンマカーブ
     * @param codecProfile コーデックのプロファイル
     */
    data class TenBitHdrParameters(
        val colorStandard: Int = MediaFormat.COLOR_STANDARD_BT2020,
        val colorTransfer: Int = MediaFormat.COLOR_TRANSFER_HLG,
        val codecProfile: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
    )

    companion object {
        /** タイムアウト */
        private const val TIMEOUT_US = 0L
    }
}