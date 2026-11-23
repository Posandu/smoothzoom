package com.posandu.smoothzoom

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
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
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 *   1. snapshot the current frame on the GPU (GlRenderer.freeze),
 *   2. close the old lens and open the new one with a crop that matches the field of view,
 *   3. keep scaling the snapshot with the zoom while the new lens starts (zoom never pauses),
 *   4. crossfade the snapshot away once auto-exposure has settled.
 *
 * Every frame goes through GlRenderer, which also feeds the video encoder, so recordings
 * show the same seamless transition.
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
        val photoPreview: Size,
        val videoPreview: Size,
        val jpegSize: Size,
        val afPicture: Boolean,
        val afVideo: Boolean,
        val fps30: Range<Int>?,
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
        private const val ENC_W = 1920
        private const val ENC_H = 1080
        private const val FADE_MS = 260L
    }

    private val manager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val ui = Handler(Looper.getMainLooper())

    // Views
    private lateinit var textureView: TextureView
    private lateinit var flash: View
    private lateinit var zoomLabel: TextView
    private lateinit var infoLabel: TextView
    private lateinit var recLabel: TextView
    private lateinit var calibRow: LinearLayout
    private lateinit var shutter: View
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private var uwChip: TextView? = null

    // Lenses
    private var mainLens: Lens? = null
    private var uwLens: Lens? = null
    private var nativeMode = false
    private var minZoom = 1f
    private var maxZoom = 8f

    // UI-thread state
    private var zoom = 1f
    private var zoomAnimator: ValueAnimator? = null

    // Recording (UI thread)
    private var recorder: MediaRecorder? = null
    private var recSurface: Surface? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recStart = 0L

    // Shared
    @Volatile private var latestZoom = 1f
    @Volatile private var videoMode = false
    @Volatile private var renderer: GlRenderer? = null
    @Volatile private var camTexture: SurfaceTexture? = null
    private val zoomPosted = AtomicBoolean(false)

    // Camera-thread state
    private var camThread: HandlerThread? = null
    @Volatile private var cam: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionIsVideo = false
    private var previewSurface: Surface? = null
    private val jpegReaders = HashMap<String, ImageReader>()
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
        updateModeUi()
    }

    override fun onResume() {
        super.onResume()
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.CAMERA in missing) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            maybeStartCamera()
        }
    }

    override fun onPause() {
        stopRecording()
        stopCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) maybeStartCamera()
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
        val texSizes = map.getOutputSizes(SurfaceTexture::class.java)
        val prev = pickSize(texSizes, 1920, 4, 3) ?: return null
        val vid = pickSize(texSizes, 1920, 16, 9) ?: prev
        // Cap the JPEG size: the full 50 MP mode makes session setup (and so every lens switch) much slower.
        val jpeg = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4096, 4, 3) ?: return null
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val maxD = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zr = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { it.lower == 30 && it.upper == 30 }

        return Lens(
            id, equiv, active, maxD, orient, prev, vid, jpeg,
            afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
            fps, zr,
        )
    }

    private fun pickSize(sizes: Array<Size>?, maxW: Int, rw: Int, rh: Int): Size? {
        if (sizes.isNullOrEmpty()) return null
        val ok = sizes.filter { it.width <= maxW }.ifEmpty { sizes.toList() }
        val match = ok.filter { it.width * rh == it.height * rw }
        return match.ifEmpty { ok }.maxByOrNull { it.width.toLong() * it.height }
    }

    /** Portrait height / width of the preview for this lens in the current mode. */
    private fun aspectFor(lens: Lens): Float {
        val s = if (videoMode) lens.videoPreview else lens.photoPreview
        return s.width.toFloat() / s.height
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
        val top = dpi(72f)
        // Tall enough for 16:9 video; photo mode draws a 4:3 picture at the top of it.
        val h = min(w * 16 / 9, resources.displayMetrics.heightPixels - top)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val previewBox = FrameLayout(this)
        root.addView(previewBox, FrameLayout.LayoutParams(w, h, Gravity.TOP).apply { topMargin = top })

        textureView = TextureView(this)
        previewBox.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        previewBox.addView(flash, FrameLayout.LayoutParams(-1, -1))

        // Top: lens info (tap to show FOV calibration), recording timer
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        topBar.addView(infoLabel)
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
        topBar.addView(calibRow)
        recLabel = TextView(this).apply {
            setTextColor(0xFFFF5252.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        topBar.addView(recLabel)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dpi(24f) })

        // Bottom: zoom readout, chips, mode switch, shutter
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

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun modeTab(label: String, video: Boolean) = TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
            setOnClickListener { setMode(video) }
        }.also { modeRow.addView(it) }
        photoTab = modeTab("PHOTO", false)
        videoTab = modeTab("VIDEO", true)
        bottom.addView(modeRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12f) })

        shutter = View(this).apply { setOnClickListener { onShutter() } }
        bottom.addView(shutter, LinearLayout.LayoutParams(dpi(72f), dpi(72f)).apply { topMargin = dpi(8f) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dpi(40f) })

        // Pinch to zoom anywhere that isn't a button
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
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
                maybeStartCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                renderer?.setDisplaySize(width, height)
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopRecording()
                stopCamera()
                return true
            }
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

    private fun updateModeUi() {
        val recording = recorder != null
        photoTab.setTextColor(if (!videoMode) 0xFFFFD54F.toInt() else Color.WHITE)
        videoTab.setTextColor(if (videoMode) 0xFFFFD54F.toInt() else Color.WHITE)
        photoTab.alpha = if (recording) 0.3f else 1f
        shutter.background = when {
            recording -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f)
                setColor(0xFFE53935.toInt())
                setStroke(dpi(4f), Color.WHITE)
            }
            videoMode -> circle(0xFFE53935.toInt(), dpi(4f), Color.WHITE)
            else -> circle(Color.WHITE, dpi(4f), 0xFF888888.toInt())
        }
        recLabel.visibility = if (recording) View.VISIBLE else View.GONE
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
        renderer?.zoom = zoom
        latestZoom = zoom
        val h = cam ?: return
        if (zoomPosted.compareAndSet(false, true)) {
            if (!h.post { zoomPosted.set(false); camApplyZoom() }) zoomPosted.set(false)
        }
    }

    /**
     * Animate to a zoom stop. The animation runs straight through the lens boundary: the
     * frozen frame keeps following the zoom while the other lens starts, so nothing pauses.
     */
    private fun animateZoomTo(target: Float) {
        val t = target.coerceIn(minZoom, maxZoom)
        zoomAnimator?.cancel()
        val lf = ln(zoom)
        val lt = ln(t)
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { setZoom(exp(lf + (lt - lf) * (it.animatedValue as Float))) }
            start()
        }
    }

    private fun setMode(video: Boolean) {
        if (recorder != null || video == videoMode) return
        videoMode = video
        updateModeUi()
        cam?.post { camReopen() }
    }

    private fun onShutter() {
        if (!videoMode) {
            takePhoto()
        } else if (recorder == null) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    // ---------------------------------------------------------------- camera (camera thread)

    private fun maybeStartCamera() {
        if (cam != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!textureView.isAvailable) return
        val display = textureView.surfaceTexture ?: return
        val m = mainLens ?: return

        val startLens = if (!nativeMode && uwLens != null && zoom < SWITCH_UP) uwLens!! else m
        val r = GlRenderer()
        val st = r.start(display, textureView.width, textureView.height, aspectFor(startLens))
        if (st == null) {
            r.release()
            infoLabel.text = "OpenGL setup failed."
            return
        }
        r.zoom = zoom
        renderer = r
        camTexture = st

        val t = HandlerThread("camera").also { it.start() }
        camThread = t
        val h = Handler(t.looper)
        cam = h
        latestZoom = zoom
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
            jpegReaders.values.forEach { try { it.close() } catch (_: Exception) {} }
            jpegReaders.clear()
            switching = false
            currentLens = null
        }
        t?.quitSafely()
        try { t?.join(1500) } catch (_: InterruptedException) {}
        zoomPosted.set(false)
        renderer?.release()
        renderer = null
        camTexture = null
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
        val want = chooseLens(latestZoom, lens)
        if (want !== lens) {
            // A switch already in flight finishes first; the settle callback calls us again.
            if (!switching) startSwitch(want)
            return
        }
        // Keep the crop in sync even while the new lens is settling, so the crossfade matches.
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
        renderer?.freeze(aspectFor(want))
        closeCameraInternal()
        openLens(want)
    }

    /** Restart the current lens, e.g. for a photo/video mode change (different stream size). */
    private fun camReopen() {
        val lens = currentLens ?: return
        val want = chooseLens(latestZoom, lens)
        switching = true
        renderer?.freeze(aspectFor(want))
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
        // Closing the device tears down its session too; skipping session.close() saves a round trip.
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
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
                    if (gen != generation) return
                    switching = false
                    toast("Camera ${lens.id} error $error")
                    renderer?.fadeOut(FADE_MS)
                }
            }, cam)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera ${lens.id} failed", e)
            switching = false
            toast("Can't open camera ${lens.id}: ${e.message}")
            renderer?.fadeOut(FADE_MS)
        }
    }

    private fun jpegReaderFor(lens: Lens): ImageReader = jpegReaders.getOrPut(lens.id) {
        ImageReader.newInstance(lens.jpegSize.width, lens.jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                img.close()
                saveJpeg(bytes)
            }, cam)
        }
    }

    private fun createSession(d: CameraDevice, lens: Lens, gen: Int) {
        val st = camTexture ?: return
        val video = videoMode
        val size = if (video) lens.videoPreview else lens.photoPreview
        st.setDefaultBufferSize(size.width, size.height)
        val surf = Surface(st)
        previewSurface = surf

        // Video frames reach the encoder through GlRenderer, so video sessions only need the preview.
        val outputs = mutableListOf(surf)
        if (!video) outputs.add(jpegReaderFor(lens).surface)

        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s
                    sessionIsVideo = video
                    val b = d.createCaptureRequest(
                        if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    )
                    b.addTarget(surf)
                    when {
                        video && lens.afVideo ->
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        lens.afPicture ->
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    if (video) lens.fps30?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
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
                    if (gen != generation) return
                    switching = false
                    toast("Couldn't configure camera ${lens.id}")
                    renderer?.fadeOut(FADE_MS)
                }
            }, cam)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            switching = false
            renderer?.fadeOut(FADE_MS)
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (!switching || s !== session) return
            framesAfterSwitch++
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            val settled = ae == null ||
                ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            // A couple of frames with settled exposure (or a short cap) so the crossfade doesn't
            // reveal a dark/bright flash. The fade itself hides any remaining small AE drift.
            if ((framesAfterSwitch >= 2 && settled) || framesAfterSwitch >= 8) {
                switching = false
                renderer?.fadeOut(FADE_MS)
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
        if (sessionIsVideo) return
        val r = jpegReaders[lens.id] ?: return
        if (switching) { toast("Switching lenses, try again"); return }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            if (lens.afPicture) {
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

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveJpeg(bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SmoothZoom_${stamp()}.jpg")
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

    // ---------------------------------------------------------------- video (UI thread)

    private val recTick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            recLabel.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private fun startRecording() {
        val r = renderer ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SmoothZoom_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SmoothZoom")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Can't create video file"); return }

        var pfd: ParcelFileDescriptor? = null
        var surface: Surface? = null
        var rec: MediaRecorder? = null
        try {
            pfd = contentResolver.openFileDescriptor(uri, "w") ?: error("no file descriptor")
            surface = MediaCodec.createPersistentInputSurface()
            val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.apply {
                if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(ENC_W, ENC_H)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(16_000_000)
                if (audio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(48_000)
                    setAudioEncodingBitRate(192_000)
                    setAudioChannels(2)
                }
                // Frames are rendered landscape; players rotate them back to portrait.
                setOrientationHint(90)
                setInputSurface(surface)
                prepare()
                start()
            }
            r.setEncoder(surface, ENC_W, ENC_H)
        } catch (e: Exception) {
            Log.e(TAG, "start recording failed", e)
            try { rec?.release() } catch (_: Exception) {}
            surface?.release()
            try { pfd?.close() } catch (_: Exception) {}
            contentResolver.delete(uri, null, null)
            toast("Can't record: ${e.message}")
            return
        }
        recorder = rec
        recSurface = surface
        recPfd = pfd
        recUri = uri
        recStart = SystemClock.elapsedRealtime()
        ui.post(recTick)
        updateModeUi()
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        ui.removeCallbacks(recTick)
        renderer?.setEncoder(null, 0, 0)
        val ok = try { rec.stop(); true } catch (e: RuntimeException) { false }
        rec.release()
        recSurface?.release()
        recSurface = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        val uri = recUri
        recUri = null
        if (uri != null) {
            if (ok) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                toast("Video saved to DCIM/SmoothZoom")
            } else {
                contentResolver.delete(uri, null, null)
                toast("Recording too short")
            }
        }
        updateModeUi()
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
