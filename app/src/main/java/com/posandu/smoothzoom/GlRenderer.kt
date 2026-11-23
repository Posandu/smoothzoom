package com.posandu.smoothzoom

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Draws the camera preview with OpenGL so a lens switch can be hidden both on screen and
 * in the recorded video.
 *
 * Layers, bottom to top:
 *   live   - the current camera stream (zoom crop is done by the camera itself)
 *   frozen - a snapshot of what was on screen right before a switch. It follows the zoom
 *            (it can shrink, with a blurred copy behind it, so zooming out never stalls) and
 *            is crossfaded away once the new lens is ready.
 *
 * Each frame is drawn to the display and, while recording, to the encoder surface, so
 * the video gets the same transition instead of a gap.
 */
class GlRenderer {

    companion object {
        private const val TAG = "SmoothZoomGL"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val ROT_NONE = floatArrayOf(1f, 0f, 0f, 1f)
        // Encoder frames are stored landscape with a 90° rotation hint: upright (x, y) -> (-y, x).
        private val ROT_ENCODER = floatArrayOf(0f, 1f, -1f, 0f)

        private const val VS = """
            attribute vec2 aPos;
            uniform mat4 uTexM;
            uniform mat2 uRot;
            uniform float uScale;
            varying vec2 vUv;
            void main() {
                vUv = (uTexM * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
                gl_Position = vec4(uRot * (aPos * uScale), 0.0, 1.0);
            }
        """
        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            uniform float uAlpha;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, uAlpha); }
        """
        private const val FS_2D = """
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            uniform float uBias;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv, uBias).rgb, uAlpha); }
        """
    }

    private class Prog(val id: Int) {
        val aPos = GLES30.glGetAttribLocation(id, "aPos")
        val uTexM = GLES30.glGetUniformLocation(id, "uTexM")
        val uRot = GLES30.glGetUniformLocation(id, "uRot")
        val uScale = GLES30.glGetUniformLocation(id, "uScale")
        val uAlpha = GLES30.glGetUniformLocation(id, "uAlpha")
        val uBias = GLES30.glGetUniformLocation(id, "uBias")
        val uTex = GLES30.glGetUniformLocation(id, "uTex")
    }

    private val thread = HandlerThread("gl").also { it.start() }
    private val gl = Handler(thread.looper)

    // EGL
    private var dpy = EGL14.EGL_NO_DISPLAY
    private var ctx = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var displaySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0
    private var lastEncNs = 0L
    private var surfW = 1
    private var surfH = 1

    // Programs / geometry
    private lateinit var progOes: Prog
    private lateinit var prog2d: Prog
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    // Live camera layer
    private var oesTex = 0
    private var camTexture: SurfaceTexture? = null
    private val camMatrix = FloatArray(16)
    private var frameAvailable = false
    private var hasLive = false

    // Frozen layer (ping-pong so a snapshot can include the previous, still-fading one)
    private val snapTex = IntArray(2)
    private val snapFbo = IntArray(2)
    private val snapW = IntArray(2)
    private val snapH = IntArray(2)
    private var snapIdx = 0
    private val snapRect = IntArray(4)
    private var snapZoom = 1f
    private var frozen = false
    private var frozenAlpha = 1f
    private var freezeNs = 0L
    private var fadeStartNs = 0L
    private var fadeMs = 0L

    /** Portrait height / width of the live picture. */
    private var aspect = 4f / 3f

    /** Current zoom, written from the UI thread. The frozen layer scales by zoom / snapZoom. */
    @Volatile var zoom = 1f

    private var drawPosted = false
    private var vsyncPending = false
    private val drawRunnable = Runnable { drawPosted = false; draw() }
    private val vsync = Choreographer.FrameCallback { vsyncPending = false; draw() }

    // ---------------------------------------------------------------- public API

    /** Sets up EGL on the display texture and returns the SurfaceTexture the camera should write to. */
    fun start(display: SurfaceTexture, w: Int, h: Int, aspect: Float): SurfaceTexture? = sync(2000) {
        try {
            initEgl(display)
            surfW = max1(w); surfH = max1(h)
            this.aspect = aspect
            progOes = Prog(buildProgram(VS, FS_OES))
            prog2d = Prog(buildProgram(VS, FS_2D))
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(
                GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE
            )

            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            oesTex = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener({ frameAvailable = true; requestDraw() }, gl)
            camTexture = st
            st
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            null
        }
    }

    fun setDisplaySize(w: Int, h: Int) {
        gl.post { surfW = max1(w); surfH = max1(h); requestDraw() }
    }

    /**
     * Snapshot what is on screen right now and hold it on top until [fadeOut].
     * Blocks briefly (one GL pass) so the caller can close the camera right after.
     */
    fun freeze(newAspect: Float) {
        sync(300) {
            val live = liveRect()
            if (hasLive || frozen) {
                makeCurrent(displaySurface)
                val next = 1 - snapIdx
                ensureSnap(next, live[2], live[3])
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo[next])
                val shiftedSnap = intArrayOf(
                    snapRect[0] - live[0], snapRect[1] - live[1], snapRect[2], snapRect[3]
                )
                compose(intArrayOf(0, 0, live[2], live[3]), shiftedSnap, ROT_NONE, System.nanoTime())
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapTex[next])
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

                snapIdx = next
                live.copyInto(snapRect)
                snapZoom = zoom
                frozen = true
                frozenAlpha = 1f
                fadeMs = 0
                freezeNs = System.nanoTime()
                // The stale frame from the old stream must not peek out around the snapshot.
                hasLive = false
            }
            aspect = newAspect
            requestDraw()
        }
    }

    /** Crossfade the frozen layer away; the live stream underneath is ready. */
    fun fadeOut(ms: Long) {
        gl.post {
            if (frozen && fadeMs == 0L) {
                fadeStartNs = System.nanoTime()
                fadeMs = ms
                requestDraw()
            }
        }
    }

    /** Start (surface != null) or stop feeding frames to a video encoder. Blocks until applied. */
    fun setEncoder(surface: Surface?, w: Int, h: Int) {
        sync(1000) {
            if (encSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(displaySurface)
                EGL14.eglDestroySurface(dpy, encSurface)
                encSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null) {
                encSurface = EGL14.eglCreateWindowSurface(dpy, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                encW = w
                encH = h
                lastEncNs = 0
            }
        }
    }

    fun release() {
        sync(1000) {
            if (dpy == EGL14.EGL_NO_DISPLAY) return@sync
            try {
                makeCurrent(displaySurface)
                camTexture?.release()
                camTexture = null
                GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
                GLES30.glDeleteTextures(2, snapTex, 0)
                GLES30.glDeleteFramebuffers(2, snapFbo, 0)
                if (::progOes.isInitialized) GLES30.glDeleteProgram(progOes.id)
                if (::prog2d.isInitialized) GLES30.glDeleteProgram(prog2d.id)
            } catch (_: Exception) {}
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (encSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, encSurface)
            if (displaySurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, displaySurface)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(dpy)
            encSurface = EGL14.EGL_NO_SURFACE
            displaySurface = EGL14.EGL_NO_SURFACE
            ctx = EGL14.EGL_NO_CONTEXT
            dpy = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- rendering (GL thread)

    private fun requestDraw() {
        if (!drawPosted) {
            drawPosted = true
            gl.post(drawRunnable)
        }
    }

    private fun draw() {
        if (displaySurface == EGL14.EGL_NO_SURFACE) return
        makeCurrent(displaySurface)
        if (frameAvailable) {
            frameAvailable = false
            try {
                camTexture?.updateTexImage()
                camTexture?.getTransformMatrix(camMatrix)
                hasLive = true
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage failed", e)
            }
        }
        val now = System.nanoTime()
        if (frozen && fadeMs > 0) {
            val t = ((now - fadeStartNs) / 1e6f / fadeMs).coerceIn(0f, 1f)
            frozenAlpha = 1f - t * t * (3f - 2f * t)
            if (t >= 1f) { frozen = false; fadeMs = 0 }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        compose(liveRect(), snapRect, ROT_NONE, now)
        EGL14.eglSwapBuffers(dpy, displaySurface)

        // ~30 fps to the encoder: every camera frame, plus vsync ticks while a switch is hidden.
        if (encSurface != EGL14.EGL_NO_SURFACE && now - lastEncNs >= 25_000_000L) {
            lastEncNs = now
            makeCurrent(encSurface)
            val full = intArrayOf(0, 0, encW, encH)
            compose(full, full, ROT_ENCODER, now)
            EGLExt.eglPresentationTimeANDROID(dpy, encSurface, now)
            EGL14.eglSwapBuffers(dpy, encSurface)
        }

        // While the frozen layer is up the camera may deliver nothing, so drive frames from vsync.
        if (frozen && !vsyncPending) {
            vsyncPending = true
            Choreographer.getInstance().postFrameCallback(vsync)
        }
    }

    private fun compose(liveVp: IntArray, snapVp: IntArray, rot: FloatArray, now: Long) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (hasLive) {
            viewport(liveVp)
            drawLayer(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, camMatrix, 1f, 1f, 0f, rot)
        }
        if (frozen && frozenAlpha > 0f) {
            viewport(snapVp)
            val s = (zoom / snapZoom).coerceIn(0.2f, 10f)
            val tex = snapTex[snapIdx]
            // Zooming out past the snapshot: fill the edges with a heavily blurred copy.
            if (s < 0.999f) drawLayer(prog2d, GLES30.GL_TEXTURE_2D, tex, IDENTITY, 1f, frozenAlpha, 6f, rot)
            val blur = ((now - freezeNs) / 150e6f).coerceIn(0f, 1f) * 1.2f
            drawLayer(prog2d, GLES30.GL_TEXTURE_2D, tex, IDENTITY, s, frozenAlpha, blur, rot)
        }
    }

    private fun drawLayer(
        p: Prog, target: Int, tex: Int, texM: FloatArray,
        scale: Float, alpha: Float, bias: Float, rot: FloatArray,
    ) {
        GLES30.glUseProgram(p.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(p.uTex, 0)
        GLES30.glUniformMatrix4fv(p.uTexM, 1, false, texM, 0)
        GLES30.glUniformMatrix2fv(p.uRot, 1, false, rot, 0)
        GLES30.glUniform1f(p.uScale, scale)
        GLES30.glUniform1f(p.uAlpha, alpha)
        if (p.uBias >= 0) GLES30.glUniform1f(p.uBias, bias)
        GLES30.glEnableVertexAttribArray(p.aPos)
        GLES30.glVertexAttribPointer(p.aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Live picture rect in GL coordinates: full width, top-aligned. */
    private fun liveRect(): IntArray {
        val vh = (surfW * aspect).roundToInt()
        return intArrayOf(0, surfH - vh, surfW, vh)
    }

    private fun viewport(r: IntArray) = GLES30.glViewport(r[0], r[1], r[2], r[3])

    private fun ensureSnap(i: Int, w: Int, h: Int) {
        if (snapTex[i] == 0) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0); snapTex[i] = t[0]
            GLES30.glGenFramebuffers(1, t, 0); snapFbo[i] = t[0]
        }
        if (snapW[i] == w && snapH[i] == h) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapTex[i])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        texParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo[i])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, snapTex[i], 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        snapW[i] = w
        snapH[i] = h
    }

    private fun texParams(target: Int, minFilter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    // ---------------------------------------------------------------- EGL / GL helpers

    private fun initEgl(display: SurfaceTexture) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(dpy, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        config = configs[0]
        ctx = EGL14.eglCreateContext(
            dpy, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        displaySurface = EGL14.eglCreateWindowSurface(dpy, config, display, intArrayOf(EGL14.EGL_NONE), 0)
        check(displaySurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        makeCurrent(displaySurface)
    }

    private fun makeCurrent(s: EGLSurface) {
        EGL14.eglMakeCurrent(dpy, s, s, ctx)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src.trimIndent())
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    private fun <T> sync(timeoutMs: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        val posted = gl.post {
            try { result = block() } finally { latch.countDown() }
        }
        if (!posted) return null
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return result
    }
}
