package com.alexweber.anamorphicstretchcam.gl

import android.opengl.EGLSurface
import android.view.Surface

/**
 * A single EGL window surface bound to a real [Surface] (either the on-screen preview
 * SurfaceView's surface, or a MediaCodec encoder's input surface). Rendering to two
 * different destinations each frame is just: makeCurrent(surfaceA) -> draw -> swap,
 * makeCurrent(surfaceB) -> draw -> swap, all against the same shared EglCore context.
 */
class WindowSurface(
    private val eglCore: EglCore,
    private val surface: Surface,
    private val ownsSurface: Boolean
) {
    private var eglSurface: EGLSurface? = eglCore.createWindowSurface(surface)
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun setDimensions(w: Int, h: Int) {
        width = w
        height = h
    }

    fun makeCurrent() {
        eglSurface?.let { eglCore.makeCurrent(it) }
    }

    fun swapBuffers(): Boolean {
        return eglSurface?.let { eglCore.swapBuffers(it) } ?: false
    }

    fun setPresentationTime(nanos: Long) {
        eglSurface?.let { eglCore.setPresentationTime(it, nanos) }
    }

    fun release() {
        eglSurface?.let { eglCore.releaseSurface(it) }
        eglSurface = null
        if (ownsSurface) {
            surface.release()
        }
    }
}
