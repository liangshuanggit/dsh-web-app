package com.liangshuang.dshweb

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * dsh 网页版：一个薄壳 WebView app，用来打开云端部署的 dsh web。
 *
 * 登录方式：dsh web 用一次性 token 链接登录，登录后 cookie 保持会话。
 * 首次使用粘贴登录链接打开一次即可；之后直接进。服务重启导致链接
 * 更换时，在菜单里选「更新登录链接」粘贴新链接。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var formView: LinearLayout
    private lateinit var linkInput: EditText
    private lateinit var formHint: TextView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    private fun hasLink(): Boolean = prefs.getBoolean(KEY_HAS_LINK, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupWebView()

        if (hasLink()) {
            showWeb()
            webView.loadUrl(baseUrl() + "/")
        } else {
            showForm("把我发你的 dsh 登录链接粘贴到下面，打开一次就会记住登录。")
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            visibility = View.GONE
        }
        root.addView(progressBar)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(webView)

        formView = buildForm()
        frame.addView(formView)

        root.addView(frame)
        setContentView(root)
    }

    private fun buildForm(): LinearLayout {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "dsh 网页版"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        form.addView(title)

        formHint = TextView(this).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        form.addView(formHint)

        linkInput = EditText(this).apply {
            hint = "粘贴登录链接（https://…?token=…）"
            isSingleLine = false
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        form.addView(linkInput)

        val openButton = Button(this).apply {
            text = "打开 dsh"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener { openFromInput() }
        }
        form.addView(openButton)

        val directButton = Button(this).apply {
            text = "已登录过，直接打开"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                showWeb()
                webView.loadUrl(baseUrl() + "/")
            }
        }
        form.addView(directButton)

        return form
    }

    private fun showForm(message: String) {
        formHint.text = message
        prefillFromClipboard()
        formView.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun showWeb() {
        formView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun prefillFromClipboard() {
        if (linkInput.text.isNotEmpty()) return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString()?.trim() else null
        if (!text.isNullOrEmpty() && (text.contains("token=") || text.contains("railway.app"))) {
            linkInput.setText(text)
        }
    }

    private fun openFromInput() {
        val raw = linkInput.text.toString().trim()
        val uri = Uri.parse(raw)
        if (raw.isEmpty() || uri.scheme == null || uri.host == null) {
            Toast.makeText(this, "链接不对，请粘贴完整的登录链接", Toast.LENGTH_SHORT).show()
            return
        }
        val base = "${uri.scheme}://${uri.authority}"
        prefs.edit()
            .putString(KEY_BASE_URL, base)
            .putBoolean(KEY_HAS_LINK, true)
            .apply()
        showWeb()
        webView.loadUrl(raw)
    }

    // ---------- WebView ----------

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "http" || url.scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && errorResponse?.statusCode == 401) {
                    // dsh 服务重启后旧登录失效，需要新链接。
                    showForm("登录已失效（云端服务重启过）。把我发你的新登录链接粘贴到下面，再打开一次。")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@MainActivity,
                        "页面加载失败，检查网络后点菜单里的刷新",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 先把上一个未完成的选择回调取消掉，再接新的。
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "选择文件"),
                        REQUEST_FILE_CHOOSER
                    )
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "没有能打开这个下载的程序", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_CHOOSER) {
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            filePathCallback = null
        }
    }

    // ---------- Menu / keys / lifecycle ----------

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_UPDATE_LINK, 0, "更新登录链接")
        menu?.add(0, MENU_REFRESH, 1, "刷新")
        menu?.add(0, MENU_OPEN_BROWSER, 2, "在浏览器打开")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_UPDATE_LINK -> {
                linkInput.text.clear()
                showForm("把我发你的新登录链接粘贴到下面，打开一次就会记住登录。")
                true
            }
            MENU_REFRESH -> {
                if (webView.visibility == View.VISIBLE) webView.reload()
                true
            }
            MENU_OPEN_BROWSER -> {
                val url = webView.url ?: (baseUrl() + "/")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "dsh_web"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_HAS_LINK = "has_link"
        private const val DEFAULT_BASE_URL = "https://dsh-web-production-1ffc704c.up.railway.app"
        private const val REQUEST_FILE_CHOOSER = 1001
        private const val MENU_UPDATE_LINK = 1
        private const val MENU_REFRESH = 2
        private const val MENU_OPEN_BROWSER = 3
    }
}
