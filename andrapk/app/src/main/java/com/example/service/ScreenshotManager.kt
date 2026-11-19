package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.quiz.QuizOverlayManager
import com.example.ui.JarvisOverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ScreenshotManager {
    private const val TAG = "ScreenshotManager"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var width = 720
    private var height = 1280
    private var densityDpi = 320
    private var createdRotation: Int = -1

    @Volatile
    private var lastCaptureInfoStr: String = "belum ada tangkapan"

    /** Info tangkapan terakhir: dimensi + orientasi + sumber (untuk tool & debugging). */
    fun lastCaptureInfo(): String = lastCaptureInfoStr

    private fun captureInfo(w: Int, h: Int, source: String): String {
        val orientasi = when {
            w > h -> "landscape"
            h > w -> "portrait"
            else -> "persegi"
        }
        lastCaptureInfoStr = w.toString() + "x" + h + " (" + orientasi + ") via " + source
        return lastCaptureInfoStr
    }

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive = _isMediaProjectionActive.asStateFlow()

    /** Timestamp terakhir sesi capture dimatikan (0 = tidak pernah/tidak tahu). */
    private val _projectionDiedAt = MutableStateFlow(0L)
    val projectionDiedAt = _projectionDiedAt.asStateFlow()

    data class ScreenMetrics(
        val widthPixels: Int,
        val heightPixels: Int,
        val densityDpi: Int,
        val density: Float
    )

    fun getScreenMetrics(context: Context): ScreenMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // maximumWindowMetrics: dimensi penuh display di rotasi AKTIF (anti salah skala)
            val windowMetrics = windowManager.maximumWindowMetrics
            val bounds = windowMetrics.bounds
            val densityDpi = context.resources.configuration.densityDpi
            val density = context.resources.displayMetrics.density
            ScreenMetrics(bounds.width(), bounds.height(), densityDpi, density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, metrics.density)
        }
    }

    /** Rotasi display saat ini: 0/1/2/3 (Surface.ROTATION_*). */
    fun currentRotation(context: Context): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Proyeksi layar aktif (untuk ScreenRecordManager & fitur lain yang butuh konsent yang sama). */
    internal fun activeProjection(): MediaProjection? = mediaProjection

    fun setMediaProjection(context: Context, projection: MediaProjection) {
        release()
        mediaProjection = projection

        // Register callback BEFORE createVirtualDisplay (MANDATORY on Android 14+ / API 34+)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection session stopped by system")
                _projectionDiedAt.value = System.currentTimeMillis()
                release()
            }
        }
        projectionCallback = callback
        try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register MediaProjection callback: ${e.message}")
        }

        // Use EXACT native hardware display metrics for virtualDisplay to prevent SurfaceFlinger top-cropping
        val metrics = getScreenMetrics(context)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "JarvisScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            createdRotation = currentRotation(context)
            _isMediaProjectionActive.value = true
            Log.i(TAG, "MediaProjection initialized 100% full screen: ${width}x${height} @ ${densityDpi}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection virtual display: ${e.message}", e)
            release()
        }
    }

    /**
     * Memastikan VirtualDisplay mengikuti orientasi layar SAAT INI.
     * Tanpa ini, screenshot setelah layar berputar (mis. remote desktop landscape)
     * menghasilkan citra salah orientasi / salah skala — OCR dan koordinat tap jadi miss.
     * Aman dipanggil kapan pun: hanya rebuild bila rotasi/dimensi benar-benar berubah,
     * dan TIDAK butuh consent ulang (memakai MediaProjection yang sama).
     */
    private fun ensureFreshVirtualDisplay(context: Context) {
        val projection = mediaProjection ?: return
        val rotation = currentRotation(context)
        val metrics = getScreenMetrics(context)
        if (rotation == createdRotation && metrics.widthPixels == width && metrics.heightPixels == height) {
            return
        }
        Log.i(
            TAG,
            "Display berubah (rot $createdRotation->$rotation, ${width}x${height} -> ${metrics.widthPixels}x${metrics.heightPixels}) — rebuild VirtualDisplay"
        )
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi
        createdRotation = rotation
        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "JarvisScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            // Beri waktu SurfaceFlinger merender frame pertama ke surface baru.
            Thread.sleep(150)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal rebuild VirtualDisplay: ${e.message}", e)
            // Paksa rebuild ulang pada capture berikutnya — tanpa ini state terlihat
            // "sudah segar" padahal VirtualDisplay null, dan early-return membuat
            // jalur projection mati selamanya.
            createdRotation = -1
            width = -1
            height = -1
        }
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtualDisplay", e)
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing imageReader", e)
        }
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering callback", e)
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaProjection", e)
        }
        projectionCallback = null
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        _isMediaProjectionActive.value = false
    }

    /**
     * Cek gambar "kosong": seragam DAN gelap. Frame gagal tangkap selalu HITAM;
     * halaman terang polos adalah konten sah (jangan dibuang!).
     * Dipakai memilih sumber tangkapan terbaik (projection vs accessibility).
     */
    private fun isUniformBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        val sx = (w / 64).coerceAtLeast(1)
        val sy = (h / 64).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = b.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                n++
                x += sx
            }
            y += sy
        }
        if (n == 0) return true
        val mean = sum / n
        var dev = 0
        var m = 0
        y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = b.getPixel(x, y)
                val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                if (kotlin.math.abs(l - mean) > 24.0) dev++
                m++
                x += sx
            }
            y += sy
        }
        if (dev.toDouble() / m >= 0.02) return false // ada konten yang jelas
        return mean < 40.0 // seragam + gelap = frame gagal tangkap (halaman putih = konten sah)
    }

    /**
     * Captures current screen and returns Base64 encoded JPEG.
     *
     * Chain cerdas:
     *   1. MediaProjection — hasil DIPERIKSA dulu; kalau kosong/hitam (frame pertama,
     *      FLAG_SECURE, virtual display bermasalah) JANGAN langsung dipakai.
     *   2. AccessibilityService.takeScreenshot (API 30+) — jalur tangkap TERPISAH yang
     *      sering berhasil saat projection bermasalah; dipakai bila hasilnya tidak kosong.
     *   3. Dua-duanya kosong → kembalikan gambar kosong (tanpa error) agar pemanggil
     *      (QuizAnalyzer) bisa capture-ulang & menampilkan pesan penyebab yang tepat.
     *
     * @param trace callback opsional (default null) untuk diagnostic sumber tangkapan.
     */
    suspend fun captureBase64(
        context: Context,
        trace: ((String) -> Unit)? = null
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        // FIX rotasi (Bug A): pastikan VirtualDisplay mengikuti orientasi layar SAAT INI.
        // Tanpa ini, layar landscape (mis. 1600x720 saat game/remote dibuka) tetap
        // ditangkap ke buffer portrait 720x1600 -> citra ter-letterbox, OCR gagal.
        ensureFreshVirtualDisplay(context)
        var blankFallbackBase64: String? = null
        val reader = imageReader
        if (reader != null) {
            var image: Image? = null
            try {
                image = acquireLatestImageWithRetry(reader)
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        if (isUniformBlank(bitmap)) {
                            trace?.invoke("MediaProjection: gambar kosong/hitam")
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                            blankFallbackBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            captureInfo(bitmap.width, bitmap.height, "MediaProjection (kosong)")
                        } else {
                            // GUARD ORIENTASI AKTIF: dimensi frame HARUS cocok dengan layar
                            // SAAT INI (portrait atau landscape). Tidak cocok = frame usang
                            // dari orientasi lama (race rotasi) -> rebuild + capture ulang 1x.
                            val m = getScreenMetrics(context)
                            val cocok = (bitmap.width == m.widthPixels && bitmap.height == m.heightPixels) ||
                                (bitmap.width == m.heightPixels && bitmap.height == m.widthPixels)
                            if (!cocok) {
                                trace?.invoke("Frame usang (" + bitmap.width + "x" + bitmap.height + ", layar " + m.widthPixels + "x" + m.heightPixels + ") - rebuild & ulang")
                                bitmap.recycle()
                                createdRotation = -1
                                width = -1
                                height = -1
                                ensureFreshVirtualDisplay(context)
                                val reader2 = imageReader
                                if (reader2 != null) {
                                    var img2: Image? = null
                                    try {
                                        img2 = acquireLatestImageWithRetry(reader2)
                                        if (img2 != null) {
                                            val bmp2 = imageToBitmap(img2)
                                            if (bmp2 != null && !isUniformBlank(bmp2)) {
                                                val b64 = bitmapToBase64(bmp2)
                                                trace?.invoke("Sumber: MediaProjection (frame segar " + bmp2.width + "x" + bmp2.height + ")")
                                                captureInfo(bmp2.width, bmp2.height, "MediaProjection")
                                                bmp2.recycle()
                                                return@withContext Pair(b64, null)
                                            }
                                            bmp2?.recycle()
                                        }
                                    } finally {
                                        try { img2?.close() } catch (_: Exception) {}
                                    }
                                }
                                // retry gagal -> lanjut ke jalur fallback di bawah
                            } else {
                                val base64 = bitmapToBase64(bitmap)
                                trace?.invoke("Sumber: MediaProjection")
                                captureInfo(bitmap.width, bitmap.height, "MediaProjection")
                                bitmap.recycle()
                                return@withContext Pair(base64, null)
                            }
                        }
                        bitmap.recycle()
                    }
                } else {
                    trace?.invoke("MediaProjection: tidak ada frame")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection capture failed: ${e.message}")
                trace?.invoke("MediaProjection: error")
            } finally {
                // PENTING: image harus SELALU ditutup — kebocoran 1 image mengunci
                // ImageReader (maxImages) sehingga SEMUA capture berikutnya gagal.
                try { image?.close() } catch (_: Exception) {}
            }
        }

        // Attempt 2: AccessibilityService Fallback (jalur tangkap berbeda)
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService != null) {
            val bitmap = suspendCoroutine<Bitmap?> { cont ->
                accessibilityService.takeScreenshotCompat { bmp ->
                    cont.resume(bmp)
                }
            }
            if (bitmap != null) {
                if (isUniformBlank(bitmap)) {
                    trace?.invoke("Accessibility: gambar kosong/hitam (app mungkin FLAG_SECURE)")
                    bitmap.recycle()
                } else {
                    val base64 = bitmapToBase64(bitmap)
                    trace?.invoke("Sumber: Accessibility screenshot")
                    captureInfo(bitmap.width, bitmap.height, "Accessibility screenshot")
                    bitmap.recycle()
                    return@withContext Pair(base64, null)
                }
            } else {
                trace?.invoke("Accessibility: gagal/tidak tersedia")
            }
        }

        // Attempt 3: screencap via Shizuku (level shell) — SELALU ikut rotasi dan
        // satu-satunya jalur yang menangkap konten FLAG_SECURE (game/app proteksi).
        // Hanya dipakai bila Shizuku berjalan & izinnya sudah diberikan.
        val shizukuPng = AdbShizukuManager.screencapIfPermitted()
        val shizukuBitmap = if (shizukuPng != null) BitmapFactory.decodeByteArray(shizukuPng, 0, shizukuPng.size) else null
        if (shizukuBitmap != null) {
            if (isUniformBlank(shizukuBitmap)) {
                trace?.invoke("Shizuku screencap: kosong")
                shizukuBitmap.recycle()
            } else {
                val base64 = bitmapToBase64(shizukuBitmap)
                trace?.invoke("Sumber: Shizuku screencap (tahan FLAG_SECURE)")
                captureInfo(shizukuBitmap.width, shizukuBitmap.height, "Shizuku screencap")
                shizukuBitmap.recycle()
                return@withContext Pair(base64, null)
            }
        } else {
            trace?.invoke("Shizuku: " + AdbShizukuManager.diagnose())
        }

        // Attempt 4: screencap via TERMUX-ADB (pengganti Shizuku bila Shizuku mati).
        // Butuh sekali jalan: termux/setup_adb.sh di Termux (adb connect).
        if (AdbShizukuManager.isTermuxAdbReady()) {
            val png4 = AdbShizukuManager.termuxScreencapPng()
            val bmp4 = if (png4 != null) BitmapFactory.decodeByteArray(png4, 0, png4.size) else null
            if (bmp4 != null) {
                if (isUniformBlank(bmp4)) {
                    trace?.invoke("Termux-ADB: gambar kosong")
                    bmp4.recycle()
                } else {
                    val base64 = bitmapToBase64(bmp4)
                    trace?.invoke("Sumber: Termux-ADB screencap (tahan FLAG_SECURE)")
                    captureInfo(bmp4.width, bmp4.height, "Termux-ADB screencap")
                    bmp4.recycle()
                    return@withContext Pair(base64, null)
                }
            } else {
                trace?.invoke("Termux-ADB: screencap tidak sampai (jalankan 'jad status' di Termux)")
            }
        }

        if (blankFallbackBase64 != null) return@withContext Pair(blankFallbackBase64, null)

        Pair(
            null,
            "Screenshot requires MediaProjection consent or AccessibilityService on Android 11+. Please grant Screen Capture in JARVIS app."
        )
    }

    private fun acquireLatestImageWithRetry(reader: ImageReader): Image? {
        for (i in 0 until 5) {
            val image = reader.acquireLatestImage()
            if (image != null) return image
            Thread.sleep(60)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        val maxDim = 1568
        val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // =========================================================================
    // Autonomous AI Agent Screenshot & Set-of-Mark Visual Annotation
    // =========================================================================

    data class AgentScreenshotResult(
        val base64: String,
        val savedPath: String?,
        val width: Int,
        val height: Int,
        val source: String,
        val annotated: Boolean,
        val elementsSummary: String
    )

    fun getScreenshotsDir(context: Context): File {
        val canUseExternal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageDirectory()?.canWrite() == true
            }
        } catch (_: Exception) {
            false
        }

        if (canUseExternal) {
            val extDir = File(Environment.getExternalStorageDirectory(), "JARVIS/screenshots")
            if (extDir.exists() || extDir.mkdirs()) return extDir
        }
        return File(context.filesDir, "screenshots").apply { if (!exists()) mkdirs() }
    }

    /** Daftar screenshot yang pernah disimpan AI Agent (terbaru di atas). */
    fun listSavedScreenshots(context: Context, limit: Int = 20): List<File> {
        val dir = getScreenshotsDir(context)
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true) || f.name.endsWith(".png", true))
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.take(limit.coerceIn(1, 100))
    }

    /** Membaca file gambar lokal menjadi base64 untuk dianalisis AI. */
    fun readFileAsBase64(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) return null
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val b64 = bitmapToBase64(bmp)
            bmp.recycle()
            b64
        } catch (e: Exception) {
            Log.e(TAG, "readFileAsBase64 error: ${e.message}")
            null
        }
    }

    /**
     * Tangkap layar secara otonom untuk AI Agent:
     * - Opsional delayMs sebelum tangkap (mis. menunggu animasi/loading selesai)
     * - Opsional sembunyikan overlay JARVIS & Quiz sementara agar layar 100% bersih
     * - Opsional annotateGrid=true: gambar grid koordinat + badge angka (#1, #2, ...) pada elemen klik
     * - Opsional simpan otomatis ke /sdcard/JARVIS/screenshots/ (pruning maks 30 file terakhir)
     */
    suspend fun captureForAgent(
        context: Context,
        hideOverlay: Boolean = true,
        annotateGrid: Boolean = false,
        saveToFile: Boolean = true,
        saveAs: String? = null,
        delayMs: Long = 0L
    ): Pair<AgentScreenshotResult?, String?> = withContext(Dispatchers.IO) {
        if (delayMs > 0L) {
            delay(delayMs.coerceIn(0L, 10000L))
        }

        if (hideOverlay) {
            JarvisOverlayManager.setTemporarilyHidden(true)
            QuizOverlayManager.setTemporarilyHidden(true)
            delay(180L)
        }

        val traceNotes = mutableListOf<String>()
        val captured = try {
            captureBase64(context) { note -> traceNotes.add(note) }
        } finally {
            if (hideOverlay) {
                JarvisOverlayManager.setTemporarilyHidden(false)
                QuizOverlayManager.setTemporarilyHidden(false)
            }
        }

        val rawBase64 = captured.first
        val captureError = captured.second
        if (rawBase64 == null) {
            return@withContext Pair(null, captureError ?: "Gagal mengambil screenshot.")
        }

        val rawBytes = Base64.decode(rawBase64, Base64.DEFAULT)
        val decodedBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            ?: return@withContext Pair(null, "Gagal mendecode bitmap screenshot.")

        val metrics = getScreenMetrics(context)
        var finalBase64 = rawBase64
        var elementsSummary = ""
        var finalWidth = decodedBmp.width
        var finalHeight = decodedBmp.height

        try {
            if (annotateGrid) {
                val (annotatedBmp, summary) = renderAnnotatedOverlay(decodedBmp, metrics)
                finalWidth = annotatedBmp.width
                finalHeight = annotatedBmp.height
                finalBase64 = bitmapToBase64(annotatedBmp)
                elementsSummary = summary
                if (annotatedBmp != decodedBmp) annotatedBmp.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Annotation gagal, pakai screenshot polos: ${e.message}")
        } finally {
            decodedBmp.recycle()
        }

        var savedPath: String? = null
        if (saveToFile) {
            try {
                val dir = getScreenshotsDir(context)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val prefix = if (annotateGrid) "shot_grid" else "shot"
                val sanitized = saveAs?.trim()
                    ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    ?.take(48)
                    ?.ifBlank { null }
                val fileName = when {
                    sanitized == null -> "${prefix}_${ts}.jpg"
                    sanitized.endsWith(".jpg", true) || sanitized.endsWith(".jpeg", true) -> sanitized
                    else -> "${sanitized}.jpg"
                }
                val outFile = File(dir, fileName)
                val finalBytes = Base64.decode(finalBase64, Base64.DEFAULT)
                outFile.writeBytes(finalBytes)
                savedPath = outFile.absolutePath

                // Otomatis pangkas file lama jika melebihi 30 screenshot
                val allShots = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }
                    ?.sortedByDescending { it.lastModified() }
                if (allShots != null && allShots.size > 30) {
                    allShots.drop(30).forEach { runCatching { it.delete() } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal menyimpan file screenshot: ${e.message}")
            }
        }

        val sourceLabel = traceNotes.lastOrNull() ?: lastCaptureInfo()
        Pair(
            AgentScreenshotResult(
                base64 = finalBase64,
                savedPath = savedPath,
                width = finalWidth,
                height = finalHeight,
                source = sourceLabel,
                annotated = annotateGrid,
                elementsSummary = elementsSummary
            ),
            null
        )
    }

    /**
     * Menggambar grid koordinat (setiap 20% X/Y dalam pixel layar asli) +
     * nomor Set-of-Mark (#1, #2, ...) pada elemen klik Accessibility agar AI bisa
     * langsung tap(x, y) secara presisi tinggi tanpa tebak-tebakan.
     */
    private fun renderAnnotatedOverlay(
        source: Bitmap,
        metrics: ScreenMetrics
    ): Pair<Bitmap, String> {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val bw = mutable.width.toFloat()
        val bh = mutable.height.toFloat()
        val realW = metrics.widthPixels.coerceAtLeast(1).toFloat()
        val realH = metrics.heightPixels.coerceAtLeast(1).toFloat()
        val scaleX = bw / realW
        val scaleY = bh / realH

        // 1. Grid koordinat tipis setiap 20%
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 91, 156, 246) // JarvisCyan semi-transparan
            strokeWidth = (bw / 450f).coerceIn(1.5f, 3f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 11, 13, 16)
            style = Paint.Style.FILL
        }
        val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(236, 239, 244)
            textSize = (bw / 42f).coerceIn(18f, 30f)
            isFakeBoldText = true
        }

        val steps = listOf(0.2f, 0.4f, 0.6f, 0.8f)
        for (f in steps) {
            val x = bw * f
            val realX = (realW * f).toInt()
            canvas.drawLine(x, 0f, x, bh, gridPaint)
            val txt = "x=$realX"
            val tw = labelTextPaint.measureText(txt)
            canvas.drawRoundRect(RectF(x - tw / 2f - 6f, 6f, x + tw / 2f + 6f, labelTextPaint.textSize + 14f), 6f, 6f, labelBgPaint)
            canvas.drawText(txt, x - tw / 2f, labelTextPaint.textSize + 8f, labelTextPaint)

            val y = bh * f
            val realY = (realH * f).toInt()
            canvas.drawLine(0f, y, bw, y, gridPaint)
            val yTxt = "y=$realY"
            val yTw = labelTextPaint.measureText(yTxt)
            canvas.drawRoundRect(RectF(6f, y - labelTextPaint.textSize / 2f - 4f, yTw + 18f, y + labelTextPaint.textSize / 2f + 6f), 6f, 6f, labelBgPaint)
            canvas.drawText(yTxt, 12f, y + labelTextPaint.textSize / 3f, labelTextPaint)
        }

        // 2. Set-of-Mark badge (#1..#25) untuk elemen interaktif Accessibility
        val summarySb = StringBuilder()
        val acc = JarvisAccessibilityService.instance
        if (acc != null) {
            val elements = runCatching { acc.readScreenElements() }.getOrDefault(emptyList())
                .filter { el ->
                    (el.isClickable || el.isEditable) &&
                        el.bounds.width > 12 && el.bounds.height > 12 &&
                        el.bounds.centerX in 0..metrics.widthPixels &&
                        el.bounds.centerY in 0..metrics.heightPixels
                }
                .take(25)

            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 52, 199, 123) // JarvisEmerald
                strokeWidth = (bw / 360f).coerceIn(2f, 4f)
                style = Paint.Style.STROKE
            }
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(229, 91, 100) // JarvisRed kontras tinggi
                style = Paint.Style.FILL
            }
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (bw / 38f).coerceIn(20f, 32f)
                isFakeBoldText = true
            }

            elements.forEachIndexed { idx, el ->
                val num = idx + 1
                val left = (el.bounds.left * scaleX).coerceIn(0f, bw)
                val top = (el.bounds.top * scaleY).coerceIn(0f, bh)
                val right = (el.bounds.right * scaleX).coerceIn(0f, bw)
                val bottom = (el.bounds.bottom * scaleY).coerceIn(0f, bh)

                canvas.drawRect(left, top, right, bottom, boxPaint)

                val tag = "#$num"
                val tw = badgeTextPaint.measureText(tag)
                val bhTag = badgeTextPaint.textSize + 10f
                val bx = left.coerceIn(2f, (bw - tw - 14f).coerceAtLeast(2f))
                val by = top.coerceIn(2f, (bh - bhTag - 2f).coerceAtLeast(2f))
                canvas.drawRoundRect(RectF(bx, by, bx + tw + 12f, by + bhTag), 8f, 8f, badgeBgPaint)
                canvas.drawText(tag, bx + 6f, by + badgeTextPaint.textSize + 2f, badgeTextPaint)

                val label = el.text.ifBlank { el.contentDescription }.ifBlank { el.viewId.substringAfterLast('/') }.ifBlank { el.className.substringAfterLast('.') }
                val role = if (el.isEditable) "[INPUT]" else "[CLICK]"
                summarySb.append("#$num $role \"${label.take(40)}\" -> tap(${el.bounds.centerX}, ${el.bounds.centerY})\n")
            }
        }

        val summary = if (summarySb.isEmpty()) {
            "Grid koordinat aktif (${metrics.widthPixels}x${metrics.heightPixels}). Tidak ada overlay elemen Accessibility terdeteksi."
        } else {
            "Grid koordinat (${metrics.widthPixels}x${metrics.heightPixels}) + Set-of-Mark elemen interaktif:\n$summarySb"
        }
        return Pair(mutable, summary)
    }
}
