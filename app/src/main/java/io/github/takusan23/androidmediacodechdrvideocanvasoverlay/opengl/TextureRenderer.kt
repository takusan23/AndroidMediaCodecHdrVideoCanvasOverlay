package io.github.takusan23.androidmediacodechdrvideocanvasoverlay.opengl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [OpenGlRenderer]から実際の描画処理を持ってきたもの。OpenGL ES のセットアップは[InputSurface]でやる。
 * カメラ映像や動画デコーダーの映像を描画したり、Canvas で書いたものを OpenGL ES へ転写したりする。
 *
 * @param width 映像の幅
 * @param height 映像の高さ
 * @param isEnableTenBitHdr 10-bit HDR を利用する場合は true
 */
class TextureRenderer(
    private val width: Int,
    private val height: Int,
    private val isEnableTenBitHdr: Boolean
) {

    private val mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private var mProgram = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    // Uniform 変数のハンドル
    private var sSurfaceTextureHandle = 0
    private var sCanvasTextureHandle = 0
    private var iDrawModeHandle = 0

    // テクスチャ ID
    private var surfaceTextureTextureId = 0
    private var canvasTextureTextureId = 0

    // Canvas 描画のため Bitmap
    private val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)

    init {
        mTriangleVertices.put(mTriangleVerticesData).position(0)
    }

    /**
     * Canvas に書く。
     * GL スレッドから呼び出すこと。
     */
    fun drawCanvas(draw: Canvas.() -> Unit) {
        // 前回のを消す
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        // 書く
        draw(canvas)

        // 多分いる
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureTextureId)

        // テクスチャを転送
        // texImage2D、引数違いがいるので注意
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, canvasBitmap, 0)
        checkGlError("GLUtils.texImage2D")

        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // テクスチャの ID をわたす
        GLES20.glUniform1i(sSurfaceTextureHandle, 0) // GLES20.GL_TEXTURE0
        GLES20.glUniform1i(sCanvasTextureHandle, 1) // GLES20.GL_TEXTURE1
        // モード切替
        GLES20.glUniform1i(iDrawModeHandle, FRAGMENT_SHADER_DRAW_MODE_CANVAS_BITMAP)
        checkGlError("glUniform1i sSurfaceTextureHandle sCanvasTextureHandle iDrawModeHandle")

        // そのほかの値を渡す
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        // 行列を戻す
        Matrix.setIdentityM(mSTMatrix, 0)
        Matrix.setIdentityM(mMVPMatrix, 0)

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /**
     * SurfaceTexture を描画する。
     * GL スレッドから呼び出すこと。
     *
     * @param surfaceTexture 描画する[SurfaceTexture]
     * @param onTransform 位置や回転を適用するための行列を作るための関数
     */
    fun drawSurfaceTexture(
        surfaceTexture: TextureRendererSurfaceTexture,
        onTransform: ((mvpMatrix: FloatArray) -> Unit)? = null
    ) {
        // attachGlContext の前に呼ぶ必要あり。多分
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureTextureId)

        // 映像を OpenGL ES で使う準備
        surfaceTexture.detachGl()
        surfaceTexture.attachGl(surfaceTextureTextureId)
        // 映像が来ていればテクスチャ更新
        surfaceTexture.checkAndUpdateTexImage()
        surfaceTexture.getTransformMatrix(mSTMatrix)

        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        // テクスチャの ID をわたす
        GLES20.glUniform1i(sSurfaceTextureHandle, 0) // GLES20.GL_TEXTURE0
        GLES20.glUniform1i(sCanvasTextureHandle, 1) // GLES20.GL_TEXTURE1
        // モード切替
        GLES20.glUniform1i(iDrawModeHandle, FRAGMENT_SHADER_DRAW_MODE_SURFACE_TEXTURE)
        checkGlError("glUniform1i sSurfaceTextureHandle sCanvasTextureHandle iDrawModeHandle")

        // そのほかの値を渡す
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        // 行列を適用したい場合
        Matrix.setIdentityM(mMVPMatrix, 0)
        if (onTransform != null) {
            onTransform(mMVPMatrix)
        }

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /**
     * バーテックスシェーダ、フラグメントシェーダーをコンパイルする。
     * GL スレッドから呼び出すこと。
     */
    fun prepareShader() {
        mProgram = createProgram(
            vertexSource = VERTEX_SHADER,
            // TODO HLG だろうと samplerExternalOES から HDR のフレームが取れてそう
            fragmentSource = if (isEnableTenBitHdr) FRAGMENT_SHADER_10BIT_HDR else FRAGMENT_SHADER
        )
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        sSurfaceTextureHandle = GLES20.glGetUniformLocation(mProgram, "sSurfaceTexture")
        checkGlError("glGetUniformLocation sSurfaceTexture")
        if (sSurfaceTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sSurfaceTexture")
        }
        sCanvasTextureHandle = GLES20.glGetUniformLocation(mProgram, "sCanvasTexture")
        checkGlError("glGetUniformLocation sCanvasTexture")
        if (sCanvasTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for sCanvasTexture")
        }
        iDrawModeHandle = GLES20.glGetUniformLocation(mProgram, "iDrawMode")
        checkGlError("glGetUniformLocation iDrawMode")
        if (iDrawModeHandle == -1) {
            throw RuntimeException("Could not get attrib location for iDrawMode")
        }

        // テクスチャ ID を払い出してもらう
        // SurfaceTexture / Canvas Bitmap 用
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        surfaceTextureTextureId = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureTextureId)
        checkGlError("glBindTexture cameraTextureId")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        canvasTextureTextureId = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureTextureId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")

        // アルファブレンディング
        // Canvas で書いた際に、透明な部分は透明になるように
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError("glEnable GLES20.GL_BLEND")
    }

    /** テクスチャ ID を払い出す。[SurfaceTexture]を作成するのに必要なので。 */
    fun generateTextureId(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        return textures.first()
    }

    /**
     * 描画前に呼び出す。
     * GL スレッドから呼び出すこと。
     */
    fun prepareDraw() {
        // クリア？多分必要
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        // drawCanvas / drawSurfaceTexture どっちも呼び出さない場合 glUseProgram 誰もしないので
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    /**
     * GLSL（フラグメントシェーダー・バーテックスシェーダー）をコンパイルして、OpenGL ES とリンクする
     *
     * @throws RuntimeException 構文エラーの場合に投げる
     * @throws RuntimeException それ以外
     * @return 0 以外で成功
     */
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    /**
     * GLSL（フラグメントシェーダー・バーテックスシェーダー）のコンパイルをする
     *
     * @throws RuntimeException 構文エラーの場合に投げる
     * @throws RuntimeException それ以外
     * @return 0 以外で成功
     */
    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            // 失敗したら例外を投げる。その際に構文エラーのメッセージを取得する
            val syntaxErrorMessage = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException(syntaxErrorMessage)
            // ここで return 0 しても例外を投げるので意味がない
            // shader = 0
        }
        return shader
    }

    companion object {
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        private val mTriangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )

        private const val VERTEX_SHADER = """#version 300 es
in vec4 aPosition;
in vec4 aTextureCoord;

uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;

out vec2 vTextureCoord;

void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

        // iDrawMode に渡す定数
        private const val FRAGMENT_SHADER_DRAW_MODE_SURFACE_TEXTURE = 1
        private const val FRAGMENT_SHADER_DRAW_MODE_CANVAS_BITMAP = 2

        /** 10-bit HDR の時に使うフラグメントシェーダー */
        private const val FRAGMENT_SHADER_10BIT_HDR = """#version 300 es
#extension GL_EXT_YUV_target : require
precision mediump float;

in vec2 vTextureCoord;
uniform sampler2D sCanvasTexture;
uniform __samplerExternal2DY2YEXT sSurfaceTexture;

// 何を描画するか
// 1 SurfaceTexture（カメラや動画のデコード映像）
// 2 Bitmap（テキストや画像を描画した Canvas）
uniform int iDrawMode;

// 出力色
out vec4 FragColor;

// https://github.com/android/camera-samples/blob/a07d5f1667b1c022dac2538d1f553df20016d89c/Camera2Video/app/src/main/java/com/example/android/camera2/video/HardwarePipeline.kt#L107
vec3 yuvToRgb(vec3 yuv) {
  const vec3 yuvOffset = vec3(0.0625, 0.5, 0.5);
  const mat3 yuvToRgbColorTransform = mat3(
    1.1689f, 1.1689f, 1.1689f,
    0.0000f, -0.1881f, 2.1502f,
    1.6853f, -0.6530f, 0.0000f
  );
  return clamp(yuvToRgbColorTransform * (yuv - yuvOffset), 0.0, 1.0);
}

void main() {   
  vec4 outColor = vec4(0.0, 0.0, 0.0, 1.0);

  if (iDrawMode == 1) {
    outColor.rgb = yuvToRgb(texture(sSurfaceTexture, vTextureCoord).rgb);
  } else if (iDrawMode == 2) {
    // テクスチャ座標なので Y を反転
    outColor = texture(sCanvasTexture, vec2(vTextureCoord.x, 1.0 - vTextureCoord.y));
  }

  FragColor = outColor;
}
"""

        /** SDR のときに使うフラグメントシェーダー */
        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

in vec2 vTextureCoord;
uniform sampler2D sCanvasTexture;
uniform samplerExternalOES sSurfaceTexture;

// 何を描画するか
// 1 SurfaceTexture（カメラや動画のデコード映像）
// 2 Bitmap（テキストや画像を描画した Canvas）
uniform int iDrawMode;

// 出力色
out vec4 FragColor;

void main() {   
  vec4 outColor = vec4(0.0, 0.0, 0.0, 1.0);

  if (iDrawMode == 1) {
    outColor = texture(sSurfaceTexture, vTextureCoord);
  } else if (iDrawMode == 2) {
    // テクスチャ座標なので Y を反転
    outColor = texture(sCanvasTexture, vec2(vTextureCoord.x, 1.0 - vTextureCoord.y));
  }

  FragColor = outColor;
}
"""
    }
}