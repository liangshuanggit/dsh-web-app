package com.liangshuang.dshweb

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * dsh 网页版：内嵌 Firefox 的 Gecko 引擎（GeckoView），打开云端 dsh web。
 *
 * 登录方式：dsh web 用一次性 token 链接登录，登录后引擎的 cookie 保持
 * 会话。首次使用粘贴登录链接打开一次即可；之后直接进。服务重启导致
 * 链接更换时，在菜单里选「更新登录链接」粘贴新链接。
 */
class MainActivity : Activity() {

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var progressBar: ProgressBar
    private lateinit var formView: LinearLayout
    private lateinit var linkInput: EditText
    private lateinit var formHint: TextView

    private var canGoBack = false
    private var currentUrl: String? = null
    private var expectingTokenCheck = false

    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    private fun hasLink(): Boolean = prefs.getBoolean(KEY_HAS_LINK, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupSession()

        if (hasLink()) {
            showWeb()
            session.loadUri(baseUrl() + "/")
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

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(geckoView)

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
                session.loadUri(baseUrl() + "/")
            }
        }
        form.addView(directButton)

        return form
    }

    private fun showForm(message: String) {
        formHint.text = message
        prefillFromClipboard()
        formView.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
    }

    private fun showWeb() {
        formView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
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
        expectingTokenCheck = raw.contains("token=")
        showWeb()
        session.loadUri(raw)
    }

    // ---------- Gecko session ----------

    private fun setupSession() {
        session = GeckoSession()
        session.open(GeckoRuntime.getDefault(this))
        session.settings.allowJavascript = true

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                currentUrl = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "about" ||
                    scheme == "blob" || scheme == "data" || scheme == "file"
                ) {
                    return null // 交给引擎正常加载
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.uri)))
                } catch (e: Exception) {
                    // 没有能打开的程序就忽略
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Toast.makeText(
                    this@MainActivity,
                    "页面加载失败，检查网络后点菜单里的刷新",
                    Toast.LENGTH_SHORT
                ).show()
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progressBar.visibility = View.GONE
                if (expectingTokenCheck) {
                    expectingTokenCheck = false
                    val u = currentUrl
                    // token 有效时服务端会跳转到不带 token 的地址；
                    // 还停在带 token 的地址，说明没登录进去。
                    if (u != null && u.contains("token=")) {
                        showForm("没能登录进去（链接可能已过期，或云端服务暂时不可用）。把我发你的最新登录链接粘贴到下面再试。")
                    }
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                // 引擎不直接渲染的响应（一般是文件下载）交给外部程序打开。
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(response.uri)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "没有能打开这个下载的程序", Toast.LENGTH_SHORT).show()
                }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                pendingFilePrompt = prompt
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFileResult = result
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = prompt.mimeTypes?.firstOrNull { it.contains("/") } ?: "*/*"
                    if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_FILE_CHOOSER)
                    result
                } catch (e: Exception) {
                    pendingFilePrompt = null
                    pendingFileResult = null
                    GeckoResult.fromValue(prompt.dismiss())
                }
            }

            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(prompt.message ?: "")
                    .setPositiveButton("好") { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(prompt.message ?: "")
                    .setPositiveButton("确定") { _, _ ->
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }
                    .setNegativeButton("取消") { _, _ ->
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    .setOnCancelListener {
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    .show()
                return result
            }
        }

        geckoView.setSession(session)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val prompt = pendingFilePrompt
            val result = pendingFileResult
            pendingFilePrompt = null
            pendingFileResult = null
            if (prompt == null || result == null) return
            val uris = mutableListOf<Uri>()
            if (resultCode == RESULT_OK && data != null) {
                data.data?.let { uris.add(it) }
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { uris.add(it) }
                    }
                }
            }
            if (uris.isEmpty()) {
                result.complete(prompt.dismiss())
            } else {
                result.complete(prompt.confirm(this, uris.toTypedArray()))
            }
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
                if (geckoView.visibility == View.VISIBLE) session.reload()
                true
            }
            MENU_OPEN_BROWSER -> {
                val url = currentUrl ?: (baseUrl() + "/")
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
        if (keyCode == KeyEvent.KEYCODE_BACK && geckoView.visibility == View.VISIBLE && canGoBack) {
            session.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
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
