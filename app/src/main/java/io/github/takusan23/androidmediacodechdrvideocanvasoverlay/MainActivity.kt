package io.github.takusan23.androidmediacodechdrvideocanvasoverlay

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.contentValuesOf
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec.MediaMuxerTool
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec.VideoDecoder
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.mediacodec.VideoEncoder
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.opengl.OpenGlRenderer
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.opengl.TextureRendererSurfaceTexture
import io.github.takusan23.androidmediacodechdrvideocanvasoverlay.ui.theme.AndroidMediaCodecHdrVideoCanvasOverlayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidMediaCodecHdrVideoCanvasOverlayTheme {
                HomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoUri = remember { mutableStateOf<Uri?>(null) }
    val overlayText = remember { mutableStateOf("10 ビット HDR 動画の編集テスト") }
    val isSdrToneMapping = remember { mutableStateOf(false) }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { videoUri.value = it }
    )

    fun start() {
        scope.launch(Dispatchers.Default) {
            val inputUri = videoUri.value ?: return@launch
            val tempVideoTrackFile = context.getExternalFilesDir(null)?.resolve("temp_video.mp4") ?: return@launch

            // 動画のメタデータを取得
            val videoWidth: Int
            val videoHeight: Int
            val fps: Int
            val colorStandard: Int
            val colorTransfer: Int
            MediaMetadataRetriever().use { mediaMetadataRetriever ->
                context.contentResolver.openFileDescriptor(inputUri, "r")?.use {
                    mediaMetadataRetriever.setDataSource(it.fileDescriptor)
                }
                // FPS / 色空間 / ガンマカーブ
                fps = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: 30
                colorStandard = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull() ?: MediaFormat.COLOR_STANDARD_BT2020
                colorTransfer = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() ?: MediaFormat.COLOR_TRANSFER_HLG
                // 動画の縦横サイズは ROTATION を見る必要あり
                val __videoWidth = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                val __videoHeight = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                val rotation = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                // 縦動画の場合、縦横が入れ替わるので
                val (_videoWidth, _videoHeight) = when (rotation) {
                    90, 270 -> __videoHeight to __videoWidth
                    else -> __videoWidth to __videoHeight
                }
                videoWidth = _videoWidth
                videoHeight = _videoHeight
            }

            // 動画のエンコーダー
            val videoEncoder = VideoEncoder().apply {
                prepare(
                    videoFilePath = tempVideoTrackFile.path,
                    outputVideoWidth = videoWidth,
                    outputVideoHeight = videoHeight,
                    frameRate = fps,
                    bitRate = 20_000_000,
                    keyframeInterval = 1,
                    codecName = MediaFormat.MIMETYPE_VIDEO_HEVC,
                    tenBitHdrParametersOrNullSdr = if (isSdrToneMapping.value) {
                        // SDR
                        null
                    } else
                        // HDR なので色空間とガンマカーブを明示的に
                        VideoEncoder.TenBitHdrParameters(
                            colorStandard = colorStandard,
                            colorTransfer = colorTransfer,
                            codecProfile = when (colorTransfer) {
                                MediaFormat.COLOR_TRANSFER_HLG -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                MediaFormat.COLOR_TRANSFER_ST2084 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                else -> -1 // ココには来ないはず
                            }
                        )
                )
            }

            // OpenGL で描画するクラス
            val openGlRenderer = OpenGlRenderer(
                outputSurface = videoEncoder.getInputSurface(),
                width = videoWidth,
                height = videoHeight,
                isEnableTenBitHdr = if (isSdrToneMapping.value) {
                    // SDR なので
                    false
                } else {
                    // BT.2020 でかつ、HLG か ST2084
                    (colorStandard == MediaFormat.COLOR_STANDARD_BT2020 && (colorTransfer == MediaFormat.COLOR_TRANSFER_HLG || colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084))
                }
            ).apply { prepare() }

            // 映像を流す SurfaceTexture を作る
            val surfaceTexture = TextureRendererSurfaceTexture(openGlRenderer.generateTextureId())

            // 動画のデコーダーを作る
            val videoDecoder = VideoDecoder().apply {
                prepare(
                    context = context,
                    uri = inputUri,
                    outputSurface = surfaceTexture.surface,
                    toToneMapSdr = isSdrToneMapping.value
                )
            }

            try {
                coroutineScope {
                    // エンコーダー
                    val encoderJob = launch { videoEncoder.start() }

                    // OpenGL のメインループ
                    val openGlJob = launch {
                        val continuesData = OpenGlRenderer.DrawContinuesData(true, 0)
                        val frameMs = 1_000 / fps
                        val textPaint = Paint().apply {
                            color = Color.WHITE
                            textSize = 100f
                            isAntiAlias = true
                        }
                        var currentPositionMs = 0L

                        // isAvailableNext が false までループする
                        openGlRenderer.drawLoop {
                            // フレームを取得。次のフレームがない場合は null
                            val hasNextFrame = videoDecoder.seekNextFrame(currentPositionMs) != null
                            currentPositionMs += frameMs

                            // 描画する
                            drawSurfaceTexture(surfaceTexture)
                            drawCanvas { drawText(overlayText.value, 100f, 200f, textPaint) }

                            // ループ続行情報
                            continuesData.isAvailableNext = hasNextFrame
                            continuesData.currentTimeNanoSeconds = currentPositionMs * 1_000_000L // nanoseconds に変換する
                            continuesData
                        }
                    }

                    // 終わったらエンコーダーも終わり
                    openGlJob.join()
                    encoderJob.cancelAndJoin()
                }
            } finally {
                surfaceTexture.destroy()
                openGlRenderer.destroy()
                videoDecoder.destroy()
            }

            // 音声トラックがないので（音がないので）、元の動画データから入れ直す
            val resultFile = context.getExternalFilesDir(null)?.resolve("result_${System.currentTimeMillis()}.mp4")!!
            MediaMuxerTool.mixAvTrack(
                context = context,
                audioTrackUri = inputUri,
                videoTrackFilePath = tempVideoTrackFile.path,
                resultFilePath = resultFile.path
            )

            // 端末の動画フォルダに入れる
            val contentResolver = context.contentResolver
            val contentValues = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to resultFile.name,
                MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/AndroidMediaCodecHdrVideoCanvasOverlay"
            )
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
            resultFile.inputStream().use { inputStream ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // コピーしたので消す
            tempVideoTrackFile.delete()
            resultFile.delete()

            // おわり
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "おわり", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.app_name)) }) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            Button(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Text(text = "動画を選ぶ")
            }

            OutlinedTextField(
                value = overlayText.value,
                onValueChange = { overlayText.value = it },
                label = { Text(text = "映像の上に重ねる文字") }
            )

            Row(modifier = Modifier.toggleable(value = isSdrToneMapping.value, onValueChange = { isSdrToneMapping.value = it })) {
                Text(text = "SDR に変換する（トーンマッピングする）")
                Switch(checked = isSdrToneMapping.value, onCheckedChange = null)
            }

            if (videoUri.value != null) {
                Button(onClick = { start() }) {
                    Text(text = "動画編集を始める")
                }
            }
        }
    }
}