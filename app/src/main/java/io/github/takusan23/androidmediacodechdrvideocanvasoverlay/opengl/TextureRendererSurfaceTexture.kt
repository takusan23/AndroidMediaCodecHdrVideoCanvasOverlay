package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.opengl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * [SurfaceTexture]をラップしたもの、ちょっと使いにくいので
 *
 * @param initTexName [OpenGlRenderer.generateTextureId]
 */
class TextureRendererSurfaceTexture(private val initTexName: Int) {

    private val surfaceTexture = SurfaceTexture(initTexName)
    private val _isAvailableFrameFlow = MutableStateFlow(false)

    /** [SurfaceTexture.detachFromGLContext]したら false */
    private var isAttach = true

    /** [SurfaceTexture]へ映像を渡す[Surface] */
    val surface = Surface(surfaceTexture) // Surface に SurfaceTexture を渡すというよくわからない API 設計

    init {
        surfaceTexture.setOnFrameAvailableListener {
            // StateFlow はスレッドセーフが約束されているので
            _isAvailableFrameFlow.value = true
        }
    }

    /**
     * [SurfaceTexture.setDefaultBufferSize] を呼び出す
     * Camera2 API の解像度、SurfaceTexture の場合はここで決定する
     */
    fun setTextureSize(width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width, height)
    }

    /**
     * GL コンテキストを切り替え、テクスチャ ID の変更を行う。GL スレッドから呼び出すこと。
     * [OpenGlRenderer]を作り直しする場合など。
     *
     * @param texName テクスチャ
     */
    fun attachGl(texName: Int) {
        // 余計に呼び出さないようにする
        if (!isAttach) {
            surfaceTexture.attachToGLContext(texName)
            isAttach = true
        }
    }

    /**
     * GL コンテキストから切り離す。GL スレッドから呼び出すこと。
     * [OpenGlRenderer]を作り直しする場合など。
     */
    fun detachGl() {
        if (isAttach) {
            surfaceTexture.detachFromGLContext()
            isAttach = false
        }
    }

    /** 新しいフレームが来るまで待って、[SurfaceTexture.updateTexImage]を呼び出す */
    suspend fun awaitUpdateTexImage() { // todo 使ってない
        // フラグが来たら折る
        _isAvailableFrameFlow.first { it /* == true */ }
        _isAvailableFrameFlow.value = false
        surfaceTexture.updateTexImage()
    }

    /** テクスチャが更新されていれば、[SurfaceTexture.updateTexImage]を呼び出す */
    fun checkAndUpdateTexImage() {
        val isAvailable = _isAvailableFrameFlow.value
        if (isAvailable) {
            _isAvailableFrameFlow.value = false
            surfaceTexture.updateTexImage()
        }
    }

    /** [SurfaceTexture.getTransformMatrix]を呼ぶ */
    fun getTransformMatrix(mtx: FloatArray) {
        surfaceTexture.getTransformMatrix(mtx)
    }

    /**
     * 破棄する
     * GL スレッドから呼び出すこと（テクスチャを破棄したい）
     * TODO テクスチャを明示的に破棄すべきか
     */
    fun destroy() {
        val textures = intArrayOf(initTexName)
        GLES20.glDeleteTextures(1, textures, 0)
        surface.release()
        surfaceTexture.release()
    }
}