package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.JarvisApp
import com.example.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all built-in and user/AI-defined tools in JARVIS-HP.
 * Executes all tools on the actual Android device (Shell, Termux, Shizuku, Intent, Accessibility, HTTP, System Logic).
 */
object ToolManager {

    private const val TAG = "ToolManager"
    private const val PREFS_NAME = "jarvis_custom_tools_prefs"
    private const val KEY_TOOLS_JSON = "custom_tools_list"

    private var sharedPreferences: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Built-in tools definition
    val builtInTools: List<CustomTool> = listOf(
        CustomTool(
            id = "open_app",
            name = "Buka Aplikasi",
            description = "Membuka aplikasi target berdasarkan package name atau nama umum (contoh: youtube, chrome, settings, whatsapp)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "open_app",
            parametersSchema = """{"package_name": "com.google.android.youtube"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "type_text",
            name = "Ketik Teks",
            description = "Mengetikkan teks secara otomatis pada input field yang sedang aktif atau target element ID",
            category = "Input & Form",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "type_text",
            parametersSchema = """{"text": "Halo Dunia", "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "tap",
            name = "Tap Layar",
            description = "Melakukan klik/tap pada koordinat spesifik (x, y) atau identifier teks/ID elemen",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "tap",
            parametersSchema = """{"x": 500, "y": 800, "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "swipe",
            name = "Swipe / Scroll",
            description = "Mengusap layar dari koordinat awal (x1, y1) ke koordinat akhir (x2, y2)",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "swipe",
            parametersSchema = """{"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration_ms": 300}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "press_key",
            name = "Tekan Tombol Sistem",
            description = "Menekan tombol navigasi sistem seperti BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "press_key",
            parametersSchema = """{"keycode": "BACK"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "read_screen",
            name = "Baca Layar (UI Hierarchy)",
            description = "Membaca semua elemen UI yang tampil di layar beserta teks, id, dan posisinya",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "read_screen",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screenshot",
            name = "Screenshot Layar (Otonom)",
            description = "Mengambil screenshot layar HP secara otonom (otomatis menyembunyikan overlay JARVIS, opsional delay_ms, simpan ke /sdcard/JARVIS/screenshots, dan langsung dikirim ke visi AI). Mendukung MediaProjection, Accessibility, Shizuku, dan Termux-ADB.",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "screenshot",
            parametersSchema = """{"hide_overlay": true, "annotate_grid": false, "save_to_file": true, "save_as": "opsional_nama", "delay_ms": 0}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screenshot_annotated",
            name = "Screenshot + Grid Koordinat & Tag Elemen",
            description = "Mengambil screenshot layar dengan overlay grid koordinat (x/y) dan badge bernomor (#1, #2, ...) pada elemen klik (Set-of-Mark) agar AI bisa memilih target tap(x,y) dengan akurasi sangat tinggi.",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "screenshot_annotated",
            parametersSchema = """{"hide_overlay": true, "save_as": "opsional_nama", "delay_ms": 0}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screenshot_list",
            name = "Daftar Screenshot Tersimpan",
            description = "Melihat daftar file screenshot yang pernah diambil AI Agent beserta path, ukuran, dan waktunya.",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "screenshot_list",
            parametersSchema = """{"limit": 15}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_exec",
            name = "Terminal AI Persisten",
            description = "Menjalankan perintah shell/bash/python di terminal internal milik AI Agent (ala AI Agent komputer). Mempertahankan direktori kerja (cd) dan variabel environment (export) antar perintah per sesi, dengan workspace di /sdcard/JARVIS/workspace. Set as_adb=true untuk eksekusi via Shizuku/Termux-ADB.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_exec",
            parametersSchema = """{"command": "pwd && ls -la", "session": "main", "timeout_sec": 30, "as_adb": false}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_bg_start",
            name = "Jalankan Proses Latar Belakang",
            description = "Memulai proses jangka panjang di terminal AI (mis. server lokal python/node, script watcher, download besar) tanpa memblokir percakapan. Mengembalikan job_id.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_bg_start",
            parametersSchema = """{"command": "python3 -m http.server 8080", "name": "web_server", "session": "main"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_bg_read",
            name = "Baca Log Proses Latar Belakang",
            description = "Membaca output terbaru (stdout/stderr) dari proses latar belakang berdasarkan job_id.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_bg_read",
            parametersSchema = """{"job_id": "bg_xxxxxx", "tail_lines": 60}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_bg_input",
            name = "Kirim Input Stdin ke Job",
            description = "Mengirim teks input interaktif (stdin) ke proses latar belakang yang sedang berjalan.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_bg_input",
            parametersSchema = """{"job_id": "bg_xxxxxx", "input": "y"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_bg_stop",
            name = "Hentikan Proses Latar Belakang",
            description = "Menghentikan proses latar belakang tertentu berdasarkan job_id, atau isi 'all' untuk menghentikan semuanya.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_bg_stop",
            parametersSchema = """{"job_id": "bg_xxxxxx"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_bg_list",
            name = "Daftar Proses Latar Belakang",
            description = "Menampilkan semua proses background terminal AI yang sedang berjalan maupun sudah selesai.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_bg_list",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "terminal_reset",
            name = "Reset Sesi Terminal AI",
            description = "Mereset direktori kerja (cwd) dan environment sesi terminal kembali ke folder workspace awal.",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "terminal_reset",
            parametersSchema = """{"session": "main"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "workspace_file",
            name = "Editor File Workspace (Read/Write/Edit/Grep/Tree)",
            description = "Alat manajemen file & kode ala coding agent komputer: read (dengan nomor baris & start_line/end_line), write (tulis file), edit (search & replace presisi), grep (cari regex/teks rekursif), tree (pohon folder).",
            category = "Terminal AI Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "workspace_file",
            parametersSchema = """{"action": "read|write|edit|grep|tree", "path": "script.py", "content": "...", "find": "...", "replace": "...", "query": "...", "start_line": 1, "end_line": 200}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "web_fetch",
            name = "Ambil Konten Web / API",
            description = "Mengambil halaman web atau endpoint JSON via HTTP GET dan membersihkan tag HTML menjadi teks bersih yang mudah dibaca AI.",
            category = "Web & Riset",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "web_fetch",
            parametersSchema = """{"url": "https://example.com", "max_chars": 8000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "download_file",
            name = "Download File ke Workspace",
            description = "Mengunduh file dari URL internet langsung ke folder workspace terminal AI atau path tujuan.",
            category = "Web & Riset",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "download_file",
            parametersSchema = """{"url": "https://example.com/data.json", "path": "data.json"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "agent_plan",
            name = "Rencana & Checklist Tugas AI",
            description = "Membuat dan memperbarui daftar langkah kerja (todo checklist) realtime yang tampil di UI Chat untuk tugas multi-langkah.",
            category = "Perencanaan Agent",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "agent_plan",
            parametersSchema = """{"action": "set|update|get|clear", "steps": ["Langkah 1", "Langkah 2"], "id": 1, "status": "in_progress|done|failed", "note": "..."}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "wait_seconds",
            name = "Tunggu (Delay Aman)",
            description = "Menjeda eksekusi selama beberapa detik (0.2 sampai 30 detik) untuk menunggu loading aplikasi, proses terminal, atau animasi selesai.",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "wait_seconds",
            parametersSchema = """{"seconds": 2}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "ocr_screenshot",
            name = "OCR Screenshot (Baca Teks Layar)",
            description = "Satu langkah: tangkap layar lalu langsung membaca SEMUA teksnya (OCR on-device). Cara termudah membaca soal/chat/pesan yang tampil di layar — termasuk konten TANPA elemen UI (remote desktop, game, WebView, video). Gunakan ini alih-alih screenshot+decode_image bila yang dibutuhkan hanya teksnya",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "ocr_screenshot",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "quiz_scroll_page",
            name = "Gulir Halaman (Sekali, Sesuai Kebutuhan)",
            description = "Menggulir halaman/PC SEKALI per panggilan: {\"direction\":\"down\"|\"up\",\"page\":false|true}. page=true memakai kunci PageUp/PageDown PC (via Shizuku/Termux-ADB); page=false memakai 1 gesture gulir (roda StarDesk otomatis saat remote desktop). ATURAN: panggil HANYA bila ocr_screenshot menunjukkan konten TERPOTONG — jangan pernah menggulir tanpa alasan; setelah scroll, SELALU ocr_screenshot ulang untuk verifikasi",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "quiz_scroll_page",
            parametersSchema = "{\"direction\": \"down|up\", \"page\": false}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "quiz_capture",
            name = "Simpan Frame ke Buffer Analyzer",
            description = "Menyimpan tangkapan layar saat ini ke buffer analyzer di HUD (seperti tombol \ud83d\udcf7 Tambah). User menekan \"Kirim ke AI\" di HUD untuk menganalisis gabungan semua frame",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "quiz_capture",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "adb_shell",
            name = "ADB Shell via Termux (Pengganti Shizuku)",
            description = "Menjalankan perintah shell LEVEL ADB lewat Termux: input keyevent, screencap -p, uiautomator dump, pm grant, dumpsys, dll. Butuh setup sekali: jalankan termux/setup_adb.sh di Termux",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "adb_shell",
            parametersSchema = """{"command": "input keyevent 93"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "battery",
            name = "Info Baterai",
            description = "Memeriksa persentase baterai, voltase, status pengisian, dan temperatur",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "battery",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "shell",
            name = "Shell Command",
            description = "Mengeksekusi perintah shell Android via Shizuku / Termux / Linux Runtime",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "pm list packages -3"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_command",
            name = "Termux Terminal Command",
            description = "Mengeksekusi perintah terminal / shell langsung (contoh: ls, df -h, cat, top, dumpsys, python script)",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "df -h"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_storage",
            name = "Info Storage HP",
            description = "Mendapatkan informasi kapasitas penyimpanan internal HP (Total, Digunakan, dan Sisa dalam GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_storage",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "cek_ram",
            name = "Info RAM HP",
            description = "Mengecek penggunaan memori RAM perangkat (Total, Tersedia, dan Terpakai dalam MB & GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "cek_ram",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_read",
            name = "Baca Clipboard",
            description = "Membaca teks yang sedang disalin di clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_read",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_write",
            name = "Tulis Clipboard",
            description = "Menyalin teks baru ke clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_write",
            parametersSchema = """{"text": "Teks yang ingin disalin"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "flashlight_toggle",
            name = "Senter / Flashlight",
            description = "Menyalakan atau mematikan lampu senter kamera HP",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "flashlight_toggle",
            parametersSchema = """{"enable": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "send_notification",
            name = "Kirim Notifikasi",
            description = "Mengirimkan banner notifikasi lokal ke status bar Android",
            category = "Sistem & Notifikasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "send_notification",
            parametersSchema = """{"title": "Pesan JARVIS", "message": "Isi notifikasi"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "http_request",
            name = "HTTP API Request",
            description = "Melakukan request HTTP GET / POST ke web API eksternal dan mengambil responnya",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{"url": "https://api.ipify.org?format=json", "method": "GET"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_service",
            name = "Termux Service Control",
            description = "Mengontrol background service di Termux & daemon JARVIS (action: status, start, stop, restart, run_agent, list; service: nama_service)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_service",
            parametersSchema = """{"action": "status", "service": "jarvis_agent"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_api",
            name = "Termux API Runner",
            description = "Menjalankan perintah Termux:API (command: battery, wifi, tts, vibrate, torch, notification, toast, location, volume, clipboard-get, clipboard-set, sensor, sms; args: opsi)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_api",
            parametersSchema = """{"command": "battery", "args": ""}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_pkg",
            name = "Termux Package Manager",
            description = "Menginstal, mengupdate, atau mencari package di Termux (action: install, update, list, search; package: nama_package)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_pkg",
            parametersSchema = """{"action": "list", "package": "python"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_python",
            name = "Termux Python Runner",
            description = "Mengeksekusi kode atau script Python 3 secara langsung dalam runtime Termux Linux",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_python",
            parametersSchema = """{"code": "import platform, sys; print('Python', sys.version)"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_file",
            name = "Termux File Operations",
            description = "Membaca, menulis, mendaftar, atau menghapus file di direktori Termux / Storage (action: read, write, list, delete, mkdir; path: direktori/file; content: teks)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_file",
            parametersSchema = """{"action": "list", "path": "."}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_tool",
            name = "Buat Tool Baru (AI Dynamic)",
            description = "Membuat dan mendaftarkan tool otomatis baru ke dalam sistem",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_tool",
            parametersSchema = """{"name": "Nama Tool", "description": "Deskripsi", "script_type": "shell", "command": "perintah", "parameters_schema": "{}"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_python_tool",
            name = "Buat Tool Python Termux (@tool)",
            description = "Membuat modul custom tool Python baru dengan decorator @tool untuk disimpan di tools/custom/",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_python_tool",
            parametersSchema = """{"filename": "nama_tool.py", "tool_name": "nama_tool", "description": "Deskripsi tool", "code": "kode python lengkap"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "save_memory",
            name = "Simpan Memori (.md)",
            description = "Menyimpan informasi penting, preferensi user, atau catatan permanen ke bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "save_memory",
            parametersSchema = """{"category": "Preferensi User", "content": "Rincian informasi yang perlu diingat"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "recall_memory",
            name = "Akses Memori (.md)",
            description = "Membaca dan mencari memori tersimpan dari bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "recall_memory",
            parametersSchema = """{"query": "kata kunci pencarian"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "summarize_memory",
            name = "Arsip & Padatkan Memori (.md)",
            description = "Memadatkan dan mengarsipkan catatan memori lama jika ukuran file memori melebihi batas untuk menjaga kecepatan & efisiensi token AI",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "summarize_memory",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "open_file",
            name = "Buka Berkas",
            description = "Membuka berkas atau dokumen di perangkat via Intent Android",
            category = "Sistem & File",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "open_file",
            parametersSchema = """{"path": "/sdcard/Download/dokumen.pdf", "mime_type": "*/*"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_current_app",
            name = "Dapatkan Aplikasi Aktif",
            description = "Mengetahui aplikasi yang sedang terbuka di layar depan (Package Name & Activity)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_current_app",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_device_resolution",
            name = "Cek Resolusi & Layar Perangkat",
            description = "Mendapatkan dimensi resolusi layar (Width x Height px), kepadatan piksel (DPI), dan orientasi layar agar koordinat gesture/klik akurat 100%",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_device_resolution",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "list_apps",
            name = "Daftar Aplikasi Terinstal",
            description = "Mengambil daftar aplikasi terinstal pada perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "list_apps",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "kill_app",
            name = "Hentikan Paket Aplikasi",
            description = "Menghentikan paksa aplikasi latar belakang via am force-stop",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "kill_app",
            parametersSchema = """{"package_name": "com.example.app"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "decode_image",
            name = "Dekode Gambar (Base64 ke Teks)",
            description = "Mendekode gambar base64/file/uri/screenshot terakhir menjadi deskripsi tekstual lengkap (dimensi, warna dominan, kecerahan, tingkat detail, peta bentuk ASCII, dan OCR teks) sehingga AI tanpa kemampuan vision pun dapat membaca isi gambar",
            category = "Media & Analisis",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "decode_image",
            parametersSchema = """{"source": "last_screenshot", "base64": "opsional", "path": "opsional", "with_ocr": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screen_orientation",
            name = "Cek Orientasi Layar",
            description = "Membaca rotasi layar saat ini (0/90/180/270 derajat, portrait/landscape) beserta dimensi piksel. Panggil ini SEBELUM tap/screenshot agar koordinat tidak meleset saat layar berputar",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "screen_orientation",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "find_by_text",
            name = "Cari Elemen berdasarkan Teks",
            description = "Mencari elemen UI di layar yang cocok dengan teks (label tombol, judul, dst) dan mengembalikan koordinat pusat + ukurannya. Bisa menunggu elemen muncul via timeout_ms",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "find_by_text",
            parametersSchema = """{"text": "Login", "timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "tap_by_text",
            name = "Tap berdasarkan Teks",
            description = "Mengetuk elemen UI langsung berdasarkan teksnya (mis. tombol 'Izinkan' atau 'OK') tanpa menghitung koordinat manual. Jauh lebih akurat daripada tap koordinat, apalagi di mode landscape",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "tap_by_text",
            parametersSchema = """{"text": "Izinkan", "timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "wait_for_element",
            name = "Tunggu Elemen Muncul",
            description = "Menunggu hingga elemen dengan teks tertentu muncul di layar (dengan timeout). Dipakai setelah aksi (tap/open_app) untuk memastikan layar sudah termuat sebelum aksi berikutnya",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "wait_for_element",
            parametersSchema = """{"text": "Berhasil", "timeout_ms": 5000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "dumpsys_window",
            name = "Info Window Aktif (dumpsys)",
            description = "Menampilkan window yang sedang fokus, rotasi display, dan dimensi layar via dumpsys window — untuk diagnosa orientasi dan memastikan app target benar-benar di depan",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mRotation|mDisplayWidth|mDisplayHeight' | head -20",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "scroll_to_text",
            name = "Scroll sampai Teks Ditemukan",
            description = "Scroll otomatis layar sampai elemen dengan teks tertentu terlihat (untuk list panjang) lalu kembalikan koordinatnya — lebih baik daripada swipe buta berulang",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "scroll_to_text",
            parametersSchema = """{"text": "Setelan", "max_swipes": 6}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "wait_stable",
            name = "Tunggu Layar Stabil",
            description = "Menunggu hingga layar tidak berubah lagi (animasi/loading selesai) sebelum aksi atau screenshot berikutnya",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "wait_stable",
            parametersSchema = """{"timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "diff_screen",
            name = "Bandingkan Layar Before/After",
            description = "Membandingkan layar saat ini dengan snapshot pemanggilan sebelumnya. Panggil sebelum aksi (baseline) lalu setelah aksi: 'tidak ada perubahan' berarti aksi kemungkinan gagal",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "diff_screen",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "accessibility_click",
            name = "Klik Elemen via Accessibility",
            description = "Klik node UI langsung lewat AccessibilityNodeInfo berdasarkan teks/view-id — bypass koordinat sama sekali, paling akurat untuk tombol standar",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "accessibility_click",
            parametersSchema = """{"element_id": "com.whatsapp:id/send", "text": "opsional"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "ocr_region",
            name = "OCR Area Tertentu",
            description = "OCR hanya pada AREA tertentu dari screenshot/gambar (crop dulu) — jauh lebih hemat token & akurat daripada decode_image satu layar penuh. Region bisa piksel (left/top/right/bottom) atau persen (x/y/w/h_percent). Tanpa region = satu layar penuh",
            category = "Media & Analisis",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "ocr_region",
            parametersSchema = """{"x_percent": 0, "y_percent": 0, "w_percent": 100, "h_percent": 100, "source": "last_screenshot", "base64": "opsional", "path": "opsional"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "record_screen",
            name = "Rekam Layar (Video MP4)",
            description = "Merekam layar perangkat menjadi video MP4 — untuk debugging multi-step atau dokumentasi otomasi. Params {\"action\": \"start\"|\"stop\"|\"status\"}; otomatis berhenti maksimal 3 menit; hasil berupa path file video",
            category = "Media & Analisis",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "record_screen",
            parametersSchema = """{"action": "start"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "adb_via_shizuku",
            name = "ADB Shell via Shizuku",
            description = "Menjalankan perintah shell level ADB (uid shell) via Shizuku — membuka akses pm grant, am force-stop, uiautomator dump, dumpsys penuh, dll yang diblokir bagi uid aplikasi biasa. Butuh app Shizuku aktif + izin diberikan",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "adb_via_shizuku",
            parametersSchema = """{"command": "pm list packages -3", "action": "exec"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "input_swipe_bezier",
            name = "Swipe Kurva Bezier (Manusiawi)",
            description = "Swipe mengikuti kurva Bezier — menyerupai gerakan jari manusia, berguna untuk UI yang mengabaikan swipe garis lurus (carousel, map, drawer). Param bend (-1.0..1.0) mengatur kelengkungan",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "input_swipe_bezier",
            parametersSchema = """{"x1": 500, "y1": 800, "x2": 500, "y2": 300, "duration_ms": 600, "bend": 0.35}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_request_create",
            name = "Minta Izin Buat Komputer Virtual",
            description = "WAJIB dipanggil sebelum memakai Komputer Virtual. Mengirim kartu persetujuan resmi ke layar Chat pengguna agar pengguna bisa menyetujui atau menolak pembuatan Linux VM terisolasi (distro: debian/ubuntu, mode: gui/cli, include_browser: true/false, packages: [...], reason: alasan)",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_request_create",
            parametersSchema = """{"distro": "debian", "mode": "gui", "include_browser": true, "packages": [], "reason": "Alasan membutuhkan Komputer Virtual"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_status",
            name = "Status Komputer Virtual",
            description = "Memeriksa status Komputer Virtual AI (berhenti/disiapkan/aktif), status persetujuan pengguna, alamat live viewer noVNC, RAM, dan kapasitas disk container",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_status",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_exec",
            name = "Eksekusi Perintah Linux di VM",
            description = "Menjalankan perintah bash/Linux, Python, Node, Git, curl, atau instalasi paket secara penuh di dalam container Komputer Virtual Linux terisolasi (bekerja penuh untuk model Vision & Non-Vision)",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_exec",
            parametersSchema = """{"command": "uname -a && ls -la", "workdir": "/root", "timeout_sec": 45}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_screenshot",
            name = "Screenshot Layar Komputer Virtual",
            description = "Mengambil tangkapan layar desktop X11 Komputer Virtual (DISPLAY=:1) + daftar jendela/tab aktif. Untuk model Vision dikirim sebagai gambar; untuk model Non-Vision otomatis diubah menjadi OCR teks + koordinat piksel [x, y]",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_screenshot",
            parametersSchema = """{"save_as": "opsional"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_inspect_screen",
            name = "Inspeksi Teks & Koordinat Layar VM",
            description = "Membaca isi layar Komputer Virtual dalam format TEKS terstruktur: jendela X11 aktif, tab Chromium CDP, serta seluruh teks layar beserta koordinat klik (x, y) hasil OCR — ideal untuk model Non-Vision atau inspeksi hemat token",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_inspect_screen",
            parametersSchema = """{"include_ocr": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_desktop_action",
            name = "Kontrol Mouse, Keyboard & Browser VM",
            description = "Mengendalikan desktop & aplikasi di dalam Komputer Virtual: click, double_click, right_click, click_text (otomatis cari teks via OCR lalu klik — cocok untuk Non-Vision), move, scroll, type, key, open_browser, open_terminal, open_app",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_desktop_action",
            parametersSchema = """{"action": "open_browser", "url": "https://www.google.com", "x": 640, "y": 360, "text": "", "key": "Return", "direction": "down", "steps": 4, "app": ""}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "vm_control",
            name = "Kontrol Daya Komputer Virtual",
            description = "Mengelola siklus hidup Komputer Virtual yang sudah disetujui pengguna (action: start, stop, restart, install_browser)",
            category = "Komputer Virtual",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "vm_control",
            parametersSchema = """{"action": "start"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_install",
            name = "Instal Tool & Skill dari GitHub",
            description = "Menginstal dan menyimpan permanen tool, CLI, framework, atau Agent Skill (SKILL.md) dari link GitHub (mis. https://github.com/remotion-dev/remotion, remotion-dev/skills), paket npm:<nama>, pip:<nama>, atau nama tool. Otomatis mendeteksi ekosistem, mengunduh dokumentasi/SKILL.md, memilih runtime (VM Linux / Termux), menjalankan setup, dan menyimpannya agar bisa dipakai berkali-kali",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_install",
            parametersSchema = """{"source": "https://github.com/remotion-dev/remotion", "runtime": "auto", "alias": "", "run_setup": true}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_setup",
            name = "Setup Runtime Tool GitHub",
            description = "Menjalankan ulang atau menyelesaikan instalasi dependensi (clone + npm/pip/build) untuk tool GitHub yang sudah tersimpan (mis. setelah Komputer Virtual Linux dinyalakan)",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_setup",
            parametersSchema = """{"id": "remotion", "runtime": "vm"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_run",
            name = "Jalankan Tool GitHub Tersimpan",
            description = "Menjalankan tool GitHub yang sudah terpasang permanen berkali-kali di runtime-nya (VM Linux atau Termux). Bisa kirim 'args' untuk mengisi template default atau 'command' penuh",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_run",
            parametersSchema = """{"id": "remotion", "command": "", "args": "--version", "workdir": "", "timeout_sec": 120}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_read_docs",
            name = "Baca SKILL.md / README Tool GitHub",
            description = "Membaca dokumentasi SKILL.md, sub-skill, atau README.md tersimpan dari tool GitHub yang sudah diinstal agar AI mengikuti aturan resmi penggunaannya",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_read_docs",
            parametersSchema = """{"id": "remotion_skills", "file": "", "query": ""}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_list",
            name = "Daftar Tool GitHub Terpasang",
            description = "Menampilkan semua tool, CLI, dan Agent Skill dari GitHub yang tersimpan permanen di perangkat",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_list",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "github_tool_remove",
            name = "Hapus Tool GitHub Terpasang",
            description = "Menghapus tool/skill GitHub dari registry permanen dan membersihkan direktori instalasinya",
            category = "GitHub & Skills",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "github_tool_remove",
            parametersSchema = """{"id": "remotion"}""",
            riskLevel = ToolRiskLevel.MEDIUM,
            isEnabled = true,
            isBuiltIn = true
        )
    )

    private val _tools = MutableStateFlow<List<CustomTool>>(builtInTools)
    val tools: StateFlow<List<CustomTool>> = _tools.asStateFlow()

    // Permission Mode State
    private val _permissionMode = MutableStateFlow(AiPermissionMode.FULL_ACCESS)
    val permissionMode: StateFlow<AiPermissionMode> = _permissionMode.asStateFlow()

    private val _customPermissions = MutableStateFlow(CustomPermissionSettings())
    val customPermissions: StateFlow<CustomPermissionSettings> = _customPermissions.asStateFlow()

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Permission settings: baca prefs kecil, cepat — aman di main thread.
        loadPermissionSettings()
        // Parsing JSON custom tools bisa besar (tools buatan AI) — jangan blokir
        // main thread saat startup; hasil di-update lewat StateFlow saat siap.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadCustomTools()
        }
    }

    fun setPermissionMode(mode: AiPermissionMode) {
        _permissionMode.value = mode
        sharedPreferences?.edit()?.putString("ai_permission_mode", mode.name)?.apply()
    }

    fun updateCustomPermissions(settings: CustomPermissionSettings) {
        _customPermissions.value = settings
        sharedPreferences?.edit()?.apply {
            putBoolean("perm_read_screen", settings.allowReadScreen)
            putBoolean("perm_tap_swipe", settings.allowTapSwipe)
            putBoolean("perm_type_text", settings.allowTypeText)
            putBoolean("perm_open_app", settings.allowOpenApp)
            putBoolean("perm_shell", settings.allowShellCommands)
            putBoolean("perm_create_tool", settings.allowCreateTools)
            putBoolean("perm_system_keys", settings.allowSystemKeys)
            apply()
        }
    }

    private fun loadPermissionSettings() {
        val prefs = sharedPreferences ?: return
        val modeStr = prefs.getString("ai_permission_mode", AiPermissionMode.FULL_ACCESS.name)
        _permissionMode.value = try {
            AiPermissionMode.valueOf(modeStr ?: AiPermissionMode.FULL_ACCESS.name)
        } catch (_: Exception) {
            AiPermissionMode.FULL_ACCESS
        }

        _customPermissions.value = CustomPermissionSettings(
            allowReadScreen = prefs.getBoolean("perm_read_screen", true),
            allowTapSwipe = prefs.getBoolean("perm_tap_swipe", true),
            allowTypeText = prefs.getBoolean("perm_type_text", true),
            allowOpenApp = prefs.getBoolean("perm_open_app", true),
            allowShellCommands = prefs.getBoolean("perm_shell", true),
            allowCreateTools = prefs.getBoolean("perm_create_tool", true),
            allowSystemKeys = prefs.getBoolean("perm_system_keys", true)
        )
    }

    private fun loadCustomTools() {
        val prefs = sharedPreferences ?: return
        val jsonStr = prefs.getString(KEY_TOOLS_JSON, null)
        val customList = mutableListOf<CustomTool>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArr = JSONArray(jsonStr)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    customList.add(
                        CustomTool(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            description = obj.optString("description"),
                            category = obj.optString("category", "Custom"),
                            scriptType = try {
                                ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                            } catch (_: Exception) {
                                ToolScriptType.SHELL
                            },
                            command = obj.optString("command"),
                            parametersSchema = obj.optString("parametersSchema", "{}"),
                            riskLevel = try {
                                ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                            } catch (_: Exception) {
                                ToolRiskLevel.LOW
                            },
                            isEnabled = obj.optBoolean("isEnabled", true),
                            isBuiltIn = false,
                            createdByAi = obj.optBoolean("createdByAi", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved custom tools", e)
            }
        }

        _tools.value = builtInTools + customList
    }

    private fun saveCustomTools() {
        val prefs = sharedPreferences ?: return
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
                put("createdAt", tool.createdAt)
            }
            jsonArr.put(obj)
        }
        prefs.edit().putString(KEY_TOOLS_JSON, jsonArr.toString()).apply()
    }

    fun addOrUpdateTool(tool: CustomTool) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == tool.id }
        if (index != -1) {
            val existing = current[index]
            if (existing.isBuiltIn) {
                current[index] = existing.copy(isEnabled = tool.isEnabled)
            } else {
                current[index] = tool
            }
        } else {
            current.add(tool)
        }
        _tools.value = current
        saveCustomTools()
    }

    fun deleteTool(toolId: String): Boolean {
        val current = _tools.value.toMutableList()
        val target = current.firstOrNull { it.id == toolId } ?: return false
        if (target.isBuiltIn) {
            return false
        }
        current.removeAll { it.id == toolId }
        _tools.value = current
        saveCustomTools()
        return true
    }

    fun toggleTool(toolId: String) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == toolId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(isEnabled = !item.isEnabled)
            _tools.value = current
            saveCustomTools()
        }
    }

    fun getTool(toolId: String): CustomTool? {
        val q = toolId.trim()
        // Cocokkan berdasarkan ID dulu, lalu berdasarkan NAMA (AI sering memanggil
        // tool custom pakai nama yang diberikannya, bukan id yang di-generate sistem).
        return _tools.value.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: _tools.value.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    fun isToolEnabled(toolId: String): Boolean {
        val tool = getTool(toolId) ?: return false
        return tool.isEnabled
    }

    private val _pendingDeletionRequest = MutableStateFlow<DeletionRequest?>(null)
    val pendingDeletionRequest: StateFlow<DeletionRequest?> = _pendingDeletionRequest.asStateFlow()

    fun requestDeletionApproval(title: String, details: String, onConfirm: () -> Unit, onDeny: () -> Unit) {
        if (_permissionMode.value == AiPermissionMode.FULL_ACCESS) {
            onConfirm()
        } else {
            _pendingDeletionRequest.value = DeletionRequest(
                title = title,
                details = details,
                onConfirm = {
                    _pendingDeletionRequest.value = null
                    onConfirm()
                },
                onDeny = {
                    _pendingDeletionRequest.value = null
                    onDeny()
                }
            )
        }
    }

    fun confirmPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onConfirm()
    }

    fun denyPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onDeny()
    }

    fun isDeletionAction(toolName: String, command: String, params: JSONObject): Boolean {
        val nameLower = toolName.lowercase()
        val cmdLower = command.lowercase()
        val actParam = params.optString("action", params.optString("act", "")).lowercase()

        return nameLower.contains("delete") || nameLower.contains("remove") || nameLower.contains("hapus") ||
               cmdLower.contains("rm ") || cmdLower.contains("rm -rf") || cmdLower.contains("delete") ||
               cmdLower.contains("unlink") || actParam in listOf("delete", "remove", "hapus", "unlink", "clear")
    }

    /**
     * Checks if executing this action is allowed under the current AI Permission Mode.
     */
    fun checkPermission(toolName: String, riskLevel: ToolRiskLevel = ToolRiskLevel.LOW): Pair<Boolean, String?> {
        val mode = _permissionMode.value
        val lower = toolName.lowercase()

        when (mode) {
            AiPermissionMode.SANDBOXED -> {
                val isReadOnly = lower in listOf(
                    "read_screen", "screenshot", "screenshot_annotated", "screenshot_list",
                    "ocr_screenshot", "battery", "get_telemetry", "list_tools", "get_storage",
                    "cek_ram", "clipboard_read", "screen_orientation", "find_by_text",
                    "wait_for_element", "dumpsys_window", "get_device_resolution",
                    "get_current_app", "list_apps", "recall_memory", "wait_stable", "ocr_region",
                    "diff_screen", "scroll_to_text", "web_fetch", "agent_plan", "wait_seconds",
                    "vm_status", "vm_inspect_screen", "github_tool_list", "github_tool_read_docs",
                    "terminal_bg_list", "terminal_bg_read"
                )
                if (!isReadOnly) {
                    return Pair(false, "Aksi '$toolName' diblokir oleh AI Permission Mode: SANDBOXED (Hanya baca yang diizinkan).")
                }
                return Pair(true, null)
            }

            AiPermissionMode.LOW_RISK, AiPermissionMode.FULL_ACCESS -> {
                return Pair(true, null)
            }

            AiPermissionMode.CUSTOM -> {
                val custom = _customPermissions.value
                when (lower) {
                    "read_screen", "screenshot", "screenshot_annotated", "screenshot_list", "ocr_screenshot" ->
                        if (!custom.allowReadScreen) return Pair(false, "Izin Baca Layar dinonaktifkan di setelan kustom.")
                    "tap", "swipe" -> if (!custom.allowTapSwipe) return Pair(false, "Izin Tap & Swipe dinonaktifkan di setelan kustom.")
                    "type_text", "send_text" -> if (!custom.allowTypeText) return Pair(false, "Izin Ketik Teks dinonaktifkan di setelan kustom.")
                    "open_app" -> if (!custom.allowOpenApp) return Pair(false, "Izin Buka Aplikasi dinonaktifkan di setelan kustom.")
                    "shell", "termux_command", "terminal_exec", "terminal_bg_start", "terminal_bg_input", "terminal_bg_stop", "terminal_reset" ->
                        if (!custom.allowShellCommands) return Pair(false, "Izin Shell/Terminal Command dinonaktifkan di setelan kustom.")
                    "create_tool", "create_python_tool" -> if (!custom.allowCreateTools) return Pair(false, "Izin AI Membuat Tool dinonaktifkan di setelan kustom.")
                    "press_key" -> if (!custom.allowSystemKeys) return Pair(false, "Izin Tombol Navigasi dinonaktifkan di setelan kustom.")
                }
                return Pair(true, null)
            }
        }
    }

    /**
     * AI Dynamic Tool Creation: registers a new tool directly generated by AI assistant or user.
     */
    fun registerAiGeneratedTool(
        name: String,
        description: String,
        scriptTypeStr: String,
        command: String,
        paramsSchema: String = "{}"
    ): CustomTool {
        val cleanId = "tool_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_") + "_" + System.currentTimeMillis() % 10000
        val scriptType = try {
            ToolScriptType.valueOf(scriptTypeStr.uppercase())
        } catch (_: Exception) {
            ToolScriptType.SHELL
        }

        val newTool = CustomTool(
            id = cleanId,
            name = name,
            description = description,
            category = "AI Generated",
            scriptType = scriptType,
            command = command,
            parametersSchema = paramsSchema.ifBlank { "{}" },
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = false,
            createdByAi = true,
            createdAt = System.currentTimeMillis()
        )
        addOrUpdateTool(newTool)
        Log.i(TAG, "Registered new tool: ${newTool.id} ($name)")
        return newTool
    }

    /**
     * Executes custom tools directly on Android device with parameter interpolation.
     */
    suspend fun executeCustomTool(tool: CustomTool, params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (!tool.isEnabled) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = "Tool '${tool.name}' sedang dalam status non-aktif."
            )
        }

        val (allowed, reason) = checkPermission(tool.id, tool.riskLevel)
        if (!allowed) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = reason ?: "Permission denied"
            )
        }

        val context = JarvisApp.instance

        return@withContext when (tool.scriptType) {
            ToolScriptType.SHELL -> {
                var finalCommand = tool.command.trim()
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = params.optString(key, "")
                    finalCommand = finalCommand.replace("{$key}", value)
                }

                // If command is generic or wrapped in {command}, check params
                if (finalCommand == "{command}" || finalCommand.isBlank()) {
                    finalCommand = params.optString("command", params.optString("cmd", ""))
                }

                // Save multi-line scripts to filesDir/scripts for proper execution
                if (finalCommand.contains("\n") || finalCommand.startsWith("#!")) {
                    val scriptsDir = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
                    val scriptExt = if (finalCommand.contains("python") || finalCommand.startsWith("#!/usr/bin/env python")) "py" else "sh"
                    val scriptFile = File(scriptsDir, "${tool.id}.$scriptExt")
                    scriptFile.writeText(finalCommand)
                    scriptFile.setExecutable(true)

                    val runnerCmd = if (scriptExt == "py") "python3 \"${scriptFile.absolutePath}\"" else "sh \"${scriptFile.absolutePath}\""
                    AdbShizukuManager.executeShell(runnerCmd, scriptsDir)
                } else {
                    AdbShizukuManager.executeShell(finalCommand)
                }
            }

            ToolScriptType.HTTP -> {
                executeHttpTool(tool, params)
            }

            ToolScriptType.INTENT -> {
                executeIntentTool(context, tool, params)
            }

            ToolScriptType.ACCESSIBILITY -> {
                val cmdLower = tool.command.lowercase().trim()
                // Jalur yang TIDAK wajib AccessibilityService (bisa lewat MediaProjection / Shizuku / Termux-ADB)
                if (cmdLower in listOf("screenshot", "take_screenshot", "screencap")) {
                    val hideOverlay = params.optBoolean("hide_overlay", true)
                    val annotateGrid = params.optBoolean("annotate_grid", false)
                    val saveToFile = params.optBoolean("save_to_file", true)
                    val saveAs = params.optString("save_as", "").ifBlank { null }
                    val delayMs = params.optLong("delay_ms", 0L)
                    val (shot, err) = ScreenshotManager.captureForAgent(
                        context = context,
                        hideOverlay = hideOverlay,
                        annotateGrid = annotateGrid,
                        saveToFile = saveToFile,
                        saveAs = saveAs,
                        delayMs = delayMs
                    )
                    if (shot != null) {
                        val savedNote = if (shot.savedPath != null) "\n💾 Tersimpan: ${shot.savedPath}" else ""
                        val gridNote = if (shot.annotated && shot.elementsSummary.isNotBlank()) "\n${shot.elementsSummary}" else ""
                        val extraMap = mutableMapOf<String, Any?>("screenshot_b64" to shot.base64)
                        if (shot.savedPath != null) extraMap["screenshot_path"] = shot.savedPath
                        return@withContext ToolResult(
                            status = "ok",
                            result = "🖼️ Tangkapan layar OK (${shot.width}x${shot.height}, ${shot.source}). Dikirim ke visi AI.$savedNote$gridNote",
                            extra = extraMap
                        )
                    } else {
                        return@withContext ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                    }
                } else if (cmdLower == "adb_shell") {
                    val cmd = params.optString("command", "")
                    return@withContext if (cmd.isBlank()) {
                        ToolResult("error", message = "Parameter 'command' wajib, mis. {\"command\":\"input keyevent 93\"}")
                    } else {
                        val out = AdbShizukuManager.termuxAdbShellWithOutput(cmd)
                        if (out != null) {
                            ToolResult("ok", result = "🖥️ adb shell ($cmd):\n" + out.take(3000))
                        } else {
                            ToolResult("error", message = "Gagal. Pastikan Termux+ADB siap: buka Termux, jalankan termux/setup_adb.sh lalu 'jad status'")
                        }
                    }
                } else if (cmdLower == "ocr_screenshot") {
                    val (shot, err) = ScreenshotManager.captureForAgent(
                        context = context,
                        hideOverlay = params.optBoolean("hide_overlay", true),
                        annotateGrid = false,
                        saveToFile = false
                    )
                    val base64 = shot?.base64
                    return@withContext if (base64 != null) {
                        val text = runCatching {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp == null) null
                            else {
                                val r = com.example.quiz.OcrEngine.recognize(bmp)
                                bmp.recycle()
                                r.getOrNull()
                            }
                        }.getOrNull()
                        if (text.isNullOrBlank()) {
                            ToolResult("ok", result = "OCR tidak menemukan teks pada tangkapan. Layar mungkin berisi gambar tanpa teks, atau tangkapan kosong/hitam (coba ulangi, atau gunakan tool screenshot).")
                        } else {
                            val shown = text.take(4000) + if (text.length > 4000) "\n...(+${text.length - 4000} karakter lagi)" else ""
                            ToolResult("ok", result = "🔤 Teks di layar (OCR satu langkah):\n$shown")
                        }
                    } else {
                        ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                    }
                }

                val service = JarvisAccessibilityService.instance
                    ?: return@withContext ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")

                if (cmdLower in listOf("read_screen", "get_ui_tree", "dump_ui")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val elements = service.getScreenElements()
                    val summary = elements.joinToString("\n") { el ->
                        "- [${el.id}] '${el.text.ifBlank { el.contentDescription }}' (${el.className}) @ (${el.bounds.centerX}, ${el.bounds.centerY})"
                    }
                    ToolResult("ok", result = "📱 Resolusi Layar: ${metrics.widthPixels}x${metrics.heightPixels} px (${metrics.densityDpi} dpi)\n📱 Elemen UI di Layar saat ini (${elements.size} elemen):\n$summary")
                } else if (params.has("x") || params.has("y") || params.has("x_percent") || params.has("y_percent")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val screenW = metrics.widthPixels.toFloat()
                    val screenH = metrics.heightPixels.toFloat()

                    val targetX = when {
                        params.has("x_percent") -> (params.getDouble("x_percent") / 100.0 * screenW).toFloat()
                        params.has("x") -> {
                            val rx = params.getDouble("x").toFloat()
                            if (rx > 0.0f && rx <= 1.0f) rx * screenW else rx.coerceIn(0f, screenW)
                        }
                        else -> screenW / 2f
                    }

                    val targetY = when {
                        params.has("y_percent") -> (params.getDouble("y_percent") / 100.0 * screenH).toFloat()
                        params.has("y") -> {
                            val ry = params.getDouble("y").toFloat()
                            if (ry > 0.0f && ry <= 1.0f) ry * screenH else ry.coerceIn(0f, screenH)
                        }
                        else -> screenH / 2f
                    }

                    service.tapCoordinates(targetX, targetY)
                } else if (params.has("text") || params.has("value")) {
                    val text = params.optString("text", params.optString("value", ""))
                    val elId = if (params.has("element_id") && !params.isNull("element_id")) params.getString("element_id") else null
                    service.typeText(elId, text)
                } else if (params.has("x1") || params.has("y1")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val screenW = metrics.widthPixels.toFloat()
                    val screenH = metrics.heightPixels.toFloat()

                    val x1 = params.optDouble("x1", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                    val y1 = params.optDouble("y1", (screenH * 0.8).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                    val x2 = params.optDouble("x2", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                    val y2 = params.optDouble("y2", (screenH * 0.2).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }

                    service.swipeCoordinates(
                        x1, y1, x2, y2,
                        params.optLong("duration_ms", 300L)
                    )
                } else if (tool.command.equals("open_app", ignoreCase = true) || params.has("package_name") || params.has("app")) {
                    val pkg = params.optString("package_name", params.optString("app", tool.command))
                    service.openApp(pkg)
                } else {
                    val targetId = params.optString("element_id", tool.command)
                    service.tapElement(targetId)
                }
            }

            ToolScriptType.CUSTOM_LOGIC -> {
                executeCustomLogic(context, tool.command, params)
            }
        }
    }

    private fun executeHttpTool(tool: CustomTool, params: JSONObject): ToolResult {
        var rawUrl = tool.command.trim()
        var method = "GET"

        if (rawUrl.startsWith("GET ", ignoreCase = true)) {
            method = "GET"
            rawUrl = rawUrl.substring(4).trim()
        } else if (rawUrl.startsWith("POST ", ignoreCase = true)) {
            method = "POST"
            rawUrl = rawUrl.substring(5).trim()
        }

        // Interpolate {param} placeholders in URL
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = params.optString(key, "")
            rawUrl = rawUrl.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8"))
        }

        if (params.has("url")) {
            rawUrl = params.getString("url")
        }
        if (params.has("method")) {
            method = params.getString("method").uppercase()
        }

        if (rawUrl.isBlank() || (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))) {
            return ToolResult("error", message = "URL HTTP tidak valid: '$rawUrl'")
        }

        return try {
            val reqBuilder = Request.Builder().url(rawUrl)
            if (method == "POST") {
                val bodyStr = params.optString("body", params.optString("json", "{}"))
                reqBuilder.post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                reqBuilder.get()
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ToolResult("ok", result = "HTTP $code OK:\n$body")
            } else {
                ToolResult("error", message = "HTTP Error $code:\n$body")
            }
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal melakukan HTTP request ke '$rawUrl': ${e.message}")
        }
    }

    private fun executeIntentTool(context: Context, tool: CustomTool, params: JSONObject): ToolResult {
        return try {
            var raw = tool.command.trim()
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.optString(key, "")
                raw = raw.replace("{$key}", value)
            }

            val intent = when {
                raw.startsWith("am start", ignoreCase = true) -> {
                    // Execute via shell for full ADB am intent capability
                    return AdbShizukuManager.executeShell(raw)
                }
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
                raw.startsWith("tel:", ignoreCase = true) -> {
                    Intent(Intent.ACTION_DIAL, Uri.parse(raw))
                }
                raw.startsWith("package:", ignoreCase = true) -> {
                    val pkgName = raw.removePrefix("package:").trim()
                    context.packageManager.getLaunchIntentForPackage(pkgName) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkgName"))
                }
                raw.startsWith("android.settings", ignoreCase = true) -> {
                    Intent(raw)
                }
                else -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(raw)
                    launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult("ok", result = "Berhasil menjalankan Intent / membuka aksi: $raw")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menjalankan Intent '${tool.command}': ${e.message}")
        }
    }

    suspend fun executeCustomLogic(context: Context, command: String, params: JSONObject): ToolResult {
        return when (command.lowercase().trim()) {
            "save_memory" -> {
                val category = params.optString("category", params.optString("title", "Catatan"))
                val content = params.optString("content", params.optString("text", ""))
                if (content.isBlank()) {
                    ToolResult("error", message = "Parameter 'content' wajib diisi untuk menyimpan memori.")
                } else {
                    val result = com.example.data.JarvisMemoryManager.saveMemory(category, content)
                    ToolResult("ok", result = result)
                }
            }

            "recall_memory" -> {
                val query = params.optString("query", params.optString("keyword", ""))
                val result = com.example.data.JarvisMemoryManager.recallMemory(query)
                ToolResult("ok", result = result)
            }

            "summarize_memory" -> {
                val result = com.example.data.JarvisMemoryManager.checkAndAutoArchiveMemory()
                ToolResult("ok", result = result)
            }

            "get_device_resolution", "device_resolution", "screen_resolution" -> {
                val metrics = ScreenshotManager.getScreenMetrics(context)
                ToolResult(
                    status = "ok",
                    result = "📱 Resolusi & Matriks Layar Perangkat:\n- Width: ${metrics.widthPixels} px\n- Height: ${metrics.heightPixels} px\n- Density: ${metrics.densityDpi} dpi (${metrics.density}x)\n- Orientasi: ${if (metrics.widthPixels < metrics.heightPixels) "PORTRAIT" else "LANDSCAPE"}"
                )
            }

            "screenshot", "take_screenshot", "screencap" -> {
                val (base64, err) = ScreenshotManager.captureBase64(context)
                if (base64 != null) {
                    ToolResult(
                        status = "ok",
                        result = "🖼️ Screenshot tangkapan layar berhasil diambil dan dikirim langsung ke analisis visi AI Anda.",
                        extra = mapOf("screenshot_b64" to base64)
                    )
                } else {
                    ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                }
            }

            "open_file" -> {
                try {
                    val pathOrUri = params.optString("path", params.optString("uri", ""))
                    val uri = if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                        Uri.parse(pathOrUri)
                    } else {
                        val file = File(pathOrUri)
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, params.optString("mime_type", "*/*"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult("ok", result = "📂 Berhasil membuka berkas: $pathOrUri")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membuka berkas: ${e.message}")
                }
            }

            "get_current_app" -> {
                val currentPkg = JarvisAccessibilityService.currentApp.value.ifBlank { "com.example" }
                ToolResult("ok", result = "📱 Aplikasi aktif saat ini: $currentPkg")
            }

            "list_apps" -> {
                try {
                    val pm = context.packageManager
                    val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    val appList = packages.take(50).joinToString("\n") { app ->
                        "- ${pm.getApplicationLabel(app)} (${app.packageName})"
                    }
                    ToolResult("ok", result = "📦 Daftar Aplikasi Terinstal (50 teratas):\n$appList")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengambil daftar aplikasi: ${e.message}")
                }
            }

            "kill_app" -> {
                val pkg = params.optString("package_name", params.optString("package", ""))
                if (pkg.isBlank()) {
                    ToolResult("error", message = "Parameter 'package_name' wajib diisi.")
                } else {
                    val res = AdbShizukuManager.executeShell("am force-stop $pkg")
                    ToolResult("ok", result = "Menghentikan paket '$pkg':\n${res.result ?: res.message}")
                }
            }

            "get_storage" -> {
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val totalBytes = stat.blockCountLong * stat.blockSizeLong
                    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                    val usedBytes = totalBytes - freeBytes
                    val totalGb = String.format(java.util.Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0))
                    val usedGb = String.format(java.util.Locale.US, "%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0))
                    val freeGb = String.format(java.util.Locale.US, "%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0))
                    ToolResult("ok", result = "📊 Informasi Penyimpanan HP:\n- Total: $totalGb GB\n- Digunakan: $usedGb GB\n- Sisa: $freeGb GB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca storage: ${e.message}")
                }
            }

            "cek_ram" -> {
                try {
                    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val totalMb = memInfo.totalMem / (1024 * 1024)
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val usedMb = totalMb - availMb
                    val totalGb = String.format(java.util.Locale.US, "%.1f", totalMb / 1024.0)
                    ToolResult("ok", result = "⚡ Informasi RAM HP:\n- Total: $totalMb MB ($totalGb GB)\n- Tersedia: $availMb MB\n- Terpakai: $usedMb MB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca RAM: ${e.message}")
                }
            }

            "battery" -> {
                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    } else false
                    val chargingStr = if (isCharging) "Sedang di-charge ⚡" else "Menggunakan baterai"
                    ToolResult("ok", result = "🔋 Status Baterai: $level% ($chargingStr)")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca baterai: ${e.message}")
                }
            }

            "clipboard_read" -> {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(Clipboard kosong)"
                    ToolResult("ok", result = "📋 Isi Clipboard:\n$text")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca clipboard: ${e.message}")
                }
            }

            "clipboard_write" -> {
                try {
                    val text = params.optString("text", params.optString("content", ""))
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
                    ToolResult("ok", result = "📋 Berhasil menyalin teks ke clipboard: \"$text\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal menulis clipboard: ${e.message}")
                }
            }

            "flashlight_toggle" -> {
                try {
                    val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = camManager.cameraIdList.firstOrNull() ?: return ToolResult("error", message = "Kamera/Senter tidak ditemukan")
                    val enable = params.optBoolean("enable", true)
                    camManager.setTorchMode(cameraId, enable)
                    ToolResult("ok", result = if (enable) "🔦 Senter dinyalakan" else "🔦 Senter dimatikan")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengatur senter: ${e.message}")
                }
            }

            "send_notification" -> {
                try {
                    val title = params.optString("title", "Andra Control")
                    val message = params.optString("message", params.optString("text", "Notifikasi dari JARVIS"))
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = "jarvis_tools_channel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(channelId, "Notifikasi Aplikasi", NotificationManager.IMPORTANCE_DEFAULT)
                        nm.createNotificationChannel(channel)
                    }
                    val notification = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notification)
                    ToolResult("ok", result = "🔔 Notifikasi terkirim: \"$title\" - \"$message\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengirim notifikasi: ${e.message}")
                }
            }

            "termux_service" -> {
                val action = params.optString("action", params.optString("act", "status"))
                val service = params.optString("service", params.optString("name", "jarvis_agent"))
                AdbShizukuManager.manageTermuxService(action, service)
            }

            "termux_api" -> {
                val command = params.optString("command", params.optString("api", params.optString("cmd", "battery")))
                val args = params.optString("args", params.optString("arguments", params.optString("options", "")))
                AdbShizukuManager.executeTermuxApi(command, args)
            }

            "termux_pkg" -> {
                val action = params.optString("action", "list")
                val pkgName = params.optString("package", params.optString("pkg", params.optString("name", "")))
                AdbShizukuManager.executeTermuxPkg(action, pkgName)
            }

            "termux_python" -> {
                val code = params.optString("code", params.optString("script", params.optString("py", "")))
                val filename = params.optString("filename", "script.py")
                AdbShizukuManager.executeTermuxPython(code, filename)
            }

            "termux_file" -> {
                val action = params.optString("action", "list")
                val path = params.optString("path", params.optString("file", "."))
                val content = params.optString("content", params.optString("text", ""))
                AdbShizukuManager.executeTermuxFile(action, path, content)
            }

            "screen_orientation", "get_orientation", "rotation" -> {
                val metrics = ScreenshotManager.getScreenMetrics(context)
                val rot = ScreenshotManager.currentRotation(context)
                val rotName = when (rot) {
                    0 -> "0° — PORTRAIT"
                    1 -> "90° — LANDSCAPE"
                    2 -> "180° — PORTRAIT (terbalik)"
                    3 -> "270° — LANDSCAPE (terbalik)"
                    else -> "$rot°"
                }
                val orient = if (metrics.widthPixels > metrics.heightPixels) "LANDSCAPE" else "PORTRAIT"
                ToolResult(
                    status = "ok",
                    result = "🔄 Rotasi layar saat ini: $rot ($rotName)\n" +
                            "📐 Dimensi display: ${metrics.widthPixels}x${metrics.heightPixels} px @ ${metrics.densityDpi} dpi\n" +
                            "🧭 Orientasi: $orient\n" +
                            "Tip: pakai koordinat dalam ruang ${metrics.widthPixels}x${metrics.heightPixels} ini untuk tap/swipe, atau lebih aman pakai 'tap_by_text'."
                )
            }

            "find_by_text", "wait_for_element" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", params.optString("element_text", "")))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks elemen yang dicari).")
                }
                val defaultTimeout = if (command.equals("wait_for_element", true)) 5000L else 0L
                val timeout = params.optLong("timeout_ms", defaultTimeout).coerceIn(0L, 20000L)
                val deadline = System.currentTimeMillis() + timeout
                var matched = service.findElementsByText(query)
                while (matched.isEmpty() && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(250)
                    matched = service.findElementsByText(query)
                }
                if (matched.isEmpty()) {
                    ToolResult(
                        "error",
                        message = "Tidak ada elemen yang cocok dengan teks '$query'${if (timeout > 0) " setelah menunggu ${timeout}ms" else ""}."
                    )
                } else {
                    val list = matched.joinToString("\n") { el ->
                        "- '${el.text.ifBlank { el.contentDescription }}' (${el.className}) @ center=(${el.bounds.centerX}, ${el.bounds.centerY}) size=${el.bounds.width}x${el.bounds.height}"
                    }
                    ToolResult("ok", result = "🔎 Ditemukan ${matched.size} elemen untuk '$query':\n$list")
                }
            }

            "tap_by_text" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", params.optString("element_text", "")))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks elemen yang akan ditap).")
                }
                val timeout = params.optLong("timeout_ms", 3000L).coerceIn(0L, 20000L)
                service.tapByText(query, timeout)
            }

            "scroll_to_text" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", ""))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks yang dicari saat scroll).")
                }
                val maxSwipes = params.optInt("max_swipes", 6).coerceIn(1, 15)
                service.scrollToText(query, maxSwipes)
            }

            "wait_stable" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                service.waitForStableScreen(params.optLong("timeout_ms", 3000L))
            }

            "diff_screen" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                service.diffScreen()
            }

            "accessibility_click" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val ident = params.optString("element_id", params.optString("text", params.optString("id", "")))
                if (ident.isBlank()) {
                    return ToolResult("error", message = "Parameter 'element_id' atau 'text' wajib diisi.")
                }
                service.tapElement(ident)
            }

            "record_screen" -> {
                val action = params.optString("action", params.optString("act", "start")).lowercase().trim()
                when (action) {
                    "stop" -> ScreenRecordManager.stop()
                    "status" -> ScreenRecordManager.status()
                    "start" -> ScreenRecordManager.start(context)
                    else -> ToolResult("error", message = "Aksi '$action' tidak dikenali. Pilih: start, stop, status")
                }
            }

            "adb_via_shizuku", "shizuku_shell" -> {
                val action = params.optString("action", params.optString("act", "exec")).lowercase().trim()
                when (action) {
                    "status", "check" -> {
                        val alive = AdbShizukuManager.isShizukuBinderAlive()
                        val granted = AdbShizukuManager.shizukuPermissionGranted()
                        ToolResult(
                            status = "ok",
                            result = "🩸 Shizuku binder: ${if (alive) "AKTIF" else "TIDAK AKTIF"} | Izin app: ${if (granted) "DIBERIKAN" else "BELUM"}\n" +
                                    (if (!alive) "Buka app Shizuku → Start (Wireless Debugging), lalu ulangi." else if (!granted) "Panggil {\"action\":\"permission\"} untuk meminta izin." else "Siap dipakai: {\"command\":\"<perintah adb>\"}")
                        )
                    }
                    "permission", "grant" -> AdbShizukuManager.requestShizukuPermission()
                    "exec", "run", "shell" -> {
                        val cmd = params.optString("command", params.optString("cmd", ""))
                        if (cmd.isBlank()) {
                            ToolResult("error", message = "Parameter 'command' wajib diisi untuk action=exec.")
                        } else {
                            AdbShizukuManager.executeShizukuShell(cmd)
                        }
                    }
                    else -> ToolResult("error", message = "Aksi '$action' tidak dikenali. Pilih: exec, status, permission")
                }
            }

            "ocr_region" -> {
                val source = params.optString("source", params.optString("src", "")).trim().lowercase()
                val b64 = params.optString("base64", params.optString("image_base64", params.optString("image", "")))
                val filePath = params.optString("path", params.optString("file", ""))
                val useLast = source == "last_screenshot" || source == "screenshot" || (b64.isBlank() && filePath.isBlank())
                com.example.service.ImageDecodeManager.analyzeRegion(
                    base64 = if (useLast && b64.isBlank()) null else b64.ifBlank { null },
                    path = filePath.ifBlank { null },
                    left = if (params.has("left")) params.optInt("left") else null,
                    top = if (params.has("top")) params.optInt("top") else null,
                    right = if (params.has("right")) params.optInt("right") else null,
                    bottom = if (params.has("bottom")) params.optInt("bottom") else null,
                    xPct = if (params.has("x_percent")) params.optDouble("x_percent") else null,
                    yPct = if (params.has("y_percent")) params.optDouble("y_percent") else null,
                    wPct = if (params.has("w_percent")) params.optDouble("w_percent") else null,
                    hPct = if (params.has("h_percent")) params.optDouble("h_percent") else null
                )
            }

            "input_swipe_bezier", "swipe_bezier", "bezier_swipe" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val metrics = ScreenshotManager.getScreenMetrics(context)
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()
                val x1 = params.optDouble("x1", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                val y1 = params.optDouble("y1", (screenH * 0.8).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                val x2 = params.optDouble("x2", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                val y2 = params.optDouble("y2", (screenH * 0.2).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                val duration = params.optLong("duration_ms", 600L)
                val bend = params.optDouble("bend", params.optDouble("curve", 0.35)).toFloat()
                service.bezierSwipe(x1, y1, x2, y2, duration, bend)
            }

            "terminal_exec", "run_terminal" -> {
                val cmd = params.optString("command", params.optString("cmd", ""))
                val session = params.optString("session", "main")
                val timeoutSec = params.optInt("timeout_sec", 30)
                val asAdb = params.optBoolean("as_adb", false)
                AgentTerminalManager.exec(
                    command = cmd,
                    sessionId = session,
                    timeoutSec = timeoutSec,
                    source = "AI",
                    asAdb = asAdb
                )
            }

            "terminal_bg_start" -> {
                val cmd = params.optString("command", params.optString("cmd", ""))
                val name = params.optString("name", "")
                val session = params.optString("session", "main")
                AgentTerminalManager.startBackground(cmd, name, session)
            }

            "terminal_bg_read" -> {
                val jobId = params.optString("job_id", params.optString("id", ""))
                val tailLines = params.optInt("tail_lines", 60)
                AgentTerminalManager.readBackground(jobId, tailLines)
            }

            "terminal_bg_input" -> {
                val jobId = params.optString("job_id", params.optString("id", ""))
                val input = params.optString("input", params.optString("text", ""))
                AgentTerminalManager.sendInputToBackground(jobId, input)
            }

            "terminal_bg_stop" -> {
                val jobId = params.optString("job_id", params.optString("id", ""))
                AgentTerminalManager.stopBackground(jobId)
            }

            "terminal_bg_list" -> {
                AgentTerminalManager.listBackgroundJobs()
            }

            "terminal_reset" -> {
                val session = params.optString("session", "main")
                AgentTerminalManager.resetSession(session)
            }

            "screenshot_annotated" -> {
                val hideOverlay = params.optBoolean("hide_overlay", true)
                val saveAs = params.optString("save_as", "").ifBlank { null }
                val delayMs = params.optLong("delay_ms", 0L)
                val (shot, err) = ScreenshotManager.captureForAgent(
                    context = context,
                    hideOverlay = hideOverlay,
                    annotateGrid = true,
                    saveToFile = true,
                    saveAs = saveAs,
                    delayMs = delayMs
                )
                if (shot != null) {
                    val savedNote = if (shot.savedPath != null) "\n💾 Tersimpan: ${shot.savedPath}" else ""
                    val extraMap = mutableMapOf<String, Any?>("screenshot_b64" to shot.base64)
                    if (shot.savedPath != null) extraMap["screenshot_path"] = shot.savedPath
                    ToolResult(
                        status = "ok",
                        result = "🎯 Screenshot Grid + Set-of-Mark OK (${shot.width}x${shot.height}, ${shot.source}).$savedNote\n${shot.elementsSummary}",
                        extra = extraMap
                    )
                } else {
                    ToolResult("error", message = err ?: "Gagal mengambil screenshot ber-grid.")
                }
            }

            "screenshot_list" -> {
                val limit = params.optInt("limit", 15)
                val files = ScreenshotManager.listSavedScreenshots(context, limit)
                if (files.isEmpty()) {
                    ToolResult("ok", result = "Belum ada screenshot tersimpan di ${ScreenshotManager.getScreenshotsDir(context).absolutePath}")
                } else {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    val listText = files.joinToString("\n") { f ->
                        "- ${f.name} (${f.length() / 1024} KB, ${fmt.format(java.util.Date(f.lastModified()))}) -> ${f.absolutePath}"
                    }
                    ToolResult("ok", result = "🖼️ Screenshot Tersimpan (${files.size} terbaru):\n$listText")
                }
            }

            "workspace_file" -> {
                AgentWorkspaceTools.executeWorkspaceFile(params)
            }

            "web_fetch" -> {
                AgentWorkspaceTools.executeWebFetch(params)
            }

            "download_file" -> {
                AgentWorkspaceTools.executeDownloadFile(params)
            }

            "agent_plan" -> {
                AgentWorkspaceTools.executeAgentPlan(params)
            }

            "wait_seconds" -> {
                AgentWorkspaceTools.executeWaitSeconds(params)
            }

            "vm_request_create" -> {
                val distro = params.optString("distro", "")
                val mode = params.optString("mode", "")
                val includeBrowser = params.optBoolean("include_browser", true)
                val reason = params.optString("reason", "Menjalankan lingkungan Linux terisolasi untuk tugas AI.")
                val pkgs = mutableListOf<String>()
                val pkgArr = params.optJSONArray("packages")
                if (pkgArr != null) {
                    for (i in 0 until pkgArr.length()) {
                        val p = pkgArr.optString(i, "").trim()
                        if (p.isNotEmpty()) pkgs.add(p)
                    }
                }
                VirtualComputerManager.requestCreateApproval(
                    distroRaw = distro.ifBlank { null },
                    modeRaw = mode.ifBlank { null },
                    includeBrowser = includeBrowser,
                    packages = pkgs,
                    reason = reason
                )
            }

            "vm_status" -> {
                VirtualComputerManager.getStatusReport()
            }

            "vm_exec" -> {
                val cmd = params.optString("command", params.optString("cmd", ""))
                val workdir = params.optString("workdir", params.optString("cwd", "/root"))
                val timeoutSec = params.optInt("timeout_sec", 45)
                VirtualComputerManager.execInVm(cmd, workdir, timeoutSec, source = "AI")
            }

            "vm_screenshot" -> {
                val saveAs = params.optString("save_as", "").ifBlank { null }
                VirtualComputerManager.captureVmScreenshot(saveAs)
            }

            "vm_inspect_screen" -> {
                val includeOcr = params.optBoolean("include_ocr", true)
                VirtualComputerManager.inspectVmScreenText(includeOcr)
            }

            "vm_desktop_action" -> {
                val act = params.optString("action", params.optString("act", ""))
                val x = if (params.has("x")) params.optInt("x") else null
                val y = if (params.has("y")) params.optInt("y") else null
                val text = if (params.has("text")) params.optString("text") else null
                val key = if (params.has("key")) params.optString("key") else null
                val url = if (params.has("url")) params.optString("url") else null
                val direction = if (params.has("direction")) params.optString("direction") else null
                val steps = params.optInt("steps", 4)
                val appCmd = if (params.has("app")) params.optString("app") else null
                VirtualComputerManager.performDesktopAction(
                    action = act,
                    x = x,
                    y = y,
                    text = text,
                    key = key,
                    url = url,
                    direction = direction,
                    steps = steps,
                    appCommand = appCmd
                )
            }

            "vm_control" -> {
                val act = params.optString("action", "status").lowercase().trim()
                when (act) {
                    "start" -> {
                        if (!VirtualComputerManager.status.value.isApproved) {
                            VirtualComputerManager.requestCreateApproval(
                                reason = "Menjalankan Komputer Virtual sesuai permintaan tugas."
                            )
                        } else {
                            VirtualComputerManager.startOrProvisionVm(context)
                        }
                    }
                    "stop" -> VirtualComputerManager.stopVm()
                    "restart" -> {
                        VirtualComputerManager.stopVm()
                        VirtualComputerManager.startOrProvisionVm(context)
                    }
                    "install_browser" -> VirtualComputerManager.installBrowser()
                    "status" -> VirtualComputerManager.getStatusReport()
                    else -> ToolResult("error", message = "Aksi vm_control '$act' tidak dikenali. Pilih: start, stop, restart, install_browser, status.")
                }
            }

            "github_tool_install" -> {
                val src = params.optString("source", params.optString("url", params.optString("repo", "")))
                val runtime = params.optString("runtime", "auto")
                val alias = params.optString("alias", params.optString("name", ""))
                val runSetup = params.optBoolean("run_setup", true)
                GitHubToolManager.installFromSource(
                    source = src,
                    preferredRuntime = runtime,
                    customAlias = alias,
                    runSetupNow = runSetup
                )
            }

            "github_tool_setup" -> {
                val id = params.optString("id", params.optString("name", ""))
                val runtime = params.optString("runtime", "")
                GitHubToolManager.setupToolById(id, runtime)
            }

            "github_tool_run" -> {
                val id = params.optString("id", params.optString("name", ""))
                val cmd = params.optString("command", params.optString("cmd", ""))
                val args = params.optString("args", "")
                val workdir = params.optString("workdir", params.optString("cwd", ""))
                val timeoutSec = params.optInt("timeout_sec", 120)
                GitHubToolManager.runInstalledTool(id, cmd, args, workdir, timeoutSec)
            }

            "github_tool_read_docs" -> {
                val id = params.optString("id", params.optString("name", ""))
                val file = params.optString("file", params.optString("filename", ""))
                val query = params.optString("query", "")
                GitHubToolManager.readToolDocs(id, file, query)
            }

            "github_tool_list" -> {
                GitHubToolManager.listInstalledToolsReport()
            }

            "github_tool_remove" -> {
                val id = params.optString("id", params.optString("name", ""))
                GitHubToolManager.removeTool(id)
            }

            else -> {
                val trimmedCmd = command.trim()
                if (trimmedCmd.startsWith("github_tool_invoke:", ignoreCase = true)) {
                    val toolSlug = trimmedCmd.substringAfter(":").trim()
                    val cmd = params.optString("command", params.optString("cmd", ""))
                    val args = params.optString("args", "")
                    val workdir = params.optString("workdir", params.optString("cwd", ""))
                    val timeoutSec = params.optInt("timeout_sec", 120)
                    GitHubToolManager.runInstalledTool(toolSlug, cmd, args, workdir, timeoutSec)
                } else {
                    ToolResult("ok", result = "Perintah custom logic '$command' selesai dijalankan.")
                }
            }
        }
    }

    /**
     * Export all custom tools to JSON string
     */
    fun exportToolsToJson(): String {
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
            }
            jsonArr.put(obj)
        }
        return jsonArr.toString(2)
    }

    /**
     * Import tools from JSON string
     */
    fun importToolsFromJson(jsonString: String): Result<Int> {
        return try {
            val jsonArr = JSONArray(jsonString.trim())
            var count = 0
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val rawId = obj.optString("id").ifBlank { "custom_" + System.currentTimeMillis() + "_$i" }
                val tool = CustomTool(
                    id = rawId,
                    name = obj.optString("name", "Imported Tool"),
                    description = obj.optString("description", ""),
                    category = obj.optString("category", "Imported"),
                    scriptType = try {
                        ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                    } catch (_: Exception) {
                        ToolScriptType.SHELL
                    },
                    command = obj.optString("command", ""),
                    parametersSchema = obj.optString("parametersSchema", "{}"),
                    riskLevel = try {
                        ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                    } catch (_: Exception) {
                        ToolRiskLevel.LOW
                    },
                    isEnabled = obj.optBoolean("isEnabled", true),
                    isBuiltIn = false,
                    createdByAi = obj.optBoolean("createdByAi", false),
                    createdAt = System.currentTimeMillis()
                )
                addOrUpdateTool(tool)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Ready-made Real Functional Templates for users & guide
    val toolTemplates = listOf(
        CustomTool(
            id = "template_shell_battery_temp",
            name = "Suhu Baterai (Dumpsys)",
            description = "Membaca temperatur baterai perangkat secara akurat via Android dumpsys",
            category = "Hardware & Status",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys battery | grep -i temperature",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_disk_free",
            name = "Cek Disk & Partisi (df -h)",
            description = "Mengecek penggunaan ruang penyimpanan semua partisi internal & SD card",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "df -h",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_screen_lock",
            name = "Kunci Layar (Power Key)",
            description = "Menekan tombol power virtual untuk mematikan/mengunci layar",
            category = "Sistem & Power",
            scriptType = ToolScriptType.SHELL,
            command = "input keyevent 26",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_volume_control",
            name = "Atur Volume Media",
            description = "Menaikkan atau menurunkan level volume suara Android",
            category = "Multimedia",
            scriptType = ToolScriptType.SHELL,
            command = "media volume --show --set {level}",
            parametersSchema = """{"level": "10"}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_url_browser",
            name = "Buka Link Website",
            description = "Membuka link alamat website langsung di browser default",
            category = "Web & Internet",
            scriptType = ToolScriptType.INTENT,
            command = "https://google.com",
            parametersSchema = """{"url": "https://google.com"}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_list_installed_apps",
            name = "Daftar Aplikasi Pihak Ketiga",
            description = "Melihat daftar package aplikasi pengguna yang terpasang di HP",
            category = "Aplikasi",
            scriptType = ToolScriptType.SHELL,
            command = "pm list packages -3",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_settings",
            name = "Buka Pengaturan Android",
            description = "Membuka menu utama Pengaturan (Settings) perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.INTENT,
            command = "android.settings.SETTINGS",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_ping_test",
            name = "Tes Koneksi Ping Internet",
            description = "Mengirim 3 paket ping ke Google DNS untuk menguji latency internet",
            category = "Jaringan",
            scriptType = ToolScriptType.SHELL,
            command = "ping -c 3 8.8.8.8",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_http_public_ip",
            name = "Cek Alamat IP Publik",
            description = "Memeriksa IP publik perangkat menggunakan API ipify",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        )
    )
}
