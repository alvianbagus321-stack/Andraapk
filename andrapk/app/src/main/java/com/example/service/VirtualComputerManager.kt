package com.example.service

import androidx.compose.runtime.Immutable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.example.JarvisApp
import com.example.model.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class VmDistro(
    val id: String,
    val displayName: String,
    val estimatedDiskMb: Int
) {
    DEBIAN("debian", "Debian Bookworm", 750),
    UBUNTU("ubuntu", "Ubuntu 24.04 LTS", 900);

    companion object {
        fun fromId(raw: String?): VmDistro = when (raw?.lowercase()?.trim()) {
            "ubuntu", "ubuntu24", "ubuntu-24" -> UBUNTU
            else -> DEBIAN
        }
    }
}

enum class VmMode(
    val id: String,
    val displayName: String,
    val estimatedRamMb: Int
) {
    DESKTOP_GUI("gui", "Desktop GUI (Openbox + noVNC)", 350),
    TERMINAL_ONLY("cli", "Terminal Linux (CLI Ringan)", 120);

    companion object {
        fun fromId(raw: String?): VmMode = when (raw?.lowercase()?.trim()) {
            "cli", "terminal", "terminal_only", "headless" -> TERMINAL_ONLY
            else -> DESKTOP_GUI
        }
    }
}

enum class VmState {
    STOPPED,
    AWAITING_APPROVAL,
    PROVISIONING,
    RUNNING,
    ERROR
}

@Immutable
data class VmApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val distro: VmDistro = VmDistro.DEBIAN,
    val mode: VmMode = VmMode.DESKTOP_GUI,
    val includeBrowser: Boolean = true,
    val extraPackages: List<String> = emptyList(),
    val reason: String = "Menjalankan lingkungan kerja Linux terisolasi untuk tugas AI.",
    val requestedAt: Long = System.currentTimeMillis()
)

@Immutable
data class VmStatus(
    val state: VmState = VmState.STOPPED,
    val distro: VmDistro = VmDistro.DEBIAN,
    val mode: VmMode = VmMode.DESKTOP_GUI,
    val includeBrowser: Boolean = true,
    val isApproved: Boolean = false,
    val novncPort: Int = 6080,
    val vncPort: Int = 5901,
    val cdpPort: Int = 9222,
    val resolution: String = "1280x720",
    val startedAt: Long? = null,
    val statusDetail: String = "Belum dijalankan",
    val lastError: String? = null
) {
    val novncUrl: String
        get() = "http://127.0.0.1:$novncPort/vnc.html?autoconnect=1&resize=scale&reconnect=1&reconnect_delay=1500"
}

@Immutable
data class VmLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val source: String, // "SYSTEM", "AI", "USER", "VM"
    val message: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * VirtualComputerManager — Mengelola Komputer Virtual Linux lokal (ARM64) milik AI Agent
 * melalui Termux `proot-distro` + Openbox/TigerVNC + noVNC WebView + Chrome DevTools Protocol (CDP).
 *
 * Keamanan & Kontrol Pengguna:
 * - AI TIDAK boleh membuat/menjalankan VM diam-diam. AI wajib memanggil `vm_request_create`
 *   yang memunculkan kartu persetujuan eksplisit di layar Chat.
 * - Pengguna dapat melihat layar VM secara realtime (noVNC), mengirim perintah terminal,
 *   atau menghentikan VM kapan saja (0% CPU & 0 MB RAM saat berhenti).
 * - Mendukung model Vision (screenshot langsung) DAN model Non-Vision (CLI penuh, daftar window X11,
 *   DOM Chromium CDP, ML Kit OCR dengan koordinat piksel [x, y], serta click_text otomatis).
 */
object VirtualComputerManager {

    private const val TAG = "VirtualComputerManager"
    private const val PREFS_NAME = "jarvis_virtual_computer_prefs"
    private const val MAX_LOGS = 200
    private const val DEFAULT_NOVNC_PORT = 6080
    private const val DEFAULT_VNC_PORT = 5901
    private const val DEFAULT_CDP_PORT = 9222
    private const val DEFAULT_RES = "1280x720"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Mencegah dua proses provisioning berjalan bersamaan (setup ganda merusak rootfs). */
    private val provisioningMutex = Mutex()

    /** Diset true saat pengguna menekan "Batalkan Setup"; dibaca loop polling setup. */
    @Volatile
    private var cancelRequested = false

    private val _status = MutableStateFlow(VmStatus())
    val status: StateFlow<VmStatus> = _status.asStateFlow()

    private val _pendingApproval = MutableStateFlow<VmApprovalRequest?>(null)
    val pendingApproval: StateFlow<VmApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _logs = MutableStateFlow<List<VmLogEntry>>(emptyList())
    val logs: StateFlow<List<VmLogEntry>> = _logs.asStateFlow()

    private val _lastScreenshotPath = MutableStateFlow<String?>(null)
    val lastScreenshotPath: StateFlow<String?> = _lastScreenshotPath.asStateFlow()

    @Volatile
    private var prefsLoaded = false

    private fun ensurePrefsLoaded(context: Context = JarvisApp.instance) {
        if (prefsLoaded) return
        prefsLoaded = true
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val distro = VmDistro.fromId(prefs.getString("distro", VmDistro.DEBIAN.id))
            val mode = VmMode.fromId(prefs.getString("mode", VmMode.DESKTOP_GUI.id))
            val includeBrowser = prefs.getBoolean("include_browser", true)
            val approved = prefs.getBoolean("approved", false)
            _status.value = _status.value.copy(
                distro = distro,
                mode = mode,
                includeBrowser = includeBrowser,
                isApproved = approved
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load VM prefs: ${e.message}")
        }
    }

    private fun persistConfig(
        context: Context = JarvisApp.instance,
        distro: VmDistro = _status.value.distro,
        mode: VmMode = _status.value.mode,
        includeBrowser: Boolean = _status.value.includeBrowser,
        approved: Boolean = _status.value.isApproved
    ) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("distro", distro.id)
                .putString("mode", mode.id)
                .putBoolean("include_browser", includeBrowser)
                .putBoolean("approved", approved)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist VM prefs: ${e.message}")
        }
    }

    private fun appendLog(source: String, message: String, isError: Boolean = false) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        val entry = VmLogEntry(source = source, message = clean, isError = isError)
        val current = _logs.value
        _logs.value = if (current.size >= MAX_LOGS) {
            current.drop(current.size - MAX_LOGS + 1) + entry
        } else {
            current + entry
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Membuat streamer log live untuk output proses panjang (setup/install).
     * - Buffer per-baris: hanya baris LENGKAP yang ditampilkan (potongan chunk disimpan dulu).
     * - Progress-bar berbasis carriage return (curl/apt) dinormalisasi: hanya frame terakhir
     *   per batch yang tampil, bukan ratusan pembaruan persen.
     * - Spam frame progres beruntun ("Reading database ... 45%" dst) dirapatkan.
     * - Baris terakhir selalu mengalir ke statusDetail (header panel VM).
     */
    private fun makeLiveLogStreamer(): (String) -> Unit {
        val pending = StringBuilder()
        var lastShownLine = ""
        return { chunk ->
            pending.append(chunk.replace('\r', '\n'))
            val text = pending.toString()
            val lastNl = text.lastIndexOf('\n')
            if (lastNl >= 0) {
                val complete = text.substring(0, lastNl)
                val remainder = text.substring(lastNl + 1)
                pending.setLength(0)
                pending.append(remainder)

                val lines = complete.split('\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                // Rapatkan frame progres identik-awalan yang beruntun (ambil frame terakhir).
                val squashed = ArrayList<String>(lines.size)
                for (line in lines) {
                    val prev = squashed.lastOrNull()
                    if (prev != null && isProgressFramePair(prev, line)) {
                        squashed[squashed.size - 1] = line
                    } else {
                        squashed.add(line)
                    }
                }
                val display = squashed.filter { it != lastShownLine }.takeLast(10)
                if (display.isNotEmpty()) {
                    lastShownLine = display.last()
                    _status.value = _status.value.copy(statusDetail = lastShownLine.take(110))
                    appendLog("VM", display.joinToString("\n"))
                }
            }
        }
    }

    /** Dua baris dianggap frame progres yang sama bila awalannya identik sebelum angka persen/byte. */
    private fun isProgressFramePair(a: String, b: String): Boolean {
        fun stem(s: String): String = s.takeWhile { !it.isDigit() }.trim()
        val sa = stem(a)
        val sb = stem(b)
        return sa.isNotEmpty() && sa == sb && (a.contains('%') || b.contains('%'))
    }

    fun updatePreferredConfig(
        distro: VmDistro = _status.value.distro,
        mode: VmMode = _status.value.mode,
        includeBrowser: Boolean = _status.value.includeBrowser
    ) {
        ensurePrefsLoaded()
        _status.value = _status.value.copy(
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser
        )
        persistConfig(distro = distro, mode = mode, includeBrowser = includeBrowser)
    }

    // =========================================================================
    // Direktori & IPC Script (Termux <-> Android App)
    // =========================================================================

    private fun getVmBaseDir(): File {
        val canUseExternal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageDirectory()?.canWrite() == true
            }
        } catch (_: Exception) {
            false
        }
        val base = if (canUseExternal) {
            File(Environment.getExternalStorageDirectory(), "JARVIS/vm")
        } else {
            File(JarvisApp.instance.filesDir, "vm")
        }
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun getIpcDir(): File = File(getVmBaseDir(), "ipc").apply { if (!exists()) mkdirs() }

    private fun getScreenshotDir(): File = File(getVmBaseDir(), "screenshots").apply { if (!exists()) mkdirs() }

    private fun getScriptFile(): File = File(getVmBaseDir(), "andra_vm.sh")

    /**
     * Tulis ulang skrip kontroler Termux + proot-distro agar selalu sinkron & idempoten.
     */
    private fun writeControlScript(): File {
        val scriptFile = getScriptFile()
        val script = """
#!/data/data/com.termux/files/usr/bin/bash
set -o pipefail

ACTION="${'$'}1"
DISTRO="${'$'}{2:-debian}"
ARG3="${'$'}{3:-}"
ARG4="${'$'}{4:-}"
ARG5="${'$'}{5:-}"
ARG6="${'$'}{6:-}"

export PATH="/data/data/com.termux/files/usr/bin:${'$'}PATH"
export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"

VM_DIR="/sdcard/JARVIS/vm"
mkdir -p "${'$'}VM_DIR/ipc" "${'$'}VM_DIR/screenshots" 2>/dev/null || true
VNC_DISPLAY=":1"
NOVNC_PORT="$DEFAULT_NOVNC_PORT"
VNC_PORT="$DEFAULT_VNC_PORT"
CDP_PORT="$DEFAULT_CDP_PORT"
RES="$DEFAULT_RES"

ensure_proot_distro() {
  if ! command -v proot-distro >/dev/null 2>&1; then
    echo "[VM] Menginstal proot-distro di Termux..."
    pkg update -y >/dev/null 2>&1 || true
    pkg install -y proot-distro || return 1
  fi
  return 0
}

is_distro_installed() {
  [ -d "${'$'}PREFIX/var/lib/proot-distro/installed-rootfs/${'$'}DISTRO" ]
}

run_in_vm() {
  local CMD_STR="${'$'}1"
  proot-distro login "${'$'}DISTRO" --shared-tmp --bind /sdcard:/mnt/sdcard -- bash -lc "export DISPLAY=${'$'}VNC_DISPLAY HOME=/root LANG=C.UTF-8; ${'$'}CMD_STR"
}

case "${'$'}ACTION" in
  setup)
    MODE="${'$'}{ARG3:-gui}"
    WITH_BROWSER="${'$'}{ARG4:-1}"
    EXTRA_PKGS="${'$'}{ARG5:-}"
    ensure_proot_distro || { echo "ERROR: Gagal memasang proot-distro"; exit 1; }
    if ! is_distro_installed; then
      # Jika APLIKASI sudah mengunduh tarball rootfs ke cache bersama, salin ke
      # dlcache proot-distro agar instalasi langsung dari file lokal (tanpa unduh ulang).
      SHARED_CACHE="/sdcard/JARVIS/vm/cache"
      DLCACHE="${'$'}PREFIX/var/lib/proot-distro/dlcache"
      if [ -d "${'$'}SHARED_CACHE" ]; then
        mkdir -p "${'$'}DLCACHE"
        for f in "${'$'}SHARED_CACHE"/*; do
          [ -f "${'$'}f" ] || continue
          BN="${'$'}(basename "${'$'}f")"
          case "${'$'}BN" in *.part) continue ;; esac
          if [ ! -f "${'$'}DLCACHE/${'$'}BN" ]; then
            echo "[VM] Memakai rootfs hasil unduhan aplikasi: ${'$'}BN"
            cp "${'$'}f" "${'$'}DLCACHE/${'$'}BN" || true
          fi
        done
      fi
      echo "[VM] Mengunduh & memasang rootfs ${'$'}DISTRO (ARM64)..."
      proot-distro install "${'$'}DISTRO" || { echo "ERROR: Gagal memasang distro ${'$'}DISTRO"; exit 1; }
    else
      echo "[VM] Rootfs ${'$'}DISTRO sudah tersedia."
    fi

    echo "[VM] Memperbarui paket dasar di dalam ${'$'}DISTRO..."
    BASE_PKGS="ca-certificates curl wget git python3 python3-pip nano procps net-tools"
    GUI_PKGS="openbox xterm dbus-x11 tigervnc-standalone-server tigervnc-common novnc websockify xdotool scrot wmctrl x11-utils fonts-dejavu-core"
    BROWSER_PKG=""
    if [ "${'$'}WITH_BROWSER" = "1" ]; then
      BROWSER_PKG="chromium"
    fi

    if [ "${'$'}MODE" = "gui" ]; then
      INSTALL_LIST="${'$'}BASE_PKGS ${'$'}GUI_PKGS ${'$'}BROWSER_PKG ${'$'}EXTRA_PKGS"
    else
      INSTALL_LIST="${'$'}BASE_PKGS ${'$'}EXTRA_PKGS"
    fi

    run_in_vm "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends ${'$'}INSTALL_LIST || (apt-get update -y && apt-get install -y --no-install-recommends ${'$'}INSTALL_LIST)"
    echo "[VM] Setup selesai untuk ${'$'}DISTRO (mode=${'$'}MODE)."
    ;;

  start)
    MODE="${'$'}{ARG3:-gui}"
    ensure_proot_distro || { echo "ERROR: proot-distro tidak tersedia"; exit 1; }
    if ! is_distro_installed; then
      echo "ERROR: Distro ${'$'}DISTRO belum terpasang. Jalankan setup terlebih dahulu."
      exit 2
    fi

    if [ "${'$'}MODE" = "gui" ]; then
      echo "[VM] Memulai virtual display Xvnc ${'$'}VNC_DISPLAY (${'$'}RES) + Openbox + noVNC :${'$'}NOVNC_PORT..."
      pkill -f "websockify.*${'$'}NOVNC_PORT" 2>/dev/null || true
      pkill -f "Xvnc ${'$'}VNC_DISPLAY" 2>/dev/null || true
      rm -f /data/data/com.termux/files/usr/tmp/.X1-lock /data/data/com.termux/files/usr/tmp/.X11-unix/X1 2>/dev/null || true

      nohup proot-distro login "${'$'}DISTRO" --shared-tmp --bind /sdcard:/mnt/sdcard -- bash -lc "
        export DISPLAY=${'$'}VNC_DISPLAY HOME=/root LANG=C.UTF-8
        rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
        Xvnc ${'$'}VNC_DISPLAY -geometry ${'$'}RES -depth 24 -localhost yes -SecurityTypes None -rfbport ${'$'}VNC_PORT >/tmp/xvnc.log 2>&1 &
        sleep 1.2
        openbox-session >/tmp/openbox.log 2>&1 &
        sleep 0.5
        NOVNC_WEB=/usr/share/novnc
        [ -d /usr/share/novnc ] || NOVNC_WEB=/usr/share/noVNC
        websockify --web=${'$'}NOVNC_WEB 127.0.0.1:${'$'}NOVNC_PORT 127.0.0.1:${'$'}VNC_PORT >/tmp/novnc.log 2>&1 &
        wait
      " >"${'$'}VM_DIR/vm_runtime.log" 2>&1 &

      sleep 2
      echo "[VM] Desktop GUI berjalan di http://127.0.0.1:${'$'}NOVNC_PORT/vnc.html"
    else
      echo "[VM] Mode Terminal Linux (${'$'}DISTRO) aktif dan siap menerima perintah."
    fi
    ;;

  stop)
    echo "[VM] Menghentikan layanan Komputer Virtual..."
    pkill -f "websockify.*${'$'}NOVNC_PORT" 2>/dev/null || true
    pkill -f "Xvnc ${'$'}VNC_DISPLAY" 2>/dev/null || true
    pkill -f "openbox" 2>/dev/null || true
    pkill -f "chromium.*remote-debugging-port=${'$'}CDP_PORT" 2>/dev/null || true
    pkill -f "proot-distro login ${'$'}DISTRO" 2>/dev/null || true
    echo "[VM] Komputer Virtual dihentikan."
    ;;

  rootfs_info)
    # Info URL/checksum tarball rootfs dari plugin proot-distro — dipakai aplikasi
    # untuk mengunduh rootfs sendiri (progres akurat, tanpa bergantung curl Termux).
    ensure_proot_distro || { echo "ERROR: Gagal memasang proot-distro"; exit 1; }
    PLUGIN="${'$'}PREFIX/etc/proot-distro/${'$'}DISTRO.sh"
    if [ ! -f "${'$'}PLUGIN" ]; then
      echo "ERROR: Plugin ${'$'}DISTRO tidak ditemukan di ${'$'}PLUGIN"
      exit 1
    fi
    RF_URL="${'$'}(. "${'$'}PLUGIN" >/dev/null 2>&1; echo "${'$'}{TARBALL_URL[aarch64]:-}")"
    RF_SHA="${'$'}(. "${'$'}PLUGIN" >/dev/null 2>&1; echo "${'$'}{TARBALL_SHA256[aarch64]:-}")"
    echo "URL=${'$'}RF_URL"
    echo "SHA256=${'$'}RF_SHA"
    [ -n "${'$'}RF_URL" ] && echo "FILE=${'$'}(basename "${'$'}RF_URL")"
    ;;

  status)
    INSTALLED="no"
    is_distro_installed && INSTALLED="yes"
    VNC_UP="no"
    pgrep -f "Xvnc ${'$'}VNC_DISPLAY" >/dev/null 2>&1 && VNC_UP="yes"
    NOVNC_UP="no"
    pgrep -f "websockify.*${'$'}NOVNC_PORT" >/dev/null 2>&1 && NOVNC_UP="yes"
    CDP_UP="no"
    pgrep -f "remote-debugging-port=${'$'}CDP_PORT" >/dev/null 2>&1 && CDP_UP="yes"
    echo "INSTALLED=${'$'}INSTALLED"
    echo "VNC_UP=${'$'}VNC_UP"
    echo "NOVNC_UP=${'$'}NOVNC_UP"
    echo "CDP_UP=${'$'}CDP_UP"
    if [ "${'$'}INSTALLED" = "yes" ]; then
      VM_INFO_CMD="${'$'}(cat <<'VMINFOEOF'
uname -a
free -m | awk 'NR==2{print "MEM_MB=" ${'$'}3 "/" ${'$'}2}'
df -h / | awk 'NR==2{print "DISK=" ${'$'}3 "/" ${'$'}2 " (" ${'$'}5 ")"}'
VMINFOEOF
)"
      run_in_vm "${'$'}VM_INFO_CMD" 2>/dev/null || true
    fi
    ;;

  exec)
    B64_CMD="${'$'}ARG3"
    WORKDIR="${'$'}{ARG4:-/root}"
    DECODED_CMD="${'$'}(printf '%s' "${'$'}B64_CMD" | base64 -d 2>/dev/null || true)"
    if [ -z "${'$'}DECODED_CMD" ]; then
      echo "ERROR: Perintah kosong atau base64 tidak valid"
      exit 1
    fi
    run_in_vm "mkdir -p '${'$'}WORKDIR' 2>/dev/null || true; cd '${'$'}WORKDIR' 2>/dev/null || cd /root; ${'$'}DECODED_CMD"
    ;;

  screenshot)
    OUT_PATH="${'$'}ARG3"
    run_in_vm "
      if command -v scrot >/dev/null 2>&1; then
        scrot -o '${'$'}OUT_PATH'
      elif command -v import >/dev/null 2>&1; then
        import -window root '${'$'}OUT_PATH'
      else
        echo 'ERROR: scrot/imagemagick belum terpasang di VM'
        exit 1
      fi
    "
    ;;

  inspect)
    run_in_vm "
      echo '=== ACTIVE WINDOW ==='
      xdotool getactivewindow getwindowname 2>/dev/null || echo '(tidak ada window aktif)'
      echo '=== OPEN WINDOWS ==='
      wmctrl -lpG 2>/dev/null || xdotool search --onlyvisible --name '.' getwindowname 2>/dev/null || echo '(kosong)'
      echo '=== CHROMIUM CDP ==='
      python3 - << 'PYEOF'
import json, urllib.request
try:
    with urllib.request.urlopen('http://127.0.0.1:$DEFAULT_CDP_PORT/json', timeout=2) as r:
        tabs = json.loads(r.read().decode('utf-8', 'ignore'))
    pages = [t for t in tabs if t.get('type') == 'page']
    if not pages:
        print('Tidak ada tab Chromium aktif.')
    for i, t in enumerate(pages[:10]):
        print(f'Tab {i+1}: {t.get(\"title\", \"\")} | {t.get(\"url\", \"\")}')
except Exception:
    print('Chromium CDP tidak aktif (browser belum dibuka).')
PYEOF
    "
    ;;

  gui)
    GUI_ACT="${'$'}ARG3"
    P1="${'$'}ARG4"
    P2="${'$'}ARG5"
    P3="${'$'}ARG6"
    case "${'$'}GUI_ACT" in
      click)
        run_in_vm "xdotool mousemove --sync ${'$'}P1 ${'$'}P2 click 1 && echo 'OK click ${'$'}P1,${'$'}P2'"
        ;;
      double_click)
        run_in_vm "xdotool mousemove --sync ${'$'}P1 ${'$'}P2 click --repeat 2 --delay 80 1 && echo 'OK double_click ${'$'}P1,${'$'}P2'"
        ;;
      right_click)
        run_in_vm "xdotool mousemove --sync ${'$'}P1 ${'$'}P2 click 3 && echo 'OK right_click ${'$'}P1,${'$'}P2'"
        ;;
      move)
        run_in_vm "xdotool mousemove --sync ${'$'}P1 ${'$'}P2 && echo 'OK move ${'$'}P1,${'$'}P2'"
        ;;
      scroll)
        BTN=5
        [ "${'$'}P1" = "up" ] && BTN=4
        STEPS="${'$'}{P2:-4}"
        run_in_vm "xdotool click --repeat ${'$'}STEPS --delay 50 ${'$'}BTN && echo 'OK scroll ${'$'}P1 x${'$'}STEPS'"
        ;;
      type)
        TYPE_CMD="${'$'}(cat <<'VMTYPEEOF'
TXT="${'$'}(printf '%s' "${'$'}TXT_B64" | base64 -d 2>/dev/null || true)"
xdotool type --delay 25 -- "${'$'}TXT" && echo 'OK type'
VMTYPEEOF
)"
        run_in_vm "TXT_B64='${'$'}P1'; ${'$'}TYPE_CMD"
        ;;
      key)
        run_in_vm "xdotool key --clearmodifiers '${'$'}P1' && echo 'OK key ${'$'}P1'"
        ;;
      open_browser)
        BROWSER_CMD="${'$'}(cat <<'VMBROWSEREOF'
VM_URL="${'$'}(printf '%s' "${'$'}VM_URL_B64" | base64 -d 2>/dev/null || true)"
[ -n "${'$'}VM_URL" ] || VM_URL="about:blank"
BROWSER_BIN=""
for b in chromium chromium-browser google-chrome firefox; do
  if command -v "${'$'}b" >/dev/null 2>&1; then BROWSER_BIN="${'$'}b"; break; fi
done
if [ -z "${'$'}BROWSER_BIN" ]; then
  echo 'ERROR: Chromium belum terpasang. Jalankan vm_control action=install_browser terlebih dahulu.'
  exit 1
fi
nohup "${'$'}BROWSER_BIN" --no-sandbox --disable-dev-shm-usage --disable-gpu --remote-debugging-port=$DEFAULT_CDP_PORT --window-size=1280,720 "${'$'}VM_URL" >/tmp/browser.log 2>&1 &
sleep 1.2
echo "OK browser (${'$'}BROWSER_BIN) dibuka ke ${'$'}VM_URL"
VMBROWSEREOF
)"
        run_in_vm "VM_URL_B64='${'$'}P1'; ${'$'}BROWSER_CMD"
        ;;
      open_terminal)
        run_in_vm "nohup xterm -fa 'DejaVu Sans Mono' -fs 11 -bg '#0B0D10' -fg '#ECEFF4' >/tmp/xterm.log 2>&1 & echo 'OK xterm dibuka'"
        ;;
      open_app)
        APP_DEC="${'$'}(printf '%s' "${'$'}P1" | base64 -d 2>/dev/null || true)"
        run_in_vm "nohup ${'$'}APP_DEC >/tmp/app.log 2>&1 & sleep 0.8 && echo 'OK app diluncurkan: ${'$'}APP_DEC'"
        ;;
      *)
        echo "ERROR: Aksi GUI tidak dikenali: ${'$'}GUI_ACT"
        exit 1
        ;;
    esac
    ;;

  install_browser)
    run_in_vm "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends chromium && echo '[VM] Chromium siap digunakan.'"
    ;;

  destroy)
    pkill -f "websockify.*${'$'}NOVNC_PORT" 2>/dev/null || true
    pkill -f "Xvnc ${'$'}VNC_DISPLAY" 2>/dev/null || true
    pkill -f "proot-distro login ${'$'}DISTRO" 2>/dev/null || true
    proot-distro remove "${'$'}DISTRO" || true
    echo "[VM] Distro ${'$'}DISTRO telah dihapus."
    ;;

  *)
    echo "ERROR: Action tidak dikenali: ${'$'}ACTION"
    exit 1
    ;;
esac
        """.trimIndent() + "\n"

        scriptFile.writeText(script)
        scriptFile.setExecutable(true)
        return scriptFile
    }

    /**
     * JEMBATAN VM — transport alternatif ketika intent RUN_COMMAND diblokir
     * (umum di MIUI/HyperOS): daemon bash kecil yang berjalan DI DALAM Termux,
     * mem-polling folder IPC bersama untuk file *.cmd lalu mengeksekusinya.
     * Dikombinasikan dengan klaim-lock atomik (mkdir) sehingga bila intent DAN
     * jembatan sama-sama hidup, perintah tetap dieksekusi tepat satu kali.
     */
    fun writeBridgeScript(): File {
        val ipc = getIpcDir().absolutePath
        val bridgeFile = File(getVmBaseDir(), "andra_bridge.sh")
        val content = """
            #!/data/data/com.termux/files/usr/bin/bash
            # Jembatan VM Andra Control — eksekutor perintah berbasis file.
            IPC="$ipc"
            mkdir -p "${'$'}IPC" 2>/dev/null
            echo ${'$'}${'$'} > "${'$'}IPC/bridge.pid" 2>/dev/null
            termux-wake-lock 2>/dev/null || true
            echo "[jembatan] aktif (pid ${'$'}${'$'})"
            while true; do
              date +%s > "${'$'}IPC/bridge.alive" 2>/dev/null
              for f in "${'$'}IPC"/*.cmd; do
                [ -e "${'$'}f" ] || continue
                c=${'$'}(cat "${'$'}f" 2>/dev/null)
                rm -f "${'$'}f" 2>/dev/null
                [ -n "${'$'}c" ] && bash -c "${'$'}c" >/dev/null 2>&1 &
              done
              sleep 1
            done
        """.trimIndent()
        runCatching { bridgeFile.writeText(content) }
        return bridgeFile
    }

    /**
     * Script bootstrap yang disajikan server lokal di /vmbridge.sh —
     * dijalankan pengguna di Termux dengan: curl -s http://127.0.0.1:8765/vmbridge.sh | bash
     * Menulis ulang script jembatan terbaru, mematikan instance lama, menjalankan yang baru,
     * dan memasang auto-start di ~/.bashrc agar jembatan hidup lagi setiap Termux dibuka.
     */
    fun getBridgeBootstrapScript(): String {
        val bridgePath = writeBridgeScript().absolutePath
        val ipc = getIpcDir().absolutePath
        return """
            #!/data/data/com.termux/files/usr/bin/bash
            B="$bridgePath"
            if [ ! -f "${'$'}B" ]; then
              echo "❌ File jembatan tidak ditemukan: ${'$'}B"
              echo "   Buka aplikasi Andra Control sekali (panel Komputer Virtual), lalu jalankan perintah ini lagi."
              exit 1
            fi
            pkill -f andra_bridge.sh 2>/dev/null
            sleep 0.5
            nohup bash "${'$'}B" >/dev/null 2>&1 &
            grep -q andra_bridge ~/.bashrc 2>/dev/null || echo '(pgrep -f andra_bridge.sh >/dev/null 2>&1 || nohup bash $bridgePath >/dev/null 2>&1 &)' >> ~/.bashrc
            sleep 1.5
            if [ -f "$ipc/bridge.alive" ]; then
              echo "✅ Jembatan VM AKTIF. Kembali ke aplikasi Andra Control, tekan \"Diagnosa Termux\" lalu \"Mulai VM\"."
              echo "   (Jembatan otomatis hidup lagi setiap kamu membuka Termux.)"
            else
              echo "⚠️ Jembatan dijalankan tapi heartbeat belum terlihat. Tunggu 3 detik lalu cek: ls $ipc/bridge.alive"
            fi
        """.trimIndent()
    }

    /** Jembatan dianggap hidup bila heartbeat diperbarui dalam 15 detik terakhir. */
    fun isBridgeAlive(): Boolean {
        val f = File(getIpcDir(), "bridge.alive")
        return f.exists() && System.currentTimeMillis() - f.lastModified() < 15_000L
    }

    /**
     * Eksekusi `andra_vm.sh` lewat Termux RUN_COMMAND dengan mekanisme IPC berbasis file
     * agar stdout, exit code, dan status selesai dapat dibaca akurat oleh aplikasi Android.
     */
    private suspend fun invokeTermuxVmScript(
        action: String,
        distro: VmDistro = _status.value.distro,
        args: List<String> = emptyList(),
        timeoutMs: Long = 60_000L,
        onPartialOutput: ((String) -> Unit)? = null,
        cancellable: Boolean = false
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val ctx = JarvisApp.instance
        if (!AdbShizukuManager.isTermuxInstalled(ctx)) {
            return@withContext Pair(
                -1,
                "Termux belum terpasang di perangkat ini. Pasang Termux dari F-Droid (https://f-droid.org/packages/com.termux/), buka sekali, lalu jalankan: echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"
            )
        }

        // KRITIS: tanpa izin "Akses semua file", folder IPC jatuh ke /data/data milik
        // aplikasi yang MUSTAHIL ditulis Termux — semua perintah pasti timeout.
        // Tolak dini dengan pesan yang jelas alih-alih menunggu timeout menyesatkan.
        if (!AdbShizukuManager.isManageStorageGranted()) {
            return@withContext Pair(
                -1,
                "Izin \"Akses semua file\" (All files access) belum diberikan ke aplikasi ini. " +
                    "Tanpa izin itu Termux tidak bisa bertukar file dengan aplikasi, sehingga semua perintah VM gagal. " +
                    "Tekan tombol \"Beri Izin Akses File\" di panel Komputer Virtual, lalu aktifkan untuk Andra Control."
            )
        }

        // KRITIS #2: TERMUX juga harus punya izin penyimpanan agar bisa menulis balik
        // hasil perintah ke folder bersama. Ini bisa dicek langsung via PackageManager.
        if (AdbShizukuManager.isTermuxStorageGranted(ctx) == false) {
            return@withContext Pair(
                -1,
                "TERMUX belum diberi izin penyimpanan — inilah penyebab timeout. Perbaiki:\n" +
                    "1. Buka Termux, jalankan: termux-setup-storage lalu tekan IZINKAN.\n" +
                    "2. Atau manual: Setelan > Aplikasi > Termux > Izin > Penyimpanan/File dan media > Izinkan.\n" +
                    "Setelah itu coba lagi."
            )
        }

        val scriptFile = writeControlScript()
        writeBridgeScript()
        val ipcDir = getIpcDir()
        val jobId = UUID.randomUUID().toString().take(8)
        val outFile = File(ipcDir, "vm_${jobId}.out")
        val rcFile = File(ipcDir, "vm_${jobId}.rc")
        val doneFile = File(ipcDir, "vm_${jobId}.done")
        val lockDir = File(ipcDir, "vm_${jobId}.lock")
        val cmdFile = File(ipcDir, "vm_${jobId}.cmd")

        val safeArgs = args.joinToString(" ") { arg ->
            "'" + arg.replace("'", "'\\''") + "'"
        }
        val bashCmd = buildString {
            append("bash '").append(scriptFile.absolutePath).append("' ")
            append("'").append(action).append("' ")
            append("'").append(distro.id).append("' ")
            if (safeArgs.isNotBlank()) append(safeArgs).append(" ")
            append("> '").append(outFile.absolutePath).append("' 2>&1; ")
            append("printf '%s' \"\$?\" > '").append(rcFile.absolutePath).append("'; ")
            append("touch '").append(doneFile.absolutePath).append("'")
        }
        // Klaim atomik: intent RUN_COMMAND dan Jembatan VM bisa sama-sama mencoba
        // menjalankan job ini — mkdir memastikan hanya SATU yang benar-benar jalan.
        val claimedCmd = "mkdir '${lockDir.absolutePath}' 2>/dev/null || exit 0; $bashCmd"

        // Transport #2 (Jembatan): tulis file perintah agar daemon jembatan di Termux
        // bisa mengeksekusinya walau intent RUN_COMMAND diblokir sistem (MIUI, dll).
        runCatching { cmdFile.writeText(claimedCmd) }

        // Transport #1 (intent RUN_COMMAND) — tetap dikirim; yang tercepat yang menang.
        val sent = AdbShizukuManager.sendTermuxRunCommandIntent(
            context = ctx,
            executablePath = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("-c", claimedCmd)
        )
        if (!sent && !isBridgeAlive()) {
            runCatching { cmdFile.delete(); lockDir.delete() }
            return@withContext Pair(
                -1,
                "Gagal mengirim perintah ke Termux: ${AdbShizukuManager.lastTermuxIntentError ?: "intent ditolak sistem"}. " +
                    "Buka aplikasi Termux sekali (biarkan di recent apps), lalu coba lagi. " +
                    "Atau aktifkan Jembatan VM: di Termux jalankan: curl -s http://127.0.0.1:8765/vmbridge.sh | bash"
            )
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProgressCheck = 0L
        var readOffset = 0L
        while (System.currentTimeMillis() < deadline) {
            // Pengguna menekan "Batalkan Setup": kirim sinyal kill ke proses di sisi
            // Termux (unduhan/instalasi benar-benar berhenti, tidak lanjut di background),
            // bersihkan file IPC, lalu kembalikan kode khusus -3.
            if (cancellable && cancelRequested) {
                sendVmKillSignal(ctx)
                delay(600)
                runCatching { outFile.delete(); rcFile.delete(); doneFile.delete(); cmdFile.delete(); lockDir.delete() }
                return@withContext Pair(-3, "Dibatalkan oleh pengguna.")
            }
            if (doneFile.exists()) {
                delay(120)
                val out = runCatching { outFile.readText() }.getOrDefault("").trim()
                val rc = runCatching { rcFile.readText().trim().toInt() }.getOrDefault(0)
                runCatching { outFile.delete(); rcFile.delete(); doneFile.delete(); cmdFile.delete(); lockDir.delete() }
                return@withContext Pair(rc, out)
            }
            // Tail inkremental: baca HANYA byte baru sejak pembacaan terakhir lalu
            // teruskan apa adanya ke callback — seluruh output (Get:, Unpacking,
            // Setting up, progres curl, dst) mengalir live seperti terminal asli.
            val now = System.currentTimeMillis()
            if (onPartialOutput != null && now - lastProgressCheck >= 1_500L) {
                lastProgressCheck = now
                runCatching {
                    if (outFile.exists()) {
                        val len = outFile.length()
                        if (len > readOffset) {
                            java.io.RandomAccessFile(outFile, "r").use { raf ->
                                raf.seek(readOffset)
                                val toRead = (len - readOffset).coerceAtMost(64_000L).toInt()
                                val bytes = ByteArray(toRead)
                                val n = raf.read(bytes)
                                if (n > 0) {
                                    readOffset += n
                                    onPartialOutput(String(bytes, 0, n, Charsets.UTF_8))
                                }
                            }
                        }
                    }
                }
            }
            delay(250)
        }

        val partial = runCatching { if (outFile.exists()) outFile.readText().trim() else "" }.getOrDefault("")
        runCatching { outFile.delete(); rcFile.delete(); doneFile.delete(); cmdFile.delete(); lockDir.delete() }

        val hint = if (partial.isBlank()) {
            "Timeout menunggu respons Termux (${timeoutMs / 1000}d) — Termux tidak menulis output apa pun. Penyebab paling umum:\n" +
                "1. Termux belum punya izin storage. Buka Termux, jalankan: termux-setup-storage lalu IZINKAN akses file. Tanpa ini Termux tidak bisa menulis hasil ke folder bersama.\n" +
                "2. Di Termux belum dijalankan: echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings\n" +
                "3. HP Xiaomi/Redmi: aktifkan 'Mulai otomatis' (Autostart) untuk Termux di Keamanan > Izin, dan matikan pembatasan baterai Termux.\n" +
                "4. Buka aplikasi Termux sekali dan biarkan tetap di recent apps saat setup berjalan.\n" +
                "5. PALING AMPUH — aktifkan Jembatan VM: di Termux jalankan: curl -s http://127.0.0.1:8765/vmbridge.sh | bash\n" +
                "Gunakan tombol \"Diagnosa Termux\" di panel VM untuk pengecekan otomatis."
        } else {
            "Perintah masih berjalan / timeout (${timeoutMs / 1000}d).\nOutput terakhir:\n$partial"
        }
        Pair(-2, hint)
    }

    /**
     * Mematikan paksa seluruh proses setup/VM di sisi Termux: script kontrol,
     * proot-distro, proot, serta unduhan curl/wget yang sedang berjalan.
     * Dipakai saat pengguna membatalkan setup agar unduhan ratusan MB tidak
     * terus berjalan diam-diam di background.
     */
    private fun sendVmKillSignal(ctx: Context = JarvisApp.instance) {
        val killCmd = listOf(
            "pkill -9 -f 'andra_vm.sh' 2>/dev/null",
            "pkill -9 -f 'proot-distro' 2>/dev/null",
            "pkill -9 -f '/bin/proot' 2>/dev/null",
            "pkill -9 -x proot 2>/dev/null",
            "pkill -9 -x curl 2>/dev/null",
            "pkill -9 -x wget 2>/dev/null",
            "true"
        ).joinToString("; ")
        runCatching {
            AdbShizukuManager.sendTermuxRunCommandIntent(
                context = ctx,
                executablePath = "/data/data/com.termux/files/usr/bin/bash",
                arguments = arrayOf("-c", killCmd)
            )
        }.onFailure { Log.w(TAG, "Gagal mengirim sinyal kill ke Termux: ${it.message}") }
    }

    /** Fire-and-forget untuk tombol "Batalkan Setup" di UI. */
    fun cancelProvisioningAsync() {
        scope.launch { cancelProvisioning() }
    }

    /**
     * Membatalkan proses setup yang sedang berjalan. Loop polling di
     * invokeTermuxVmScript mendeteksi flag ini (≤250 ms), mengirim kill ke Termux,
     * lalu startOrProvisionVm menutup status dengan rapi (mutex dilepas via finally).
     */
    suspend fun cancelProvisioning(): ToolResult = withContext(Dispatchers.IO) {
        if (_status.value.state != VmState.PROVISIONING) {
            return@withContext ToolResult("error", message = "Tidak ada proses setup yang sedang berjalan.")
        }
        cancelRequested = true
        _status.value = _status.value.copy(statusDetail = "Membatalkan setup...")
        appendLog("SYSTEM", "Membatalkan setup atas permintaan pengguna...")
        ToolResult("ok", result = "Permintaan pembatalan setup dikirim.")
    }

    /** Dipakai UI untuk menampilkan tombol perbaikan izin bila belum diberikan. */
    fun hasAllFilesAccess(): Boolean = AdbShizukuManager.isManageStorageGranted()

    /** Fire-and-forget untuk tombol "Diagnosa Termux" di UI; hasil tampil di log Terminal VM. */
    fun diagnoseTermuxAsync() {
        scope.launch { diagnoseTermux() }
    }

    /**
     * Pengecekan berlapis koneksi aplikasi ↔ Termux. Menemukan lapisan yang gagal
     * dan menulis saran perbaikan spesifik ke log Terminal VM.
     */
    suspend fun diagnoseTermux(): ToolResult = withContext(Dispatchers.IO) {
        val ctx = JarvisApp.instance
        appendLog("SYSTEM", "=== DIAGNOSA KONEKSI TERMUX ===")

        // 1. Termux terpasang?
        if (!AdbShizukuManager.isTermuxInstalled(ctx)) {
            appendLog("SYSTEM", "[1] GAGAL: Termux tidak terpasang. Pasang dari F-Droid: https://f-droid.org/packages/com.termux/", isError = true)
            return@withContext ToolResult("error", message = "Termux tidak terpasang.")
        }
        appendLog("SYSTEM", "[1] OK: Termux terpasang.")

        // 2. Izin "Akses semua file" aplikasi ini?
        if (!AdbShizukuManager.isManageStorageGranted()) {
            appendLog(
                "SYSTEM",
                "[2] GAGAL: Aplikasi belum punya izin \"Akses semua file\". Tanpa ini folder pertukaran jatuh ke area privat yang tidak bisa ditulis Termux. Tekan tombol \"Beri Izin Akses File\" lalu aktifkan untuk Andra Control, kemudian diagnosa ulang.",
                isError = true
            )
            return@withContext ToolResult("error", message = "Izin Akses semua file belum diberikan.")
        }
        appendLog("SYSTEM", "[2] OK: Izin \"Akses semua file\" aktif.")

        // 3. Aplikasi bisa menulis folder IPC bersama?
        val ipcDir = getIpcDir()
        appendLog("SYSTEM", "[3] Folder pertukaran: ${ipcDir.absolutePath}")
        val appCanWrite = runCatching {
            val f = File(ipcDir, "app_probe.tmp")
            f.writeText("ok")
            f.delete()
        }.isSuccess
        if (!appCanWrite) {
            appendLog("SYSTEM", "[3] GAGAL: Aplikasi tidak bisa menulis ke folder tersebut. Coba cabut-pasang izin \"Akses semua file\" lalu restart aplikasi.", isError = true)
            return@withContext ToolResult("error", message = "Folder IPC tidak bisa ditulis aplikasi.")
        }
        appendLog("SYSTEM", "[3] OK: Aplikasi bisa menulis folder pertukaran.")

        // 4. Termux punya izin penyimpanan? (hasil termux-setup-storage)
        when (AdbShizukuManager.isTermuxStorageGranted(ctx)) {
            true -> appendLog("SYSTEM", "[4] OK: Termux punya izin penyimpanan.")
            false -> {
                appendLog(
                    "SYSTEM",
                    "[4] GAGAL: TERMUX belum diberi izin penyimpanan — ini penyebab timeout!\n" +
                        "Perbaiki: buka Termux, jalankan: termux-setup-storage lalu tekan IZINKAN.\n" +
                        "Atau manual: Setelan > Aplikasi > Termux > Izin > Penyimpanan/File dan media > Izinkan.\n" +
                        "Setelah itu tekan \"Diagnosa Termux\" lagi.",
                    isError = true
                )
                return@withContext ToolResult("error", message = "Termux belum punya izin penyimpanan.")
            }
            null -> appendLog("SYSTEM", "[4] INFO: Status izin penyimpanan Termux tidak terbaca — lanjut uji langsung.")
        }

        // 5. Status Jembatan VM (transport alternatif bila intent diblokir sistem).
        writeBridgeScript()
        val bridgeAlive = isBridgeAlive()
        appendLog(
            "SYSTEM",
            if (bridgeAlive) "[5] OK: Jembatan VM aktif di Termux."
            else "[5] INFO: Jembatan VM belum aktif. Bila langkah [6] gagal, aktifkan dengan menjalankan di Termux: curl -s http://127.0.0.1:8765/vmbridge.sh | bash"
        )

        // 6. Kirim probe lewat KEDUA jalur: intent RUN_COMMAND + file .cmd untuk jembatan.
        val probeFile = File(ipcDir, "probe_${System.currentTimeMillis()}.ok")
        val probeCmd = "echo ANDRA_PROBE_OK > '${probeFile.absolutePath}' 2>/dev/null || " +
            "echo ANDRA_PROBE_OK > '${probeFile.absolutePath.replace("/storage/emulated/0", "/sdcard")}' 2>/dev/null"
        val probeCmdFile = File(ipcDir, "probe_${System.currentTimeMillis()}.cmd")
        runCatching { probeCmdFile.writeText(probeCmd) }
        appendLog("SYSTEM", "[6] Mengirim perintah uji ke Termux (tunggu maks 12 detik)...")
        val sent = AdbShizukuManager.sendTermuxRunCommandIntent(
            context = ctx,
            executablePath = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("-c", probeCmd)
        )
        if (!sent && !bridgeAlive) {
            appendLog("SYSTEM", "[6] GAGAL mengirim intent ke Termux: ${AdbShizukuManager.lastTermuxIntentError ?: "ditolak sistem"}. Buka Termux sekali, biarkan di recent apps, lalu coba lagi — atau aktifkan Jembatan VM (lihat langkah [5]).", isError = true)
            runCatching { probeCmdFile.delete() }
            return@withContext ToolResult("error", message = "Intent RUN_COMMAND gagal dikirim.")
        }
        val deadline = System.currentTimeMillis() + 12_000L
        var probeOk = false
        while (System.currentTimeMillis() < deadline) {
            if (probeFile.exists()) { probeOk = true; break }
            delay(400)
        }
        runCatching { probeFile.delete(); probeCmdFile.delete() }

        if (probeOk) {
            appendLog("SYSTEM", "[6] OK: Termux merespons dan bisa menulis file. KONEKSI SEHAT ✓ — silakan mulai setup VM.")
            return@withContext ToolResult("ok", result = "Koneksi Termux sehat.")
        }

        appendLog(
            "SYSTEM",
            "[6] GAGAL: Termux tidak merespons lewat intent maupun jembatan. Perbaiki berurutan:\n" +
                "a. PALING AMPUH: aktifkan Jembatan VM — buka Termux lalu jalankan: curl -s http://127.0.0.1:8765/vmbridge.sh | bash — lalu diagnosa ulang.\n" +
                "b. Pastikan sudah pernah: echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings\n" +
                "c. HP Xiaomi/Redmi/POCO: Setelan > Aplikasi > Termux > aktifkan \"Mulai otomatis\" (Autostart) dan set Baterai ke \"Tanpa batasan\".\n" +
                "d. Biarkan Termux terbuka di recent apps, lalu tekan \"Diagnosa Termux\" lagi.",
            isError = true
        )
        ToolResult("error", message = "Termux tidak merespons probe.")
    }

    /**
     * Mengunduh tarball rootfs langsung oleh APLIKASI (HttpURLConnection) dengan progres
     * persen/MB/kecepatan live di UI, verifikasi SHA-256, lalu menaruhnya di cache bersama
     * (/sdcard/JARVIS/vm/cache) agar proot-distro di Termux memasang dari file lokal tanpa
     * mengunduh ulang. Best-effort: bila gagal apa pun alasannya, setup tetap berjalan dan
     * proot-distro mengunduh sendiri seperti biasa.
     */
    private suspend fun preDownloadRootfs(distro: VmDistro): Boolean = withContext(Dispatchers.IO) {
        try {
            val (rc, out) = invokeTermuxVmScript("rootfs_info", distro, timeoutMs = 90_000L)
            if (rc != 0) {
                appendLog("SYSTEM", "Info rootfs tidak tersedia — unduhan akan dilakukan proot-distro di Termux.")
                return@withContext false
            }
            val url = Regex("(?m)^URL=(\\S+)").find(out)?.groupValues?.get(1).orEmpty()
            val sha = Regex("(?m)^SHA256=(\\S+)").find(out)?.groupValues?.get(1).orEmpty()
            val fileName = Regex("(?m)^FILE=(\\S+)").find(out)?.groupValues?.get(1).orEmpty()
            if (!url.startsWith("http") || fileName.isBlank()) return@withContext false

            val cacheDir = File(getVmBaseDir(), "cache").apply { if (!exists()) mkdirs() }
            val target = File(cacheDir, fileName)
            if (target.exists() && sha.isNotBlank() && sha256Of(target).equals(sha, ignoreCase = true)) {
                appendLog("SYSTEM", "Rootfs sudah ada di cache aplikasi (${target.length() / 1_048_576} MB, checksum cocok) — dipakai langsung.")
                return@withContext true
            }

            appendLog("SYSTEM", "Mengunduh rootfs lewat APLIKASI (bukan Termux): $fileName")
            val tmp = File(cacheDir, "$fileName.part")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AndraControl-VM")
            }
            val total = conn.contentLengthLong
            val startAt = System.currentTimeMillis()
            var read = 0L
            var lastStatus = 0L
            var lastLog = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelRequested) {
                            runCatching { tmp.delete() }
                            appendLog("SYSTEM", "Unduhan rootfs dibatalkan.")
                            return@withContext false
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastStatus >= 1_000L) {
                            lastStatus = now
                            val mb = read / 1_048_576
                            val pct = if (total > 0) " (${read * 100 / total}%)" else ""
                            val speedKb = (read / 1024.0 / ((now - startAt).coerceAtLeast(1) / 1000.0)).toInt()
                            val line = "Unduh rootfs: $mb MB" +
                                (if (total > 0) " / ${total / 1_048_576} MB" else "") +
                                "$pct · $speedKb KB/s"
                            _status.value = _status.value.copy(statusDetail = line)
                            if (now - lastLog >= 5_000L) {
                                lastLog = now
                                appendLog("VM", line)
                            }
                        }
                    }
                }
            }
            if (sha.isNotBlank() && !sha256Of(tmp).equals(sha, ignoreCase = true)) {
                appendLog("SYSTEM", "Checksum rootfs tidak cocok — file dibuang; proot-distro akan mengunduh sendiri.", isError = true)
                runCatching { tmp.delete() }
                return@withContext false
            }
            if (!tmp.renameTo(target)) {
                runCatching { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
            appendLog("SYSTEM", "Rootfs selesai diunduh aplikasi (${target.length() / 1_048_576} MB) ✓ — Termux tinggal memasang dari file lokal.")
            true
        } catch (e: Exception) {
            appendLog("SYSTEM", "Unduhan via aplikasi gagal (${e.message}) — proot-distro akan mengunduh sendiri.", isError = true)
            false
        }
    }

    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isNoVncReachable(port: Int = _status.value.novncPort): Boolean {
        return try {
            val conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 900
                readTimeout = 900
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..404
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // Alur Persetujuan Pengguna di Chat
    // =========================================================================

    /**
     * Dipanggil oleh AI melalui tool `vm_request_create`.
     * Tidak langsung menjalankan VM — menampilkan kartu persetujuan eksplisit di Chat.
     */
    fun requestCreateApproval(
        distroRaw: String? = null,
        modeRaw: String? = null,
        includeBrowser: Boolean = true,
        packages: List<String> = emptyList(),
        reason: String = "Menjalankan lingkungan kerja Linux terisolasi untuk membantu tugas pengguna."
    ): ToolResult {
        ensurePrefsLoaded()
        val current = _status.value
        val distro = if (!distroRaw.isNullOrBlank()) VmDistro.fromId(distroRaw) else current.distro
        val mode = if (!modeRaw.isNullOrBlank()) VmMode.fromId(modeRaw) else current.mode

        if (current.state == VmState.RUNNING && current.isApproved) {
            return ToolResult(
                status = "ok",
                result = "Komputer Virtual (${current.distro.displayName} · ${current.mode.displayName}) sudah aktif dan disetujui. Kamu dapat langsung memakai vm_exec, vm_inspect_screen, vm_screenshot, atau vm_desktop_action."
            )
        }

        val req = VmApprovalRequest(
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser,
            extraPackages = packages.filter { it.isNotBlank() }.take(12),
            reason = reason.ifBlank { "Menjalankan lingkungan Linux terisolasi untuk tugas AI." }
        )
        _pendingApproval.value = req
        _status.value = current.copy(
            state = VmState.AWAITING_APPROVAL,
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser,
            statusDetail = "Menunggu persetujuan pengguna di Chat",
            lastError = null
        )
        appendLog("AI", "Meminta persetujuan pembuatan Komputer Virtual (${distro.displayName}, ${mode.displayName}).")

        return ToolResult(
            status = "ok",
            result = "Permintaan persetujuan Komputer Virtual telah ditampilkan sebagai kartu persetujuan di layar Chat pengguna.\n" +
                "- Sistem: ${distro.displayName}\n" +
                "- Mode: ${mode.displayName}\n" +
                "- Browser Chromium: ${if (includeBrowser) "Ya" else "Tidak"}\n" +
                "- Alasan: ${req.reason}\n\n" +
                "PENTING: Berhenti memanggil tool VM untuk giliran ini. Jelaskan singkat kepada pengguna bahwa kartu persetujuan sudah muncul di Chat dan tunggu pengguna menekan tombol 'Setujui & Jalankan' atau 'Tolak'."
        )
    }

    /**
     * Dipanggil saat pengguna menekan "Setujui & Jalankan" pada kartu persetujuan di Chat.
     */
    fun approveAndStart(
        context: Context = JarvisApp.instance,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        ensurePrefsLoaded(context)
        val req = _pendingApproval.value
        val distro = req?.distro ?: _status.value.distro
        val mode = req?.mode ?: _status.value.mode
        val includeBrowser = req?.includeBrowser ?: _status.value.includeBrowser
        val extraPkgs = req?.extraPackages ?: emptyList()

        _pendingApproval.value = null
        persistConfig(context, distro, mode, includeBrowser, approved = true)
        _status.value = _status.value.copy(
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser,
            isApproved = true
        )
        appendLog("USER", "Menyetujui pembuatan & akses penuh Komputer Virtual (${distro.displayName}).")

        scope.launch {
            val res = startOrProvisionVm(
                context = context,
                distro = distro,
                mode = mode,
                includeBrowser = includeBrowser,
                extraPackages = extraPkgs
            )
            val ok = res.status == "ok"
            onComplete?.invoke(ok, res.result ?: res.message ?: "")
        }
    }

    /**
     * Dipanggil saat pengguna menekan "Tolak" pada kartu persetujuan di Chat.
     */
    fun rejectApproval(reason: String = "Ditolak oleh pengguna") {
        _pendingApproval.value = null
        _status.value = _status.value.copy(
            state = VmState.STOPPED,
            statusDetail = reason,
            lastError = null
        )
        appendLog("USER", "Menolak permintaan Komputer Virtual ($reason).")
    }

    // =========================================================================
    // Lifecycle VM: Provision, Start, Stop, Restart, Status, Destroy
    // =========================================================================

    /**
     * Versi fire-and-forget untuk dipanggil dari UI (tombol Mulai/Hentikan/Hapus).
     * WAJIB dipakai UI alih-alih rememberCoroutineScope: proses provisioning berjalan
     * di scope internal manager sehingga TIDAK ikut dibatalkan saat dialog ditutup
     * atau pengguna pindah tab (penyebab status macet di "Menyiapkan..." selamanya).
     */
    fun startOrProvisionVmAsync(context: Context = JarvisApp.instance) {
        scope.launch { startOrProvisionVm(context) }
    }

    fun stopVmAsync() {
        scope.launch { stopVm() }
    }

    fun destroyVmAsync() {
        scope.launch { destroyVm() }
    }

    suspend fun startOrProvisionVm(
        context: Context = JarvisApp.instance,
        distro: VmDistro = _status.value.distro,
        mode: VmMode = _status.value.mode,
        includeBrowser: Boolean = _status.value.includeBrowser,
        extraPackages: List<String> = emptyList()
    ): ToolResult = withContext(Dispatchers.IO) {
        ensurePrefsLoaded(context)

        // Guard: jangan jalankan setup ganda (mis. AI retry atau pengguna menekan tombol dua kali).
        // Dua `proot-distro install` paralel saling merusak rootfs & lock apt.
        if (!provisioningMutex.tryLock()) {
            return@withContext ToolResult(
                status = "error",
                errorCode = "PROVISIONING_IN_PROGRESS",
                message = "Penyiapan Komputer Virtual sedang berjalan. Tunggu hingga selesai — progres live tampil di tab Terminal VM."
            )
        }
        cancelRequested = false
        try {

        if (!AdbShizukuManager.isTermuxInstalled(context)) {
            val err = "Termux belum terpasang. Pasang Termux dari F-Droid terlebih dahulu untuk menjalankan Komputer Virtual Linux."
            _status.value = _status.value.copy(state = VmState.ERROR, statusDetail = "Termux belum terpasang", lastError = err)
            appendLog("SYSTEM", err, isError = true)
            return@withContext ToolResult("error", errorCode = "TERMUX_NOT_INSTALLED", message = err)
        }

        _status.value = _status.value.copy(
            state = VmState.PROVISIONING,
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser,
            isApproved = true,
            statusDetail = "Menyiapkan ${distro.displayName}...",
            lastError = null
        )
        persistConfig(context, distro, mode, includeBrowser, approved = true)
        appendLog("SYSTEM", "Memeriksa & menyiapkan ${distro.displayName} (${mode.displayName})...")

        // 1. Cek apakah rootfs sudah terpasang
        val (stRc, stOut) = invokeTermuxVmScript("status", distro, timeoutMs = 15_000L)
        if (stRc == -1 || stRc == -2) {
            _status.value = _status.value.copy(state = VmState.ERROR, statusDetail = "Gagal terhubung ke Termux", lastError = stOut)
            appendLog("SYSTEM", stOut, isError = true)
            return@withContext ToolResult("error", message = stOut)
        }

        val alreadyInstalled = stOut.contains("INSTALLED=yes")
        if (!alreadyInstalled) {
            // Unduh rootfs oleh APLIKASI sendiri (progres akurat, tidak bergantung
            // jaringan/curl Termux). Best-effort — gagal pun setup tetap lanjut.
            _status.value = _status.value.copy(statusDetail = "Menyiapkan unduhan rootfs ${distro.displayName}...")
            preDownloadRootfs(distro)
            if (cancelRequested) {
                _status.value = _status.value.copy(state = VmState.STOPPED, statusDetail = "Setup dibatalkan", lastError = null)
                appendLog("SYSTEM", "Setup dibatalkan saat unduhan rootfs.")
                return@withContext ToolResult("ok", result = "Setup Komputer Virtual dibatalkan oleh pengguna.")
            }
            _status.value = _status.value.copy(statusDetail = "Memasang ${distro.displayName} (ekstraksi + paket dasar)...")
            appendLog("SYSTEM", "Memasang rootfs ${distro.displayName} dan paket desktop/CLI...")
            appendLog("VM", "$ andra_vm.sh setup ${distro.id} ${mode.id} browser=${if (includeBrowser) "1" else "0"}")
            val (setupRc, setupOut) = invokeTermuxVmScript(
                action = "setup",
                distro = distro,
                args = listOf(mode.id, if (includeBrowser) "1" else "0", extraPackages.joinToString(" ")),
                timeoutMs = 1_200_000L,
                onPartialOutput = makeLiveLogStreamer(),
                cancellable = true
            )
            if (setupRc == -3) {
                _status.value = _status.value.copy(
                    state = VmState.STOPPED,
                    statusDetail = "Setup dibatalkan",
                    lastError = null
                )
                appendLog("SYSTEM", "Setup dibatalkan. Rootfs parsial bisa dihapus lewat tombol \"Hapus Rootfs VM\" sebelum memulai ulang.")
                return@withContext ToolResult("ok", result = "Setup Komputer Virtual dibatalkan oleh pengguna.")
            }
            if (setupRc != 0 && setupOut.isNotBlank()) appendLog("VM", setupOut.takeLast(1200), isError = true)
            if (setupRc != 0) {
                val err = "Gagal menyiapkan ${distro.displayName}: ${setupOut.takeLast(400)}"
                _status.value = _status.value.copy(state = VmState.ERROR, statusDetail = "Setup gagal", lastError = err)
                return@withContext ToolResult("error", message = err)
            }
        } else {
            appendLog("SYSTEM", "Rootfs ${distro.displayName} sudah terpasang, melanjutkan boot...")
        }

        // Pengguna sempat menekan Batalkan tepat sebelum fase boot — hormati.
        if (cancelRequested) {
            _status.value = _status.value.copy(state = VmState.STOPPED, statusDetail = "Setup dibatalkan", lastError = null)
            appendLog("SYSTEM", "Setup dibatalkan sebelum fase boot.")
            return@withContext ToolResult("ok", result = "Setup Komputer Virtual dibatalkan oleh pengguna.")
        }

        // 2. Start VM (Xvnc + Openbox + noVNC jika mode GUI)
        _status.value = _status.value.copy(statusDetail = "Menjalankan layanan Komputer Virtual...")
        appendLog("VM", "$ andra_vm.sh start ${distro.id} ${mode.id}")
        val (startRc, startOut) = invokeTermuxVmScript(
            action = "start",
            distro = distro,
            args = listOf(mode.id),
            timeoutMs = 45_000L,
            onPartialOutput = makeLiveLogStreamer()
        )
        if (startOut.isNotBlank()) appendLog("VM", startOut, isError = startRc != 0)
        if (startRc != 0) {
            val err = "Gagal menjalankan VM: $startOut"
            _status.value = _status.value.copy(state = VmState.ERROR, statusDetail = "Boot gagal", lastError = err)
            return@withContext ToolResult("error", message = err)
        }

        // Tunggu sebentar jika GUI agar noVNC siap menerima koneksi WebView
        if (mode == VmMode.DESKTOP_GUI) {
            repeat(8) {
                if (isNoVncReachable()) return@repeat
                delay(500)
            }
        }

        _status.value = _status.value.copy(
            state = VmState.RUNNING,
            distro = distro,
            mode = mode,
            includeBrowser = includeBrowser,
            isApproved = true,
            startedAt = System.currentTimeMillis(),
            statusDetail = if (mode == VmMode.DESKTOP_GUI) {
                "Aktif · Desktop live di port $DEFAULT_NOVNC_PORT"
            } else {
                "Aktif · Terminal Linux siap"
            },
            lastError = null
        )
        appendLog("SYSTEM", "Komputer Virtual aktif (${distro.displayName} · ${mode.displayName}).")

        ToolResult(
            status = "ok",
            result = "Komputer Virtual berhasil dijalankan (${distro.displayName} · ${mode.displayName}).\n" +
                if (mode == VmMode.DESKTOP_GUI) "Live Viewer noVNC: ${_status.value.novncUrl}" else "Siap menerima perintah shell melalui vm_exec."
        )

        } finally {
            provisioningMutex.unlock()
        }
    }

    suspend fun stopVm(): ToolResult = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val current = _status.value
        val (rc, out) = invokeTermuxVmScript("stop", current.distro, timeoutMs = 20_000L)
        if (out.isNotBlank()) appendLog("VM", out, isError = rc != 0)
        _status.value = current.copy(
            state = VmState.STOPPED,
            startedAt = null,
            statusDetail = "Dihentikan (0% CPU · 0 MB RAM)",
            lastError = null
        )
        appendLog("SYSTEM", "Komputer Virtual dihentikan.")
        ToolResult("ok", result = "Komputer Virtual telah dihentikan dengan bersih.")
    }

    suspend fun destroyVm(): ToolResult = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()

        // Jika setup masih berjalan: batalkan dulu, lalu tunggu loop setup selesai
        // melepas mutex (maks ~20 dtk) supaya destroy tidak balapan dengan installer.
        if (_status.value.state == VmState.PROVISIONING) {
            cancelRequested = true
            _status.value = _status.value.copy(statusDetail = "Membatalkan setup sebelum menghapus...")
            appendLog("SYSTEM", "Setup sedang berjalan — dibatalkan dulu sebelum rootfs dihapus.")
            val waitUntil = System.currentTimeMillis() + 20_000L
            while (provisioningMutex.isLocked && System.currentTimeMillis() < waitUntil) {
                delay(300)
            }
            // Pastikan tidak ada proses installer tersisa sebelum menghapus rootfs.
            sendVmKillSignal()
            delay(800)
        }

        val current = _status.value
        appendLog("SYSTEM", "Menghapus rootfs ${current.distro.displayName}...")
        val (rc, out) = invokeTermuxVmScript("destroy", current.distro, timeoutMs = 60_000L)
        if (out.isNotBlank()) appendLog("VM", out, isError = rc != 0)
        persistConfig(approved = false)
        _status.value = current.copy(
            state = VmState.STOPPED,
            isApproved = false,
            startedAt = null,
            statusDetail = "Rootfs dihapus",
            lastError = null
        )
        ToolResult("ok", result = "Komputer Virtual (${current.distro.displayName}) telah dihapus.")
    }

    suspend fun installBrowser(): ToolResult = withContext(Dispatchers.IO) {
        val guard = requireRunningVm()
        if (guard != null) return@withContext guard
        appendLog("SYSTEM", "Memasang Chromium di dalam ${_status.value.distro.displayName}...")
        appendLog("VM", "$ andra_vm.sh install_browser ${_status.value.distro.id}")
        val (rc, out) = invokeTermuxVmScript(
            "install_browser",
            _status.value.distro,
            timeoutMs = 600_000L,
            onPartialOutput = makeLiveLogStreamer()
        )
        if (rc != 0) appendLog("VM", out.takeLast(1200), isError = true)
        if (rc == 0) {
            _status.value = _status.value.copy(includeBrowser = true)
            persistConfig(includeBrowser = true)
            ToolResult("ok", result = "Chromium berhasil dipasang di Komputer Virtual.")
        } else {
            ToolResult("error", message = "Gagal memasang Chromium: ${out.takeLast(500)}")
        }
    }

    suspend fun getStatusReport(): ToolResult = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val current = _status.value
        val (rc, out) = invokeTermuxVmScript("status", current.distro, timeoutMs = 15_000L)
        val novncAlive = isNoVncReachable(current.novncPort)
        if (rc == 0 && out.contains("VNC_UP=yes") && current.state != VmState.RUNNING && current.isApproved) {
            _status.value = current.copy(
                state = VmState.RUNNING,
                statusDetail = "Aktif · Desktop live di port ${current.novncPort}"
            )
        }
        val report = buildString {
            appendLine("Status Komputer Virtual:")
            appendLine("- State: ${_status.value.state.name}")
            appendLine("- Distro: ${_status.value.distro.displayName}")
            appendLine("- Mode: ${_status.value.mode.displayName}")
            appendLine("- Persetujuan Pengguna: ${if (_status.value.isApproved) "Disetujui" else "Belum disetujui"}")
            appendLine("- noVNC Live Viewer: ${if (novncAlive) "Aktif (${_status.value.novncUrl})" else "Tidak aktif"}")
            if (out.isNotBlank()) {
                appendLine()
                appendLine("Detail Container:")
                append(out.take(1500))
            }
        }
        ToolResult("ok", result = report.trim())
    }

    // =========================================================================
    // Eksekusi Perintah, Screenshot (Vision), Inspect & OCR (Non-Vision), & GUI
    // =========================================================================

    private fun requireRunningVm(): ToolResult? {
        ensurePrefsLoaded()
        val st = _status.value
        if (!st.isApproved) {
            return ToolResult(
                status = "error",
                errorCode = "VM_APPROVAL_REQUIRED",
                message = "Komputer Virtual belum mendapat persetujuan pengguna. Panggil tool `vm_request_create` terlebih dahulu agar kartu persetujuan muncul di Chat."
            )
        }
        if (st.state != VmState.RUNNING) {
            return ToolResult(
                status = "error",
                errorCode = "VM_NOT_RUNNING",
                message = "Komputer Virtual saat ini berstatus ${st.state.name}. Panggil `vm_control` dengan {\"action\": \"start\"} atau minta pengguna menekan Mulai VM."
            )
        }
        return null
    }

    /**
     * Eksekusi perintah bash/Linux di dalam container VM terisolasi.
     * Bekerja identik untuk model Vision maupun Non-Vision.
     */
    suspend fun execInVm(
        command: String,
        workdir: String = "/root",
        timeoutSec: Int = 45,
        source: String = "AI"
    ): ToolResult = withContext(Dispatchers.IO) {
        val guard = requireRunningVm()
        if (guard != null) return@withContext guard

        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            return@withContext ToolResult("error", message = "Parameter 'command' tidak boleh kosong.")
        }

        appendLog(source, "$ $cleanCmd")
        val b64 = Base64.encodeToString(cleanCmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val timeoutMs = (timeoutSec.coerceIn(5, 300) * 1000L)
        val (rc, out) = invokeTermuxVmScript(
            action = "exec",
            distro = _status.value.distro,
            args = listOf(b64, workdir.ifBlank { "/root" }),
            timeoutMs = timeoutMs
        )
        val displayOut = out.ifBlank { "(sukses tanpa output)" }
        appendLog("VM", displayOut.take(1800), isError = rc != 0)

        if (rc == 0) {
            ToolResult("ok", result = displayOut.take(12_000))
        } else {
            ToolResult("error", errorCode = "VM_EXIT_$rc", message = "Exit $rc:\n${displayOut.take(6_000)}")
        }
    }

    /**
     * Ambil tangkapan layar X11 dari Komputer Virtual (`DISPLAY=:1`).
     * - Mengembalikan `screenshot_b64` + `screenshot_path` agar model Vision langsung melihat layar.
     * - Jika model Non-Vision memanggil ini, `AiChatService` otomatis mengonversinya menjadi
     *   daftar teks OCR ber-koordinat [x, y] + daftar jendela & tab Chromium (tanpa mengirim gambar ke API).
     */
    suspend fun captureVmScreenshot(saveName: String? = null): ToolResult = withContext(Dispatchers.IO) {
        val guard = requireRunningVm()
        if (guard != null) return@withContext guard

        if (_status.value.mode != VmMode.DESKTOP_GUI) {
            return@withContext ToolResult(
                status = "error",
                message = "VM berjalan dalam mode Terminal (CLI). Tidak ada layar GUI untuk di-screenshot — gunakan vm_exec."
            )
        }

        val dir = getScreenshotDir()
        val cleanName = saveName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")?.ifBlank { null }
            ?: "vm_${System.currentTimeMillis()}.png"
        val fileName = if (cleanName.endsWith(".png", ignoreCase = true)) cleanName else "$cleanName.png"
        val outFile = File(dir, fileName)
        val vmPath = outFile.absolutePath.replace("/storage/emulated/0", "/mnt/sdcard").replace("/sdcard", "/mnt/sdcard")

        val (rc, out) = invokeTermuxVmScript(
            action = "screenshot",
            distro = _status.value.distro,
            args = listOf(vmPath),
            timeoutMs = 20_000L
        )
        if (rc != 0 || !outFile.exists() || outFile.length() < 64L) {
            val err = "Gagal mengambil screenshot layar VM: ${out.ifBlank { "file tangkapan kosong" }}"
            appendLog("VM", err, isError = true)
            return@withContext ToolResult("error", message = err)
        }

        _lastScreenshotPath.value = outFile.absolutePath

        // Kompres ke JPEG ringan agar cepat dikirim ke AI Vision maupun OCR
        val jpegBytes = try {
            val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
            if (bmp != null) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                bmp.recycle()
                baos.toByteArray()
            } else {
                outFile.readBytes()
            }
        } catch (_: Exception) {
            outFile.readBytes()
        }

        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val (_, windowMeta) = invokeTermuxVmScript("inspect", _status.value.distro, timeoutMs = 10_000L)

        val extraMap = mutableMapOf<String, Any?>(
            "screenshot_b64" to b64,
            "screenshot_path" to outFile.absolutePath,
            "is_vm_screenshot" to true
        )

        val summary = buildString {
            appendLine("Screenshot layar Komputer Virtual berhasil diambil (${_status.value.resolution}).")
            appendLine("Tersimpan: ${outFile.absolutePath}")
            if (windowMeta.isNotBlank()) {
                appendLine()
                append(windowMeta.take(1200))
            }
        }.trim()

        appendLog("AI", "Mengambil screenshot layar VM (${outFile.name}).")
        ToolResult("ok", result = summary, extra = extraMap)
    }

    /**
     * Inspeksi layar VM berbasis TEKS TERSTRUKTUR — dirancang khusus agar model AI NON-VISION
     * dapat "melihat" dan menavigasi desktop VM & Chromium secara akurat tanpa input gambar:
     * 1. Daftar jendela X11 & jendela aktif
     * 2. Daftar tab Chromium + teks halaman + elemen klik (via Chrome DevTools Protocol :9222)
     * 3. On-device ML Kit OCR dengan koordinat piksel tengah (x, y) dari setiap baris teks di layar VM!
     */
    suspend fun inspectVmScreenText(includeOcr: Boolean = true): ToolResult = withContext(Dispatchers.IO) {
        val guard = requireRunningVm()
        if (guard != null) return@withContext guard

        val (_, meta) = invokeTermuxVmScript("inspect", _status.value.distro, timeoutMs = 12_000L)
        val sb = StringBuilder()
        sb.appendLine("[Inspeksi Layar Komputer Virtual — Mode Teks / Non-Vision]")
        if (meta.isNotBlank()) {
            sb.appendLine(meta.take(2000))
        }

        if (includeOcr && _status.value.mode == VmMode.DESKTOP_GUI) {
            val shotRes = captureVmScreenshot("vm_inspect_latest.png")
            val b64 = shotRes.extra["screenshot_b64"] as? String
            if (!b64.isNullOrBlank()) {
                val ocrReport = ImageDecodeManager.extractOcrWithCoordinatesFromBase64(b64)
                sb.appendLine()
                sb.appendLine("=== TEKS LAYAR VM + KOORDINAT KLIK (OCR) ===")
                ocrReport.onSuccess { report ->
                    sb.appendLine(report.formatForAgent(90))
                }.onFailure { err ->
                    sb.appendLine("OCR gagal: ${err.message}")
                }
            }
        }

        sb.appendLine()
        sb.append("Tip klik presisi: gunakan vm_desktop_action {\"action\":\"click\",\"x\":...,\"y\":...} atau {\"action\":\"click_text\",\"text\":\"...\"}.")
        ToolResult("ok", result = sb.toString().trim())
    }

    /**
     * Kontrol GUI penuh di dalam VM (mouse, keyboard, scroll, buka Chromium/Terminal/App,
     * serta `click_text` yang otomatis mencari koordinat teks via OCR untuk model Non-Vision!).
     */
    suspend fun performDesktopAction(
        action: String,
        x: Int? = null,
        y: Int? = null,
        text: String? = null,
        key: String? = null,
        url: String? = null,
        direction: String? = null,
        steps: Int = 4,
        appCommand: String? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val guard = requireRunningVm()
        if (guard != null) return@withContext guard

        if (_status.value.mode != VmMode.DESKTOP_GUI) {
            return@withContext ToolResult("error", message = "Aksi desktop GUI memerlukan mode Desktop GUI.")
        }

        val act = action.lowercase().trim()
        when (act) {
            "click", "double_click", "right_click", "move" -> {
                val tx = x ?: return@withContext ToolResult("error", message = "Parameter 'x' dan 'y' wajib diisi untuk $act.")
                val ty = y ?: return@withContext ToolResult("error", message = "Parameter 'x' dan 'y' wajib diisi untuk $act.")
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf(act, tx.toString(), ty.toString()), 15_000L)
                appendLog("AI", "GUI $act ($tx, $ty)")
                if (rc == 0) ToolResult("ok", result = out.ifBlank { "Aksi $act di ($tx, $ty) selesai." })
                else ToolResult("error", message = out)
            }

            "click_text" -> {
                val query = text?.trim().orEmpty()
                if (query.isBlank()) {
                    return@withContext ToolResult("error", message = "Parameter 'text' wajib diisi untuk click_text.")
                }
                val shot = captureVmScreenshot("vm_click_text.png")
                val b64 = shot.extra["screenshot_b64"] as? String
                    ?: return@withContext ToolResult("error", message = "Gagal mengambil layar VM untuk mencari teks '$query'.")
                val report = ImageDecodeManager.extractOcrWithCoordinatesFromBase64(b64).getOrNull()
                    ?: return@withContext ToolResult("error", message = "OCR layar VM gagal dijalankan.")
                val match = report.elements.firstOrNull { it.text.equals(query, ignoreCase = true) }
                    ?: report.elements.firstOrNull { it.text.contains(query, ignoreCase = true) }
                    ?: return@withContext ToolResult(
                        "error",
                        message = "Teks '$query' tidak ditemukan di layar VM.\n${report.formatForAgent(35)}"
                    )
                val (rc, out) = invokeTermuxVmScript(
                    "gui",
                    _status.value.distro,
                    listOf("click", match.centerX.toString(), match.centerY.toString()),
                    15_000L
                )
                appendLog("AI", "GUI click_text '${match.text}' @ (${match.centerX}, ${match.centerY})")
                if (rc == 0) {
                    ToolResult("ok", result = "Berhasil klik teks \"${match.text}\" di koordinat (${match.centerX}, ${match.centerY}).")
                } else {
                    ToolResult("error", message = out)
                }
            }

            "scroll" -> {
                val dir = if (direction.equals("up", ignoreCase = true)) "up" else "down"
                val st = steps.coerceIn(1, 20)
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("scroll", dir, st.toString()), 15_000L)
                appendLog("AI", "GUI scroll $dir x$st")
                if (rc == 0) ToolResult("ok", result = out.ifBlank { "Scroll $dir ($st langkah) selesai." })
                else ToolResult("error", message = out)
            }

            "type" -> {
                val txt = text ?: return@withContext ToolResult("error", message = "Parameter 'text' wajib diisi untuk type.")
                val b64 = Base64.encodeToString(txt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("type", b64), 15_000L)
                appendLog("AI", "GUI type (${txt.length} karakter)")
                if (rc == 0) ToolResult("ok", result = "Berhasil mengetik teks di jendela aktif VM.")
                else ToolResult("error", message = out)
            }

            "key" -> {
                val k = key?.trim().orEmpty()
                if (k.isBlank()) return@withContext ToolResult("error", message = "Parameter 'key' wajib diisi (contoh: Return, BackSpace, ctrl+c, ctrl+l, alt+F4).")
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("key", k), 15_000L)
                appendLog("AI", "GUI key '$k'")
                if (rc == 0) ToolResult("ok", result = "Tombol '$k' dikirim ke VM.")
                else ToolResult("error", message = out)
            }

            "open_browser" -> {
                val targetUrl = url?.trim()?.ifBlank { "https://www.google.com" } ?: "https://www.google.com"
                val b64 = Base64.encodeToString(targetUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("open_browser", b64), 20_000L)
                appendLog("AI", "Membuka Chromium di VM -> $targetUrl")
                if (rc == 0) ToolResult("ok", result = out.ifBlank { "Chromium dibuka ke $targetUrl (CDP aktif di port $DEFAULT_CDP_PORT)." })
                else ToolResult("error", message = out)
            }

            "open_terminal" -> {
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("open_terminal"), 15_000L)
                appendLog("AI", "Membuka terminal XTerm di desktop VM")
                if (rc == 0) ToolResult("ok", result = out.ifBlank { "Terminal desktop dibuka." })
                else ToolResult("error", message = out)
            }

            "open_app" -> {
                val cmd = appCommand?.trim().orEmpty()
                if (cmd.isBlank()) return@withContext ToolResult("error", message = "Parameter 'app' wajib diisi (misal: xterm, code, firefox).")
                val b64 = Base64.encodeToString(cmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val (rc, out) = invokeTermuxVmScript("gui", _status.value.distro, listOf("open_app", b64), 20_000L)
                appendLog("AI", "Meluncurkan aplikasi VM: $cmd")
                if (rc == 0) ToolResult("ok", result = out.ifBlank { "Aplikasi '$cmd' diluncurkan di desktop VM." })
                else ToolResult("error", message = out)
            }

            else -> ToolResult(
                "error",
                message = "Aksi desktop '$action' tidak dikenali. Pilih: click, double_click, right_click, click_text, move, scroll, type, key, open_browser, open_terminal, open_app."
            )
        }
    }
}
