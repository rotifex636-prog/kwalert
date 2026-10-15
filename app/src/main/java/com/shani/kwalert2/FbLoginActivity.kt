package com.shani.kwalert2

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button

/**
 * FB Login screen — user yahan apne FB account se KHUD login karta hai.
 *
 * App password ko kabhi nahi dekhti, store nahi karti, kahin bheejti
 * nahi: login sirf WebView ke andar hota hai aur session WebView ke
 * apne cookie store me rehta hai (CookieManager, jo FbWatchService ke
 * offscreen WebView ke sath shared hai). Screen band karne se pehle
 * cookies flush kar diye jate hain taake app restart ke baad bhi
 * login barqarar rahe.
 *
 * Desktop Chrome user-agent is liye hai taake facebook.com desktop
 * layout de — wahi layout jo FbWatchService parhta hai.
 */
class FbLoginActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fb_login)

        webView = findViewById(R.id.webFbLogin)
        val btnDone: Button = findViewById(R.id.btnFbLoginDone)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = FbWatchService.DESKTOP_UA
        webView.webViewClient = WebViewClient()

        btnDone.setOnClickListener {
            flushCookies()
            finish()
        }

        webView.loadUrl("https://www.facebook.com/")
    }

    private fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (ignored: Exception) {
            // Flush fail ho jaye to bhi cookies WebView store me hi rehte hain.
        }
    }

    override fun onPause() {
        flushCookies()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }
}
