package com.dache.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 大车 Android 原生壳：单 Activity + WebView，加载仓库内正式版网页 bundle。
 *
 * 网页 bundle（../web/）在构建时由 Gradle 任务 syncWebAssets 同步进 APK 的 assets/，
 * 运行时通过 WebViewAssetLoader 以 https://appassets.androidplatform.net/ 暴露给 WebView。
 * 密码门与同步逻辑在网页层，壳只提供加密存储（DacheSecure）与版本/更新/外部链接（DacheNative）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Dache"
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val ASSET_URL = "https://appassets.androidplatform.net/index.html"
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/1binbinbin999-cloud/dache/main/version.json"
        private const val UPDATE_CHECK_DELAY_MS = 2000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_TIMEOUT_MS = 30_000
        private const val SECURE_PREFS_FILE = "dache_secure_prefs"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    /** 更新检查进行中标志：启动自动检查与网页层 checkUpdate() 同时触发时只跑一次。 */
    private val updateChecking = AtomicBoolean(false)

    /**
     * 加密存储（MasterKey AES256_GCM；键 AES256_SIV，值 AES256_GCM）。
     * 初始化失败时为 null，桥方法随之静默无操作并记日志，不让壳在启动时直接崩掉。
     */
    private var securePrefs: SharedPreferences? = null

    @SuppressLint("SetJavaScriptEnabled", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        securePrefs = createSecurePrefs()

        // assets/ 根目录映射到域名根路径：assets/index.html 就是
        // https://appassets.androidplatform.net/index.html，assets/ 子目录原样对应。
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 本地 bundle 不需要 file:// 访问，按官方示例全部关掉。
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    // 站内（asset 域名）留在 WebView 里；站外 http/https 交系统浏览器。
                    if (uri.scheme == "https" && uri.host == ASSET_DOMAIN) return false
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        openExternal(uri.toString())
                        return true
                    }
                    return false
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(SecureBridge(), "DacheSecure")
            addJavascriptInterface(NativeBridge(), "DacheNative")
        }
        setContentView(webView)
        webView.loadUrl(ASSET_URL)

        // 返回键：WebView 能后退就后退，否则走系统默认（退出应用）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 启动 2 秒后跑一次更新检查（后台线程，不阻塞 WebView 加载）。
        Handler(Looper.getMainLooper()).postDelayed(
            { checkForUpdate() },
            UPDATE_CHECK_DELAY_MS
        )
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 加密存储
    // ------------------------------------------------------------------

    private fun createSecurePrefs(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences 初始化失败", e)
            null
        }
    }

    /**
     * window.DacheSecure：同步透传存储，键名由网页层定（dache.vault、dache.token），壳不解析键名。
     * 网页层用 await 调它，同步返回没问题（@JavascriptInterface 方法本身不在主线程跑）。
     */
    inner class SecureBridge {
        @JavascriptInterface
        fun getItem(key: String): String? {
            return try {
                securePrefs?.getString(key, null)
            } catch (e: Exception) {
                Log.e(TAG, "DacheSecure.getItem 失败", e)
                null
            }
        }

        @JavascriptInterface
        fun setItem(key: String, value: String) {
            try {
                securePrefs?.edit()?.putString(key, value)?.apply()
            } catch (e: Exception) {
                Log.e(TAG, "DacheSecure.setItem 失败", e)
            }
        }

        @JavascriptInterface
        fun removeItem(key: String) {
            try {
                securePrefs?.edit()?.remove(key)?.apply()
            } catch (e: Exception) {
                Log.e(TAG, "DacheSecure.removeItem 失败", e)
            }
        }
    }

    /** window.DacheNative：版本、更新检查、外部链接。 */
    inner class NativeBridge {
        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun checkUpdate() {
            checkForUpdate()
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            openExternal(url)
        }
    }

    // ------------------------------------------------------------------
    // 更新检查与安装
    // ------------------------------------------------------------------

    private fun checkForUpdate() {
        if (!updateChecking.compareAndSet(false, true)) return
        Thread {
            try {
                val latest = fetchLatestVersion()
                if (latest != null && compareVersions(latest.version, BuildConfig.VERSION_NAME) > 0) {
                    runOnUiThread { showUpdateDialog(latest) }
                }
            } catch (e: Exception) {
                // version.json 读不到 / 解析失败：静默，不影响使用。
                Log.w(TAG, "更新检查失败（静默）", e)
            } finally {
                updateChecking.set(false)
            }
        }.start()
    }

    private data class LatestVersion(val version: String, val notes: String)

    /** 读 version.json（no-store）。读不到、非 200、无 version 字段时返回 null。 */
    private fun fetchLatestVersion(): LatestVersion? {
        val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("version", "").trim()
            if (version.isEmpty()) return null
            LatestVersion(version, json.optString("notes", "").trim())
        } finally {
            connection.disconnect()
        }
    }

    /** 按点分段比数字：1.10.0 > 1.9.9；缺段按 0 算；前面的 "v" 忽略；非数字段按 0 算。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".")
        val pb = b.trim().removePrefix("v").split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun showUpdateDialog(latest: LatestVersion) {
        val message = latest.notes.ifEmpty { "发现新版本 v${latest.version}，是否现在更新？" }
        AlertDialog.Builder(this)
            .setTitle("有新版 v${latest.version}")
            .setMessage(message)
            .setPositiveButton("更新") { _, _ -> downloadAndInstall(latest.version) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun downloadAndInstall(version: String) {
        Thread {
            try {
                val apkUrl =
                    "https://github.com/1binbinbin999-cloud/dache/releases/download/v$version/dache-$version.apk"
                val dir = File(cacheDir, "dache-update").apply { mkdirs() }
                val apkFile = File(dir, "dache-$version.apk")
                val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = DOWNLOAD_TIMEOUT_MS
                    useCaches = false
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "APK 下载失败：HTTP ${connection.responseCode}")
                        return@Thread
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(apkFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                // 下载失败：静默，不影响使用。
                Log.w(TAG, "APK 下载失败（静默）", e)
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "拉起安装失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 外部链接
    // ------------------------------------------------------------------

    private fun openExternal(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有应用能打开链接：$url", e)
        }
    }
}
