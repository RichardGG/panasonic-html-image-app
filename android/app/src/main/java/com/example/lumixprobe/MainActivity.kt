package com.example.lumixprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Lumix probe + basic remote: joins the camera's (internet-less) Wi-Fi network,
 * handshakes, and offers zoom / shutter / video controls plus a full info dump
 * that is saved to the Downloads folder.
 */
class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val logBuffer = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var network: Network? = null
    @Volatile private var connected = false
    private val connectLock = Any()

    /** Camera commands are sent one at a time, in order (so "start" always precedes "stop"). */
    private val cmdExecutor = Executors.newSingleThreadExecutor()
    private val zoomToken = AtomicInteger()

    private data class Reply(val code: Int, val type: String, val body: String)

    // Handshake identities taken from requests.html in this repo.
    private val handshakes = listOf(
        "4D454930-0100-1000-8001-020A0005A9CF",
        "4D454930-0100-1000-8001-02FA000430C6",
        "4D454930-0100-1000-8000-F02765BACACE"
    )

    // Queries saved by "Dump info".
    private val dumpQueries = listOf(
        "State" to "mode=getstate",
        "Capability" to "mode=getinfo&type=capability",
        "All menu" to "mode=getinfo&type=allmenu",
        "Current menu" to "mode=getinfo&type=curmenu",
        "Lens" to "mode=getinfo&type=lens"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("Lumix Probe ready. Join the camera's Wi-Fi, then tap Connect.")
    }

    override fun onDestroy() {
        cmdExecutor.shutdown()
        releaseNetworkRequest()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        ipField = EditText(this).apply {
            setText("192.168.54.1")
            hint = "Camera IP"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(ipField, matchWrap())

        root.addView(row(
            button("Connect") { runConnect() },
            button("Dump info") { runDump() }
        ), matchWrap())

        root.addView(label("Zoom - go to end (as fast as possible)"), matchWrap())
        root.addView(row(
            button("Min zoom (wide)") { autoZoom(tele = false) },
            button("Max zoom (tele)") { autoZoom(tele = true) }
        ), matchWrap())

        root.addView(label("Zoom - hold to move, release to stop"), matchWrap())
        root.addView(row(
            holdButton("Wide (hold)", "wide-fast", "zoomstop"),
            holdButton("Tele (hold)", "tele-fast", "zoomstop")
        ), matchWrap())

        root.addView(label("Shutter (hold = burst) / video"), matchWrap())
        root.addView(row(
            holdButton("Shoot", "capture", "capture_cancel"),
            button("Rec start") { sendCmd("video_recstart") },
            button("Rec stop") { sendCmd("video_recstop") }
        ), matchWrap())

        root.addView(row(
            button("Copy") { copyLog() },
            button("Share") { shareLog() },
            button("Clear") { clearLog() }
        ), matchWrap())

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, dp(8), 0, 0)
    }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }

    /** Sends [down] when pressed and [up] when released (or the touch is cancelled). */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(text: String, down: String, up: String) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        cancelAutoZoom()
                        sendCmd(down)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        sendCmd(up)
                    }
                }
                true
            }
        }

    // ---------------------------------------------------------------- logging

    private fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        synchronized(logBuffer) { logBuffer.append(line) }
        runOnUiThread {
            logView.append(line)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        synchronized(logBuffer) { logBuffer.setLength(0) }
        logView.text = ""
    }

    private fun logText(): String = synchronized(logBuffer) { logBuffer.toString() }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Lumix Probe log", logText()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText().takeLast(200_000))
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    // ---------------------------------------------------------------- networking

    private fun releaseNetworkRequest() {
        val cb = netCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // already unregistered
        }
        netCallback = null
    }

    /**
     * Ask Android for the Wi-Fi network explicitly. The camera's AP has no internet, so
     * Android would not normally route app traffic to it while mobile data is available;
     * requesting it (without the INTERNET capability) lets us talk to the camera over
     * Wi-Fi while everything else keeps using mobile data.
     */
    private fun findWifiNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)

        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            log("Network seen: $n type=$kind internet=$internet")
        }

        releaseNetworkRequest()
        val latch = CountDownLatch(1)
        var found: Network? = null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found = network
                latch.countDown()
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb)
        latch.await(10, TimeUnit.SECONDS)
        return found
    }

    private fun httpGet(net: Network?, url: String): Reply {
        val u = URL(url)
        val conn = (if (net != null) net.openConnection(u) else u.openConnection())
            as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Reply(code, conn.contentType ?: "", body)
        } finally {
            conn.disconnect()
        }
    }

    private fun baseUrl() = "http://${ipField.text.toString().trim()}/cam.cgi"

    // ---------------------------------------------------------------- connect

    private fun runConnect() {
        val base = baseUrl()
        thread(name = "connect") {
            try {
                connect(base)
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Finds the Wi-Fi network, handshakes and starts the remote session. */
    private fun connect(base: String): Boolean = synchronized(connectLock) {
        connected = false
        log("=== Connecting to $base ===")

        log("Looking for Wi-Fi network...")
        val net = findWifiNetwork()
        if (net == null) {
            log("No Wi-Fi network returned within 10s. Falling back to default routing " +
                "(likely to fail if mobile data is on). Is the phone joined to the camera?")
        } else {
            log("Using Wi-Fi network $net")
        }
        network = net

        var paired = false
        for ((i, uuid) in handshakes.withIndex()) {
            val name = URLEncoder.encode("LumixProbe", "UTF-8")
            val url = "$base?mode=accctrl&type=req_acc&value=$uuid&value2=$name"
            log("Handshake ${i + 1}")
            val r = try {
                httpGet(net, url)
            } catch (e: Exception) {
                log("  failed: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            log("  HTTP ${r.code} ${r.body.trim().take(300)}")
            if (r.body.trim().startsWith("ok")) {
                paired = true
                break
            }
        }
        if (!paired) {
            log("No handshake succeeded. Check Wi-Fi and the camera's Wi-Fi screen.")
            return false
        }

        try {
            val r = httpGet(net, "$base?mode=camcmd&value=recmode")
            log("Start session (recmode): HTTP ${r.code} ${r.body.replace("\n", "").take(200)}")
        } catch (e: Exception) {
            log("  recmode failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        connected = true
        log("=== Connected ===")
        return true
    }

    // ---------------------------------------------------------------- commands

    private fun notConnected() = log("Not connected - tap Connect first.")

    /** Fire-and-forget camcmd, executed in order on a background thread. */
    private fun sendCmd(value: String) {
        if (!connected) {
            notConnected()
            return
        }
        val base = baseUrl()
        val net = network
        cmdExecutor.execute {
            try {
                val r = httpGet(net, "$base?mode=camcmd&value=$value")
                log("$value -> HTTP ${r.code} ${r.body.replace("\n", "").trim().take(150)}")
            } catch (e: Exception) {
                log("$value failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun cancelAutoZoom() {
        zoomToken.incrementAndGet()
    }

    /**
     * Zoom all the way to one end at the fastest speed. The camera stops by itself at the
     * end of its range; we also poll the lens info and send zoomstop once it stops changing
     * (or after a safety timeout). Raw lens samples are logged so we can learn which
     * field tracks the zoom position.
     */
    private fun autoZoom(tele: Boolean) {
        if (!connected) {
            notConnected()
            return
        }
        val base = baseUrl()
        val net = network
        val token = zoomToken.incrementAndGet()
        val cmd = if (tele) "tele-fast" else "wide-fast"

        thread(name = "autozoom") {
            try {
                val t0 = SystemClock.elapsedRealtime()
                log("Auto zoom: $cmd")
                val start = httpGet(net, "$base?mode=camcmd&value=$cmd")
                log("  $cmd -> ${start.body.replace("\n", "").trim().take(100)}")

                var last = ""
                var same = 0
                while (token == zoomToken.get() &&
                    SystemClock.elapsedRealtime() - t0 < 10_000) {
                    Thread.sleep(250)
                    val lens = httpGet(net, "$base?mode=getinfo&type=lens").body.trim()
                    if (lens != last) {
                        log("  lens: $lens")
                        last = lens
                        same = 0
                    } else {
                        same++
                    }
                    if (same >= 16) break // unchanged for ~4s
                }

                if (token == zoomToken.get()) {
                    val stop = httpGet(net, "$base?mode=camcmd&value=zoomstop")
                    log("  zoomstop -> ${stop.body.replace("\n", "").trim().take(100)} " +
                        "(${SystemClock.elapsedRealtime() - t0} ms)")
                } else {
                    log("  auto zoom superseded")
                }
            } catch (e: Exception) {
                log("Auto zoom failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- dump

    private fun runDump() {
        val base = baseUrl()
        thread(name = "dump") {
            try {
                if (!connected && !connect(base)) return@thread
                val net = network
                val out = StringBuilder()
                out.append("Lumix dump ${Date()}\ncamera $base\n\n")
                for ((label, query) in dumpQueries) {
                    val url = "$base?$query"
                    log("Dump: $label")
                    out.append("===== $label =====\nGET $url\n")
                    try {
                        val r = httpGet(net, url)
                        out.append("HTTP ${r.code} [${r.type}] ${r.body.length} chars\n")
                        out.append(r.body.trim()).append("\n\n")
                        log("  HTTP ${r.code}, ${r.body.length} chars")
                    } catch (e: Exception) {
                        out.append("FAILED: ${e.javaClass.simpleName}: ${e.message}\n\n")
                        log("  failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                val name = saveDump(out.toString())
                log("Saved full dump to Downloads/$name (${out.length} chars)")
            } catch (e: Exception) {
                log("Dump ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun saveDump(text: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "lumix-dump-$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return name
    }
}
