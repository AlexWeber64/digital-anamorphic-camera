package com.alexweber.anamorphicstretchcam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SHADER = """
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    uniform mat4 uMVPMatrix;
    uniform mat4 uTexMatrix;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTextureCoord = (uTexMatrix * aTextureCoord).xy;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, vTextureCoord);
    }
"""

private val FULL_QUAD_COORDS = floatArrayOf(
    -1f, -1f, 0f,
    1f, -1f, 0f,
    -1f, 1f, 0f,
    1f, 1f, 0f
)

private val FULL_QUAD_TEX_COORDS = floatArrayOf(
    0f, 0f,
    1f, 0f,
    0f, 1f,
    1f, 1f
)

/**
 * Draws a camera external-OES texture as a full-screen quad. The horizontal
 * anamorphic stretch/squeeze is baked in by the caller-supplied MVP matrix
 * (see StretchGLRenderer), not by resizing the GL viewport - the texture is
 * actually resampled across a horizontally scaled quad.
 */
class StretchShaderProgram {

    private val program: Int
    private val aPositionLoc: Int
    private val aTextureCoordLoc: Int
    private val uMVPMatrixLoc: Int
    private val uTexMatrixLoc: Int
    val textureId: Int

    private val vertexBuffer: FloatBuffer = directFloatBuffer(FULL_QUAD_COORDS)
    private val texCoordBuffer: FloatBuffer = directFloatBuffer(FULL_QUAD_TEX_COORDS)

    init {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        textureId = createExternalTexture()
    }

    fun draw(mvpMatrix: FloatArray, texMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    companion object {
        private fun directFloatBuffer(data: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
        }

        private fun createExternalTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            return texId
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile failed: $log")
            }
            return shader
        }

        private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Program link failed: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }
    }
}
