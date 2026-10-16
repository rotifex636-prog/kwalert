package com.shani.kwalert2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * FB Watch — foreground service jo har 30 second par Kountry Wayne ke
 * reels page ko offscreen WebView me load karke pehli do reel tiles ke
 * links parhta hai.
 *
 * Kaam ka tareeqa:
 * - Login user khud FbLoginActivity me karta hai; cookies WebView ke
 *   apne store me rehte hain (CookieManager poori app me shared hai),
 *   is service ko wahi session mil jata hai. Password app kabhi nahi
 *   dekhti/store karti.
 * - Har cycle: page load (timeout 20s) → settle delay → JavaScript se
 *   DOM order me /reel/<digits> wale pehle DO distinct links.
 * - Pehli kamyab read par pair baseline ban jata hai (koi alert nahi).
 * - Pair change ho to foran alert NAHI — pending-pair confirmation:
 *   nayi pair 2 lagatar successful checks me dikhe tabhi confirmed
 *   alert (Speaker.speakAlert se "Video aa gayi!" 3 dafa + tune,
 *   baseline update, last catch time save). Aik dafa ki transient
 *   render glitch chupke discard ho jati hai.
 * - Same-reel dedupe: confirmed alert ke baad usi tile-1 reel id par
 *   30 min tak dobara awaz nahi ati; baseline phir bhi update hota
 *   hai. Asli nayi reel ki id mukhtalif hoti hai, wo hamesha bolegi.
 * - Page par koi reel link na mile (login / checkpoint state) to
 *   status "FB login khatam" set hota hai aur aik notification ata
 *   hai (watch session me sirf aik dafa) — awaz wala spam nahi hota,
 *   aur har cycle retry jari rehta hai.
 * - Service chalne tak partial WakeLock rehta hai taake screen off
 *   ho tab bhi checks jari rahen.
 */
class FbWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var loadInProgress = false
    private var loginNoticeShown = false

    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // Watch schedule (Pakistan time): sham 6 se subha 8 tak.
            // Is window ke bahar checks skip — battery aur data bachega.
            if (!isWatchHour()) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
                return
            }
            startCycle()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** Pakistan time (Asia/Karachi) me 18:00–08:00 ke darmiyan hai?
     *  Agar "din me bhi watch" switch on hai to hamesha true. */
    private fun isWatchHour(): Boolean {
        if (prefs().getBoolean(KEY_DAYTIME_WATCH, false)) return true
        val tz = java.util.TimeZone.getTimeZone("Asia/Karachi")
        val cal = java.util.Calendar.getInstance(tz)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= WATCH_START_HOUR || hour < WATCH_END_HOUR
    }

    private val loadTimeout = Runnable { finishCycleSilently() }

    private val settle = Runnable { extractTiles() }

    // evaluateJavascript ka callback kabhi na aye to cycle atak na jaye.
    private val jsSafety = Runnable { loadInProgress = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (ignored: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground notification foran lagana lazmi hai
        // (startForegroundService ki 5s wali shart).
        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            buildNotification(
                "FB Watch chal raha hai",
                "Har 30 second me nayi reel check ho rahi hai",
                ongoing = true
            )
        )
        if (!running) {
            running = true
            loginNoticeShown = false
            acquireWakeLock()
            prefs().edit().putBoolean(KEY_FB_WATCH_RUNNING, true).apply()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            webView?.destroy()
        } catch (ignored: Exception) {
        }
        webView = null
        releaseWakeLock()
        prefs().edit().putBoolean(KEY_FB_WATCH_RUNNING, false).apply()
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Watch cycle
    // ---------------------------------------------------------------

    private fun startCycle() {
        if (loadInProgress) return
        val wv = ensureWebView()
        loadInProgress = true
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        try {
            wv.loadUrl(REELS_URL)
        } catch (ignored: Exception) {
            finishCycleSilently()
        }
    }

    /** Load fail/timeout ho jaye to bas cycle khatam — agli cycle par phir koshish. */
    private fun finishCycleSilently() {
        if (!loadInProgress) return
        loadInProgress = false
        handler.removeCallbacks(loadTimeout)
        handler.removeCallbacks(settle)
        handler.removeCallbacks(jsSafety)
        try {
            webView?.stopLoading()
        } catch (ignored: Exception) {
        }
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = DESKTOP_UA
        // Tasaveer load karna band — sirf DOM/links chahiye, load tez hoga.
        wv.settings.loadsImagesAutomatically = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loadInProgress) return
                // Page "finished" ke baad grid render hone ka thoda waqt do.
                handler.removeCallbacks(settle)
                handler.postDelayed(settle, SETTLE_MS)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) finishCycleSilently()
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // API 23 se pehle sirf yehi callback milta hai.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) finishCycleSilently()
            }
        }
        // Offscreen WebView ko size dena lazmi hai warna render nahi hota.
        wv.layout(0, 0, 1280, 800)
        webView = wv
        return wv
    }

    private fun extractTiles() {
        val wv = webView
        if (wv == null || !loadInProgress) {
            loadInProgress = false
            return
        }
        handler.removeCallbacks(loadTimeout)
        handler.removeCallbacks(jsSafety)
        handler.postDelayed(jsSafety, JS_SAFETY_MS)
        try {
            wv.evaluateJavascript(EXTRACT_JS) { raw ->
                handler.removeCallbacks(jsSafety)
                loadInProgress = false
                onTilesResult(parseIds(raw), wv.url)
            }
        } catch (ignored: Exception) {
            loadInProgress = false
        }
    }

    /** evaluateJavascript ka result JSON string hota hai, maslan "\"123,456\"". */
    private fun parseIds(raw: String?): List<String> {
        val cleaned = raw?.trim()?.removeSurrounding("\"")?.replace("\\/", "/").orEmpty()
        if (cleaned.isEmpty() || cleaned == "null") return emptyList()
        return cleaned.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun onTilesResult(ids: List<String>, currentUrl: String?) {
        val now = System.currentTimeMillis()
        val editor = prefs().edit()
        editor.putLong(KEY_FB_LAST_CHECK_TIME, now)

        val urlHint = currentUrl?.contains("login", ignoreCase = true) == true ||
            currentUrl?.contains("checkpoint", ignoreCase = true) == true

        if (ids.size < 2) {
            if (ids.isEmpty() || urlHint) {
                // Grid nahi mila — ho sakta hai page slow load ho raha ho,
                // is liye aik khali read par foran "login khatam" mat kaho.
                // 3 lagatar khali reads par hi login warning dikhao.
                val streak = prefs().getInt(KEY_FB_EMPTY_STREAK, 0) + 1
                editor.putInt(KEY_FB_EMPTY_STREAK, streak)
                if (streak >= EMPTY_STREAK_FOR_LOGIN_WARN) {
                    editor.putBoolean(KEY_FB_LOGIN_NEEDED, true)
                    editor.apply()
                    if (!loginNoticeShown) {
                        loginNoticeShown = true
                        showLoginNeededNotification()
                    }
                } else {
                    editor.apply()
                }
            } else {
                // Sirf aik link mila — page aadha render hua hoga; is
                // read ko na baseline ke liye use karo na login samjho.
                editor.apply()
            }
            return
        }

        // Grid mil gaya — empty streak reset, login-needed wala flag saaf.
        editor.putInt(KEY_FB_EMPTY_STREAK, 0)
        editor.putBoolean(KEY_FB_LOGIN_NEEDED, false)
        // Agli dafa sach me login khatam ho to notification dobara aye.
        loginNoticeShown = false

        val tile1 = ids[0]
        val tile2 = ids[1]
        val baseline1 = prefs().getString(KEY_FB_BASELINE_1, "").orEmpty()
        val baseline2 = prefs().getString(KEY_FB_BASELINE_2, "").orEmpty()

        if (baseline1.isEmpty()) {
            // Pehli kamyab read: baseline set karo, alert NAHI.
            editor.putString(KEY_FB_BASELINE_1, tile1)
            editor.putString(KEY_FB_BASELINE_2, tile2)
            editor.apply()
            return
        }

        if (tile1 == baseline1 && tile2 == baseline2) {
            // Baseline se match — agar koi pending pair tha to wo
            // transient render glitch tha; chupke discard kar do.
            if (prefs().getInt(KEY_FB_PENDING_COUNT, 0) > 0) {
                editor.putInt(KEY_FB_PENDING_COUNT, 0)
                editor.remove(KEY_FB_PENDING_1)
                editor.remove(KEY_FB_PENDING_2)
            }
            editor.apply()
            return
        }

        // Pair baseline se mukhtalif hai — foran alert NAHI.
        // PENDING CONFIRMATION: naya tile-1 (nayi video) 2 lagatar
        // successful checks me dikhe tabhi confirmed alert hoga.
        // Sirf tile-1 ko dekho — tile-2 Facebook reshuffle kar deta hai,
        // us par confirmation latak jata tha aur video miss ho jati thi.
        val storedP1 = prefs().getString(KEY_FB_PENDING_1, "").orEmpty()
        val storedCount = prefs().getInt(KEY_FB_PENDING_COUNT, 0)

        val newCount: Int
        if (storedCount > 0 && tile1 == storedP1) {
            newCount = storedCount + 1
        } else {
            // Pehli dafa ya koi teesri mukhtalif pair — pending reset.
            newCount = 1
            editor.putString(KEY_FB_PENDING_1, tile1)
            editor.putString(KEY_FB_PENDING_2, tile2)
        }
        editor.putInt(KEY_FB_PENDING_COUNT, newCount)

        if (newCount < CONFIRM_READS) {
            // Abhi confirm nahi hua — agle check ka intezar karo.
            editor.apply()
            return
        }

        // CONFIRMED: nayi pair lagatar 2 checks me dikhi.
        editor.putInt(KEY_FB_PENDING_COUNT, 0)
        editor.remove(KEY_FB_PENDING_1)
        editor.remove(KEY_FB_PENDING_2)
        editor.putString(KEY_FB_BASELINE_1, tile1)
        editor.putString(KEY_FB_BASELINE_2, tile2)
        editor.putLong(KEY_FB_LAST_CATCH_TIME, now)

        // SAME-REEL DEDUPE: agar isi tile-1 id par pichle 30 min me
        // pehle hi alert ho chuka hai to baseline chupke update karo —
        // dobara awaz NAHI. Asli nayi reel ki id hamesha mukhtalif
        // hoti hai, is liye wo phir bhi bolegi.
        val lastAlertedReel = prefs().getString(KEY_FB_LAST_ALERTED_REEL, "").orEmpty()
        val lastAlertedTime = prefs().getLong(KEY_FB_LAST_ALERTED_TIME, 0L)
        val isRepeat = lastAlertedReel.isNotEmpty() &&
            tile1 == lastAlertedReel &&
            (now - lastAlertedTime) < DEDUPE_WINDOW_MS

        if (!isRepeat) {
            // NAYI VIDEO — Telegram wale rastay wala hi alert flow.
            editor.putString(KwNotificationListener.KEY_LAST_ALERT, FIXED_ANNOUNCEMENT)
            editor.putLong(KwNotificationListener.KEY_LAST_ALERT_TIME, now)
            editor.putString(KEY_FB_LAST_ALERTED_REEL, tile1)
            editor.putLong(KEY_FB_LAST_ALERTED_TIME, now)
            editor.apply()
            Speaker.playAlert(applicationContext)
        } else {
            editor.apply()
        }
    }

    // ---------------------------------------------------------------
    // Notifications / WakeLock
    // ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FB Watch",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags = pendingFlags or PendingIntent.FLAG_IMMUTABLE
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .build()
    }

    private fun showLoginNeededNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Ye notification persistent nahi — user dismiss kar sakta hai.
        val notification = buildNotification(
            "FB login khatam — dobara login karo",
            "KW Video Alert khol kar \"FB Login Karo\" se dobara login karo",
            ongoing = false
        )
        manager.notify(LOGIN_NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kwalert:fbwatch")
                wakeLock?.setReferenceCounted(false)
            }
            val lock = wakeLock ?: return
            if (!lock.isHeld) lock.acquire()
        } catch (ignored: Exception) {
            // Wake lock na mile to service phir bhi chalti rahe gi.
        }
    }

    private fun releaseWakeLock() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) lock.release()
        } catch (ignored: Exception) {
        }
        wakeLock = null
    }

    private fun prefs() = getSharedPreferences(KwNotificationListener.PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val REELS_URL = "https://www.facebook.com/KingKountryWayne/reels"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        const val KEY_FB_BASELINE_1 = "fb_baseline_1"
        const val KEY_FB_BASELINE_2 = "fb_baseline_2"
        const val KEY_FB_LAST_CHECK_TIME = "fb_last_check_time"
        const val KEY_FB_LAST_CATCH_TIME = "fb_last_catch_time"
        const val KEY_FB_LOGIN_NEEDED = "fb_login_needed"
        const val KEY_FB_WATCH_RUNNING = "fb_watch_running"
        const val KEY_FB_PENDING_1 = "fb_pending_1"
        const val KEY_FB_PENDING_2 = "fb_pending_2"
        const val KEY_FB_PENDING_COUNT = "fb_pending_count"
        const val KEY_FB_LAST_ALERTED_REEL = "fb_last_alerted_reel"
        const val KEY_FB_LAST_ALERTED_TIME = "fb_last_alerted_time"
        const val KEY_FB_EMPTY_STREAK = "fb_empty_streak"
        // Din me bhi watch (24 ghante) ka override switch.
        const val KEY_DAYTIME_WATCH = "daytime_watch"

        // Khali reads: itni lagatar khali reads par hi "login khatam"
        // warning dikhao — aik khali read aksar slow page load hota hai.
        private const val EMPTY_STREAK_FOR_LOGIN_WARN = 3
        // Watch schedule (Pakistan time): sham 6 (18) se subha 8 tak.
        private const val WATCH_START_HOUR = 18
        private const val WATCH_END_HOUR = 8

        // Pending-pair confirmation: nayi pair kitni lagatar
        // successful checks me dikhe tabhi confirmed alert ho.
        private const val CONFIRM_READS = 2
        // Same-reel dedupe window: is arse me dobara alert nahi.
        private const val DEDUPE_WINDOW_MS = 30 * 60 * 1000L

        private const val CHANNEL_ID = "fb_watch"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val LOGIN_NOTIFICATION_ID = 1002

        private const val CHECK_INTERVAL_MS = 30_000L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val SETTLE_MS = 3_500L
        private const val JS_SAFETY_MS = 10_000L

        private const val FIXED_ANNOUNCEMENT = "Video aa gayi!"
        private const val REPEAT_COUNT = 3

        /**
         * DOM order me anchor hrefs parh kar /reel/<digits> wale pehle
         * DO distinct ids wapas karta hai, comma-separated string me.
         */
        private const val EXTRACT_JS =
            "(function(){var ids=[];var seen={};" +
                "var as=document.querySelectorAll('a[href]');" +
                "for(var i=0;i<as.length;i++){var h=as[i].href||'';" +
                "var m=h.match(/\\/reel\\/(\\d+)/);" +
                "if(m&&!seen[m[1]]){seen[m[1]]=1;ids.push(m[1]);" +
                "if(ids.length>=2)break;}}" +
                "return ids.join(',');})()"

        fun start(context: Context) {
            val intent = Intent(context, FbWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
