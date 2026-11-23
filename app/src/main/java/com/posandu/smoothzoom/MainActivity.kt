package com.posandu.smoothzoom

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Smooth 0.5x <-> 1x zoom for phones that expose the ultrawide and main cameras
 * as separate Camera2 devices (no native logical-camera switching).
 *
 * Only one camera can stream at a time, so at the switch point we:
 *   1. freeze the last preview frame in an overlay,
 *   2. close the old lens and open the new one with a crop that matches the field of view,
 *   3. keep scaling the frozen frame with the user's pinch while the new lens starts,
 *   4. wait until auto-exposure settles, then crossfade the overlay away.
 *
 * If the phone does expose a logical camera with a zoom range below 1x, the app
 * uses CONTROL_ZOOM_RATIO instead and lets the camera system switch natively.
 */
class MainActivity : Activity() {

    private class Lens(
        val id: String,
        val equivFocal: Float,
        val activeArray: Rect,
        val maxDigital: Float,
        val sensorOrientation: Int,
        val previewSize: Size,
        val jpegSize: Size,
        val afContinuous: Boolean,
        val nativeZoomRange: Range<Float>?,
    ) {
        /** Zoom level (relative to the main camera) at which this lens shows its full frame. */
        @Volatile var base = 1f
    }

    companion object {
        private const val TAG = "SmoothZoom"
        private const val SWITCH_DOWN = 0.97f   // main -> ultrawide below this
        private const val SWITCH_UP = 1.0f      // ultrawide -> main at/above this
        private const val PREFS = "smoothzoom"
        private const val KEY_UW_BASE = "uw_base"
    }

    private val manager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val ui = Handler(Looper.getMainLooper())

    // Views
    private lateinit var textureView: TextureView
    private lateinit var overlay: ImageView
    private lateinit var flash: View
    private lateinit var zoomLabel: TextView
    private lateinit var infoLabel: TextView
    private lateinit var calibRow: LinearLayout
    private var uwChip: TextView? = null

    // Lenses
    private var mainLens: Lens? = null
    private var uwLens: Lens? = null
    private var nativeMode = false
    private var minZoom = 1f
    private var maxZoom = 8f

    // UI-thread state
    private var zoom = 1f
    private var snapZoom = 1f
    private var zoomAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var blurAnimator: ValueAnimator? = null
    private var afterSwitch: (() -> Unit)? = null

    // Shared
    @Volatile private var latestZoom = 1f
    private val zoomPosted = AtomicBoolean(false)
    @Volatile private var texture: SurfaceTexture? = null

    // Camera-thread state
    private var camThread: HandlerThread? = null
    @Volatile private var cam: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var currentLens: Lens? = null
    private var switching = false
    private var framesAfterSwitch = 0
    private var generation = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        discoverLenses()
        buildUi()
        setZoom(1f)
        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            maybeStartCamera()
        }
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) maybeStartCamera()
        else infoLabel.text = "Camera permission is needed."
    }

    // ---------------------------------------------------------------- lens discovery

    private fun discoverLenses() {
        val ids = LinkedHashSet<String>()
        try { ids.addAll(manager.cameraIdList) } catch (_: Exception) {}
        // Samsung sometimes hides physical lenses from cameraIdList but still lets apps open them.
        for (i in 0..15) ids.add(i.toString())

        val found = ids.mapNotNull { id -> try { readLens(id) } catch (_: Exception) { null } }
        if (found.isEmpty()) return

        val m = found.firstOrNull { it.id == "0" } ?: found.minBy { abs(it.equivFocal - 26f) }
        mainLens = m
        m.base = 1f

        val nr = m.nativeZoomRange
        if (nr != null && nr.lower < 0.9f) {
            nativeMode = true
            minZoom = nr.lower
            maxZoom = min(nr.upper, 10f)
            return
        }

        val uw = found
            .filter { it !== m && it.equivFocal < m.equivFocal * 0.8f }
            .minByOrNull { it.equivFocal }
        if (uw != null) {
            val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_UW_BASE, -1f)
            uw.base = if (saved in 0.3f..0.95f) saved else uw.equivFocal / m.equivFocal
            uwLens = uw
            minZoom = uw.base
        }
        maxZoom = m.maxDigital.coerceIn(1f, 10f)
    }

    private fun readLens(id: String): Lens? {
        val c = manager.getCameraCharacteristics(id)
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return null

        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(active.width(), active.height())
        val aw = phys.width * active.width() / pix.width.toFloat()
        val ah = phys.height * active.height() / pix.height.toFloat()
        val equiv = focal * 43.27f / hypot(aw, ah)

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 1920) ?: return null
        val jpeg = pickSize(map.getOutputSizes(ImageFormat.JPEG), Int.MAX_VALUE) ?: return null
        val af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        val maxD = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zr = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return Lens(id, equiv, active, maxD, orient, prev, jpeg, af, zr)
    }

    private fun pickSize(sizes: Array<Size>?, maxW: Int): Size? {
        if (sizes.isNullOrEmpty()) return null
        val ok = sizes.filter { it.width <= maxW }
        val fourThree = ok.filter { it.width * 3 == it.height * 4 }
        return fourThree.ifEmpty { ok }.maxByOrNull { it.width.toLong() * it.height }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    private fun fmt(v: Float): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun circle(fill: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val w = resources.displayMetrics.widthPixels
        val h = w * 4 / 3

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val previewBox = FrameLayout(this)
        root.addView(previewBox, FrameLayout.LayoutParams(w, h, Gravity.TOP).apply { topMargin = dpi(72f) })

        textureView = TextureView(this)
        previewBox.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        overlay = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.INVISIBLE
        }
        previewBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        previewBox.addView(flash, FrameLayout.LayoutParams(-1, -1))

        // Top: lens info (tap to show FOV calibration)
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoLabel = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(4f))
            setOnClickListener {
                if (uwLens != null) calibRow.visibility =
                    if (calibRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        top.addView(infoLabel)
        calibRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        fun calibButton(label: String, delta: Float) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
            setOnClickListener { nudgeUwBase(delta) }
        }
        calibRow.addView(calibButton("UW FOV  −", -0.01f))
        calibRow.addView(calibButton("+", +0.01f))
        top.addView(calibRow)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dpi(24f) })

        // Bottom: zoom readout, chips, shutter
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        zoomLabel = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFD54F.toInt()) }
        bottom.addView(zoomLabel)

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun chip(label: String, target: () -> Float) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            background = circle(0x88333333.toInt())
            setOnClickListener { animateZoomTo(target()) }
        }.also {
            chipRow.addView(it, LinearLayout.LayoutParams(dpi(46f), dpi(46f)).apply {
                setMargins(dpi(8f), 0, dpi(8f), 0)
            })
        }
        if (uwLens != null || nativeMode) uwChip = chip(fmt(minZoom)) { minZoom }
        chip("1") { 1f }
        chip("2") { 2f }
        bottom.addView(chipRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10f) })

        val shutter = View(this).apply {
            background = circle(Color.WHITE, dpi(4f), 0xFF888888.toInt())
            setOnClickListener { takePhoto() }
        }
        bottom.addView(shutter, LinearLayout.LayoutParams(dpi(72f), dpi(72f)).apply { topMargin = dpi(20f) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dpi(48f) })

        // Pinch to zoom anywhere that isn't a button
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                afterSwitch = null
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor)
                return true
            }
        })
        root.setOnTouchListener { _, e -> scaler.onTouchEvent(e); true }

        setContentView(root)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                texture = st
                maybeStartCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { texture = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun updateInfo() {
        val m = mainLens
        infoLabel.text = when {
            m == null -> "No back camera found."
            nativeMode -> "Native logical camera (${fmt(minZoom)}–${fmt(maxZoom)}x) · id ${m.id}"
            uwLens == null -> "Main: id ${m.id} · ${m.equivFocal.toInt()}mm\n" +
                "Ultrawide is not exposed to third-party apps on this phone."
            else -> {
                val u = uwLens!!
                "Main: id ${m.id} · ${m.equivFocal.toInt()}mm   UW: id ${u.id} · ${u.equivFocal.toInt()}mm\n" +
                    "Crossfade mode · UW = ${String.format(Locale.US, "%.2f", u.base)}x  (tap to calibrate)"
            }
        }
    }

    private fun nudgeUwBase(delta: Float) {
        val u = uwLens ?: return
        u.base = (u.base + delta).coerceIn(0.3f, 0.95f)
        minZoom = u.base
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_UW_BASE, u.base).apply()
        uwChip?.text = fmt(minZoom)
        setZoom(zoom)
        updateInfo()
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(minZoom, maxZoom)
        zoomLabel.text = String.format(Locale.US, "%.2fx", zoom)
        if (overlay.visibility == View.VISIBLE) {
            // Keep the frozen frame following the pinch. Never shrink it below full size,
            // which would show black borders.
            val s = max(zoom / snapZoom, 1f)
            overlay.scaleX = s
            overlay.scaleY = s
        }
        latestZoom = zoom
        val h = cam ?: return
        if (zoomPosted.compareAndSet(false, true)) {
            if (!h.post { zoomPosted.set(false); camApplyZoom() }) zoomPosted.set(false)
        }
    }

    private fun runZoomAnim(target: Float, ms: Long, onEnd: (() -> Unit)?) {
        zoomAnimator?.cancel()
        val from = zoom
        val lf = ln(from)
        val lt = ln(target)
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { setZoom(exp(lf + (lt - lf) * (it.animatedValue as Float))) }
            if (onEnd != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    /** Animate to a zoom stop. If it crosses the lens boundary, pause at the boundary until the new lens is live. */
    private fun animateZoomTo(target: Float) {
        val t = target.coerceIn(minZoom, maxZoom)
        afterSwitch = null
        val canSwitch = uwLens != null && !nativeMode
        val down = canSwitch && zoom >= SWITCH_DOWN && t < SWITCH_DOWN
        val up = canSwitch && zoom < SWITCH_UP && t >= SWITCH_UP
        if (!down && !up) {
            runZoomAnim(t, 450, null)
            return
        }
        val edge = if (down) SWITCH_DOWN - 0.005f else SWITCH_UP
        runZoomAnim(edge, 160) {
            val cont = { runZoomAnim(t, 380, null) }
            afterSwitch = cont
            // Safety net in case the switch never reports back.
            ui.postDelayed({ if (afterSwitch === cont) { afterSwitch = null; cont() } }, 1500)
        }
    }

    // ---------------------------------------------------------------- overlay / crossfade (UI thread)

    private fun showSnapshot() {
        fadeAnimator?.cancel()
        blurAnimator?.cancel()
        val bmp = textureView.bitmap ?: return
        overlay.setImageBitmap(bmp)
        snapZoom = zoom
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 31) {
            val maxR = dp(6f)
            blurAnimator = ValueAnimator.ofFloat(0.01f, maxR).apply {
                duration = 150
                addUpdateListener {
                    val r = it.animatedValue as Float
                    overlay.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                }
                start()
            }
        }
    }

    private fun onLensLive() {
        afterSwitch?.let { afterSwitch = null; it() }
        if (overlay.visibility != View.VISIBLE) return
        blurAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(overlay.alpha, 0f).apply {
            duration = 220
            addUpdateListener { overlay.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    overlay.visibility = View.INVISIBLE
                    overlay.setImageDrawable(null)
                    if (Build.VERSION.SDK_INT >= 31) overlay.setRenderEffect(null)
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- camera (camera thread)

    private fun maybeStartCamera() {
        if (cam != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!textureView.isAvailable) return
        val m = mainLens ?: return
        texture = textureView.surfaceTexture

        val t = HandlerThread("camera").also { it.start() }
        camThread = t
        val h = Handler(t.looper)
        cam = h
        latestZoom = zoom
        val startLens = if (!nativeMode && uwLens != null && zoom < SWITCH_UP) uwLens!! else m
        h.post {
            switching = true
            openLens(startLens)
        }
    }

    private fun stopCamera() {
        val h = cam ?: return
        val t = camThread
        cam = null
        camThread = null
        h.post {
            generation++
            closeCameraInternal()
            switching = false
            currentLens = null
        }
        t?.quitSafely()
        try { t?.join(1500) } catch (_: InterruptedException) {}
        zoomPosted.set(false)
    }

    private fun chooseLens(z: Float, current: Lens): Lens {
        val m = mainLens!!
        val u = uwLens ?: return m
        if (nativeMode) return m
        return if (current === u) {
            if (z >= SWITCH_UP) m else u
        } else {
            if (z < SWITCH_DOWN) u else m
        }
    }

    private fun camApplyZoom() {
        val lens = currentLens ?: return
        if (switching) return
        val want = chooseLens(latestZoom, lens)
        if (want !== lens) {
            startSwitch(want)
            return
        }
        val b = previewBuilder ?: return
        val s = session ?: return
        applyZoom(b, lens, latestZoom)
        try {
            s.setRepeatingRequest(b.build(), previewCallback, cam)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
        }
    }

    private fun startSwitch(want: Lens) {
        switching = true
        // Freeze the current frame on screen before the old camera goes away.
        val latch = CountDownLatch(1)
        ui.post { showSnapshot(); latch.countDown() }
        try { latch.await(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        closeCameraInternal()
        openLens(want)
    }

    private fun applyZoom(b: CaptureRequest.Builder, lens: Lens, z: Float) {
        if (nativeMode && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
            return
        }
        val f = (z / lens.base).coerceIn(1f, max(1f, lens.maxDigital))
        val a = lens.activeArray
        val cw = (a.width() / f).toInt()
        val ch = (a.height() / f).toInt()
        val l = (a.width() - cw) / 2
        val t = (a.height() - ch) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
    }

    private fun closeCameraInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        previewSurface?.release()
        previewSurface = null
        previewBuilder = null
    }

    @SuppressLint("MissingPermission")
    private fun openLens(lens: Lens) {
        val gen = ++generation
        currentLens = lens
        try {
            manager.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }
                    device = d
                    createSession(d, lens, gen)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) device = null
                    switching = false
                    toast("Camera ${lens.id} error $error")
                    ui.post { onLensLive() }
                }
            }, cam)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera ${lens.id} failed", e)
            switching = false
            toast("Can't open camera ${lens.id}: ${e.message}")
            ui.post { onLensLive() }
        }
    }

    private fun createSession(d: CameraDevice, lens: Lens, gen: Int) {
        val st = texture ?: return
        st.setDefaultBufferSize(lens.previewSize.width, lens.previewSize.height)
        val surf = Surface(st)
        previewSurface = surf

        val reader = ImageReader.newInstance(lens.jpegSize.width, lens.jpegSize.height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            img.close()
            saveJpeg(bytes)
        }, cam)
        jpegReader = reader

        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surf, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(surf)
                    if (lens.afContinuous) {
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    previewBuilder = b
                    framesAfterSwitch = 0
                    applyZoom(b, lens, latestZoom)
                    try {
                        s.setRepeatingRequest(b.build(), previewCallback, cam)
                    } catch (e: Exception) {
                        Log.e(TAG, "start preview failed", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    switching = false
                    toast("Couldn't configure camera ${lens.id}")
                    ui.post { onLensLive() }
                }
            }, cam)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            switching = false
            ui.post { onLensLive() }
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (!switching) return
            framesAfterSwitch++
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            val settled = ae == null ||
                ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            // Wait for a few frames and settled exposure so the crossfade doesn't reveal a dark/bright flash.
            if ((framesAfterSwitch >= 3 && settled) || framesAfterSwitch >= 12) {
                switching = false
                ui.post { onLensLive() }
                camApplyZoom() // zoom may have moved on (or back) while we were switching
            }
        }
    }

    // ---------------------------------------------------------------- photo

    private fun takePhoto() {
        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(200).start()
        cam?.post { camCapture() }
    }

    private fun camCapture() {
        val d = device ?: return
        val s = session ?: return
        val lens = currentLens ?: return
        val r = jpegReader ?: return
        if (switching) { toast("Switching lenses, try again"); return }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            if (lens.afContinuous) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            applyZoom(b, lens, latestZoom)
            s.capture(b.build(), null, cam)
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            toast("Capture failed: ${e.message}")
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        try {
            val name = "SmoothZoom_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SmoothZoom")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("Saved to DCIM/SmoothZoom")
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            toast("Save failed: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
