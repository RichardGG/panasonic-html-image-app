package com.example.lumixprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.text.InputType
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Minimal Lumix probe: joins the camera's (internet-less) Wi-Fi network, performs the
 * handshake, starts a remote session and dumps everything the camera reports about
 * itself (state, capability, menus) into an on-screen log that can be copied/shared.
 */
class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var probeButton: Button
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val logBuffer = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private data class Reply(val code: Int, val type: String, val body: String)

    // Handshake identities taken from requests.html in this repo.
    private val handshakes = listOf(
        "4D454930-0100-1000-8001-020A0005A9CF",
        "4D454930-0100-1000-8001-02FA000430C6",
        "4D454930-0100-1000-8000-F02765BACACE"
    )

    // Queries run after a successful handshake: label to cam.cgi query string.
    private val probes = listOf(
        "Start session (recmode)" to "mode=camcmd&value=recmode",
        "State" to "mode=getstate",
        "Capability" to "mode=getinfo&type=capability",
        "All menu" to "mode=getinfo&type=allmenu",
        "Current menu" to "mode=getinfo&type=curmenu",
        "Lens" to "mode=getinfo&type=lens"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("Lumix Probe ready. Join the camera's Wi-Fi, then tap 'Connect & probe'.")
    }

    override fun onDestroy() {
        releaseNetworkRequest()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

        probeButton = Button(this).apply {
            text = "Connect & probe"
            setOnClickListener { runProbe() }
        }
        root.addView(probeButton, matchWrap())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("Copy") { copyLog() }, weighted())
        row.addView(smallButton("Share") { shareLog() }, weighted())
        row.addView(smallButton("Clear") { clearLog() }, weighted())
        root.addView(row, matchWrap())

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

    private fun weighted() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun smallButton(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

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

    private fun httpGet(network: Network?, url: String): Reply {
        val u = URL(url)
        val conn = (if (network != null) network.openConnection(u) else u.openConnection())
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

    // ---------------------------------------------------------------- probe

    private fun runProbe() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter the camera IP", Toast.LENGTH_SHORT).show()
            return
        }
        probeButton.isEnabled = false
        thread(name = "probe") {
            try {
                probe(ip)
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runOnUiThread { probeButton.isEnabled = true }
            }
        }
    }

    private fun probe(ip: String) {
        log("=== Probe started, camera $ip ===")

        log("Looking for Wi-Fi network...")
        val network = findWifiNetwork()
        if (network == null) {
            log("No Wi-Fi network returned within 10s. Falling back to default routing " +
                "(likely to fail if mobile data is on). Is the phone joined to the camera?")
        } else {
            log("Using Wi-Fi network $network")
        }

        val base = "http://$ip/cam.cgi"

        // 1. Handshake
        var paired = false
        for ((i, uuid) in handshakes.withIndex()) {
            val name = URLEncoder.encode("LumixProbe", "UTF-8")
            val url = "$base?mode=accctrl&type=req_acc&value=$uuid&value2=$name"
            log("Handshake ${i + 1}: GET $url")
            val r = try {
                httpGet(network, url)
            } catch (e: Exception) {
                log("  failed: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            log("  HTTP ${r.code} [${r.type}] ${r.body.trim().take(300)}")
            if (r.body.trim().startsWith("ok")) {
                paired = true
                break
            }
        }
        if (!paired) {
            log("No handshake succeeded; stopping. Check Wi-Fi and the camera's Wi-Fi screen.")
            return
        }
        log("Handshake OK.")

        // 2. Session start + information dump
        for ((label, query) in probes) {
            val url = "$base?$query"
            log("--- $label: GET $url")
            val r = try {
                httpGet(network, url)
            } catch (e: Exception) {
                log("  failed: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            log("  HTTP ${r.code} [${r.type}] ${r.body.length} chars")
            val body = r.body.trim()
            log(body.take(20_000) + if (body.length > 20_000) "\n...[truncated]" else "")
            if (body.startsWith("<")) summarize(label, body)
        }
        log("=== Probe finished ===")
    }

    /** Best-effort overview of an XML reply: distinct element names and id="..." values. */
    private fun summarize(label: String, xml: String) {
        val tags = Regex("<([A-Za-z_][\\w.-]*)").findAll(xml).map { it.groupValues[1] }
            .distinct().toList()
        val ids = Regex("\\bid=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }
            .distinct().toList()
        log("  [summary of $label] elements (${tags.size}): ${tags.take(40).joinToString(", ")}")
        if (ids.isNotEmpty()) {
            log("  [summary of $label] ids (${ids.size}): ${ids.take(120).joinToString(", ")}")
        }
    }
}
