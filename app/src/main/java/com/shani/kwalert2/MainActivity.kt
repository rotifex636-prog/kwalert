package com.shani.kwalert2

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLastAlert: TextView
    private lateinit var tvSoundName: TextView

    // FB Watch wale views
    private lateinit var tvWatchState: TextView
    private lateinit var tvFbWarning: TextView
    private lateinit var tvFbLastCheck: TextView
    private lateinit var tvFbLastCatch: TextView
    private lateinit var tvFbBaseline: TextView

    private val REQ_PICK_AUDIO = 1001

    // FB Watch ki status har chand second me taza hoti rahe taake
    // last check / last catch ke time screen par live nazar ayen.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefresh = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLastAlert = findViewById(R.id.tvLastAlert)
        val btnNotifAccess: Button = findViewById(R.id.btnNotifAccess)
        val btnTest: Button = findViewById(R.id.btnTest)

        tvWatchState = findViewById(R.id.tvWatchState)
        tvFbWarning = findViewById(R.id.tvFbWarning)
        tvFbLastCheck = findViewById(R.id.tvFbLastCheck)
        tvFbLastCatch = findViewById(R.id.tvFbLastCatch)
        tvFbBaseline = findViewById(R.id.tvFbBaseline)
        val btnFbLogin: Button = findViewById(R.id.btnFbLogin)
        val btnStartWatch: Button = findViewById(R.id.btnStartWatch)
        val btnStopWatch: Button = findViewById(R.id.btnStopWatch)

        // --- v2.3: din me bhi watch (24 ghante) ka switch ---
        val switchDaytime: android.widget.Switch = findViewById(R.id.switchDaytimeWatch)
        val dayPrefs = getSharedPreferences(KwNotificationListener.PREFS_NAME, Context.MODE_PRIVATE)
        switchDaytime.isChecked = dayPrefs.getBoolean(FbWatchService.KEY_DAYTIME_WATCH, false)
        switchDaytime.setOnCheckedChangeListener { _, on ->
            dayPrefs.edit().putBoolean(FbWatchService.KEY_DAYTIME_WATCH, on).apply()
            val msg = if (on) "24 ghante watch on ho gaya" else "Sirf sham 6 se subha 8 tak watch hoga"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }

        btnNotifAccess.setOnClickListener {
            openNotificationAccessSettings()
        }

        btnTest.setOnClickListener {
            // Jo sound select hai wahi bajegi — 3 sec me 2 dafa.
            Speaker.playAlert(this)
        }

        // --- v2.2: alert sound select (voice ya phone ki file) ---
        tvSoundName = findViewById(R.id.tvSoundName)
        val btnVoice: Button = findViewById(R.id.btnVoice)
        val btnPickFile: Button = findViewById(R.id.btnPickFile)
        refreshSoundLabel()

        btnVoice.setOnClickListener {
            Speaker.setVoice(this)
            refreshSoundLabel()
            Toast.makeText(this, "Voice select ho gayi", Toast.LENGTH_SHORT).show()
        }
        btnPickFile.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, REQ_PICK_AUDIO)
            } catch (e: Exception) {
                Toast.makeText(this, "File picker nahi khul saka", Toast.LENGTH_SHORT).show()
            }
        }

        btnFbLogin.setOnClickListener {
            startActivity(Intent(this, FbLoginActivity::class.java))
        }

        btnStartWatch.setOnClickListener {
            // Android 13+ par apni notifications (FB Watch / login
            // khatam wali) dikhane ke liye runtime permission.
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_POST_NOTIFICATIONS
                    )
                } catch (ignored: Exception) {
                }
            }
            FbWatchService.start(this)
            Toast.makeText(this, "FB Watch shuru ho gaya", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        btnStopWatch.setOnClickListener {
            stopService(Intent(this, FbWatchService::class.java))
            getSharedPreferences(KwNotificationListener.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(FbWatchService.KEY_FB_WATCH_RUNNING, false)
                .apply()
            Toast.makeText(this, "FB Watch band ho gaya", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        refreshUi()
    }

    private fun refreshSoundLabel() {
        tvSoundName.text = if (Speaker.getSoundType(this) == Speaker.TYPE_FILE) {
            val n = Speaker.getSoundName(this)
            if (n.isNotEmpty()) "File: $n" else "File select karo"
        } else {
            "Voice: Video aa gayi!"
        }
    }

    @Deprecated("framework callback — v2.2 file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                var name = "audio file"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
                }
                Speaker.setFile(this, uri.toString(), name)
                refreshSoundLabel()
                Toast.makeText(this, "File select ho gayi: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "File select nahi ho saki", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * "Notification access" wali Settings screen kholna.
     *
     * Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS API 22 par aya tha —
     * API 21 (Android 5.0) par is action se wo screen nahi khulti, is liye
     * wahan aam Settings khol kar Toast se rasta bataya jata hai, aur agar
     * kisi device par intent resolve hi na ho to crash ki bajaye fallback
     * par jaya jata hai.
     */
    private fun openNotificationAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return
            } catch (ignored: ActivityNotFoundException) {
                // Is device par wo screen is action se nahi mili — fallback.
            }
        } else {
            Toast.makeText(
                this,
                "Settings > Sound & notification > Notification access me ja kar " +
                    "KW Video Alert ko ON karo",
                Toast.LENGTH_LONG
            ).show()
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (ignored: ActivityNotFoundException) {
            // Settings bhi na khule to kuch nahi kar sakte — crash mat karo.
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings se wapas aane par status foran refresh ho,
        // aur screen khuli ho tab tak har 5s me taza hoti rahe.
        refreshUi()
        uiHandler.removeCallbacks(uiRefresh)
        uiHandler.postDelayed(uiRefresh, UI_REFRESH_MS)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiRefresh)
        super.onPause()
    }

    private fun refreshUi() {
        tvStatus.text = if (hasNotificationAccess()) {
            "Notification access: MILA HUA hai"
        } else {
            "Notification access: NAHI mila — neeche wala button dabao"
        }
        refreshLastAlert()
        refreshFbUi()
    }

    /** FB Watch ki status: chalna/band, aakhri check, aakhri catch, baseline, login warning. */
    private fun refreshFbUi() {
        val prefs = getSharedPreferences(KwNotificationListener.PREFS_NAME, Context.MODE_PRIVATE)

        val running = prefs.getBoolean(FbWatchService.KEY_FB_WATCH_RUNNING, false)
        tvWatchState.text = if (running) {
            "FB Watch: CHAL raha hai"
        } else {
            "FB Watch: band hai"
        }

        val loginNeeded = prefs.getBoolean(FbWatchService.KEY_FB_LOGIN_NEEDED, false)
        tvFbWarning.visibility = if (loginNeeded) View.VISIBLE else View.GONE

        val lastCheck = prefs.getLong(FbWatchService.KEY_FB_LAST_CHECK_TIME, 0L)
        tvFbLastCheck.text = if (lastCheck > 0L) {
            "Aakhri check: " + SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(lastCheck))
        } else {
            "Aakhri check: —"
        }

        val lastCatch = prefs.getLong(FbWatchService.KEY_FB_LAST_CATCH_TIME, 0L)
        tvFbLastCatch.text = if (lastCatch > 0L) {
            "Aakhri video pakri: " + SimpleDateFormat("h:mm a", Locale.US).format(Date(lastCatch))
        } else {
            "Aakhri video pakri: —"
        }

        val baseline1 = prefs.getString(FbWatchService.KEY_FB_BASELINE_1, "").orEmpty()
        val baseline2 = prefs.getString(FbWatchService.KEY_FB_BASELINE_2, "").orEmpty()
        tvFbBaseline.text = if (baseline1.isEmpty()) {
            "Baseline: abhi set nahi"
        } else {
            "Baseline: " + shortenId(baseline1) + " / " + shortenId(baseline2)
        }
    }

    private fun shortenId(id: String): String {
        return if (id.length > 8) id.substring(0, 8) + "…" else id
    }

    private fun refreshLastAlert() {
        val prefs = getSharedPreferences(KwNotificationListener.PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getString(KwNotificationListener.KEY_LAST_ALERT, "").orEmpty()
        if (last.isEmpty()) {
            tvLastAlert.text = "Abhi tak koi alert nahi aya"
        } else {
            val time = prefs.getLong(KwNotificationListener.KEY_LAST_ALERT_TIME, 0L)
            val whenText = if (time > 0L) {
                " (" + SimpleDateFormat("h:mm a", Locale.US).format(Date(time)) + ")"
            } else {
                ""
            }
            tvLastAlert.text = last + whenText
        }
    }

    /**
     * Notification access mila hua hai ya nahi — Settings.Secure ki
     * "enabled_notification_listeners" list me apna package dhoondh kar.
     */
    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        if (flat.isBlank()) return false
        return flat.split(':').any { name ->
            ComponentName.unflattenFromString(name)?.packageName == packageName
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val UI_REFRESH_MS = 5_000L
    }
}
