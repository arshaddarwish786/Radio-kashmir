package com.radiokashmir.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: Triple<String, String, String>? = null
    private var currentTitle = ""
    private var lastError: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0e1a1c"))
        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Keep the page clear of the status bar and navigation bar (Android 15 draws edge to edge).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Wadi")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pushState()
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = pushState()
                override fun onPlayerError(error: PlaybackException) {
                    lastError = "This station isn't answering right now."
                    pushState()
                }
            })
            c.mediaMetadata.title?.let { currentTitle = it.toString() }
            pending?.let { (u, t, s) -> startStream(u, t, s) }
            pending = null
            pushState()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Go to the home screen instead of closing, so the radio keeps playing.
        moveTaskToBack(true)
    }

    private fun startStream(url: String, title: String, subtitle: String) {
        val c = controller
        if (c == null) {
            pending = Triple(url, title, subtitle)
            return
        }
        lastError = null
        currentTitle = title
        val item = MediaItem.Builder()
            .setMediaId(url)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        pushState()
    }

    private fun pushState() {
        val c = controller
        val state = when {
            lastError != null -> "error"
            c == null || c.mediaItemCount == 0 -> "stopped"
            c.isPlaying -> "playing"
            c.playbackState == Player.STATE_BUFFERING && c.playWhenReady -> "buffering"
            c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED -> "buffering"
            c.playbackState == Player.STATE_READY -> "paused"
            else -> "stopped"
        }
        val json = JSONObject()
            .put("state", state)
            .put("id", c?.currentMediaItem?.mediaId ?: "")
            .put("title", currentTitle)
            .put("msg", lastError ?: "")
        web.post { web.evaluateJavascript("window.onNative && window.onNative($json)", null) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun play(url: String, title: String, subtitle: String) {
            runOnUiThread { startStream(url, title, subtitle) }
        }

        @JavascriptInterface
        fun toggle() {
            runOnUiThread {
                controller?.let { if (it.isPlaying) it.pause() else { lastError = null; it.prepare(); it.play() } }
                pushState()
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                controller?.let { it.stop(); it.clearMediaItems() }
                lastError = null
                pushState()
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Stream link", text))
            }
        }
    }
}
