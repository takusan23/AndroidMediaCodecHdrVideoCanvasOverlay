package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.opengl

import android.view.Surface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * OpenGL ES を利用して[TextureRendererSurfaceTexture]や Canvas の中身を描画するクラス。
 * カメラ映像に Canvas で書いた文字を重ねたり出来ます。
 *
 * @param outputSurface 描画した内容の出力先 Surface。SurfaceView、MediaRecorder、MediaCodec など
 * @param width 幅
 * @param height 高さ
 * @param isEnableTenBitHdr 10-bit HDR を有効にする場合は true
 */
class OpenGlRenderer(
    outputSurface: Surface,
    width: Int,
    height: Int,
    isEnableTenBitHdr: Boolean
) {

    /** OpenGL 描画用スレッドの Kotlin Coroutine Dispatcher */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val openGlRelatedThreadDispatcher = newSingleThreadContext("openGlRelatedThreadDispatcher")

    private val inputSurface = InputSurface(outputSurface, isEnableTenBitHdr)
    private val textureRenderer = TextureRenderer(width, height, isEnableTenBitHdr)

    /** OpenGL ES の用意をし、フラグメントシェーダー等をコンパイルする */
    suspend fun prepare() {
        withContext(openGlRelatedThreadDispatcher) {
            inputSurface.makeCurrent()
            textureRenderer.prepareShader()
        }
    }

    /** テクスチャ ID を払い出す。SurfaceTexture 作成に使うので */
    suspend fun generateTextureId(): Int {
        return withContext(openGlRelatedThreadDispatcher) {
            textureRenderer.generateTextureId()
        }
    }

    /** 描画する */
    suspend fun drawLoop(drawTexture: suspend TextureRenderer.() -> DrawContinuesData) {
        withContext(openGlRelatedThreadDispatcher) {
            while (true) {
                yield()

                // 描画する
                textureRenderer.prepareDraw()
                val continuesData = drawTexture(textureRenderer)

                // presentationTime が多分必要。swapBuffers して Surface に流す
                inputSurface.setPresentationTime(continuesData.currentTimeNanoSeconds)
                inputSurface.swapBuffers()

                // 続行するか
                if (!continuesData.isAvailableNext) break
            }
        }
    }

    /** 破棄する */
    suspend fun destroy() {
        // try-finally で呼び出されるため NonCancellable 必須
        withContext(openGlRelatedThreadDispatcher + NonCancellable) {
            inputSurface.destroy()
        }
        openGlRelatedThreadDispatcher.close()
    }

    /**
     * 描画を続行するかのデータ
     *
     * @param isAvailableNext 次も描画できる場合は true
     * @param currentTimeNanoSeconds プレビューのときは使われてない（？）、MediaRecorder の場合は[System.nanoTime]、MediaCodec の場合は今のフレームの時間を入れてください。
     */
    data class DrawContinuesData(
        var isAvailableNext: Boolean,
        var currentTimeNanoSeconds: Long = System.nanoTime()
    )
}