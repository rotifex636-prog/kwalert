package com.shani.kwalert2

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Telegram ki notifications sun kar KW Video Alert bot ke alert bolta hai.
 *
 * v1.5 MATCHING (robust): purana strict match naye phone par miss ho jata
 * tha — kai bot messages unread pare hon to Telegram aik hi GROUPED /
 * SUMMARY notification post karta hai jis ka visible text asli message
 * text nahi hota (maslan "2 new messages"), aur strict "text VIDEO: se
 * shuru ho" wali shart fail ho jati thi. Is liye ab:
 *
 *  1. Notification kisi Telegram package se ho (package name me
 *     "telegram" aata ho, case-insensitive — forks/X bhi cover).
 *  2. Notification ke SAARE text fields ikatthe kiye jate hain: title,
 *     text, big text, sub text, summary text, info text, text lines
 *     (InboxStyle) aur ticker text.
 *  3. TRIGGER agar:
 *     (a) kisi bhi field me "KW Video Alert" aata ho — bot wali chat ka
 *         naam title me bhi hota hai aur group summary me bhi; bot chat
 *         me sirf alerts aate hain, is liye chat ka naam = alert, YA
 *     (b) koi field (trim ke baad) "VIDEO" se shuru ho aur us ke baad
 *         end / ":" / space ho (purana "VIDEO" / "VIDEO:" wala signal).
 *
 * Announcement hamesha FIXED jumla "Video aa gayi!" hai — video ki title
 * KABHI nahi boli jati. Match hote hi wahi jumla peeche fun tune ke sath
 * 3 dafa repeat hota hai (Speaker.speakAlert) aur last alert
 * SharedPreferences me save ho jata hai taake MainActivity dikha sake.
 *
 * Dedupe: group summary + us ke child notifications aik hi alert ke liye
 * aik sath aate hain — 60 second ke window me sirf AIK announcement
 * hoga. Asli alerts ~30 minute ke waqfe se aate hain, is liye 60s safe
 * hai.
 */
class KwNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!sbn.packageName.contains(TELEGRAM_PACKAGE_MARKER, ignoreCase = true)) return

        val fields = gatherTextFields(sbn)
        if (fields.isEmpty()) return

        val namesBotChat = fields.any { it.contains(BOT_TITLE, ignoreCase = true) }
        val hasVideoSignal = fields.any { startsWithVideoWord(it) }
        if (!namesBotChat && !hasVideoSignal) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Dedupe: 60 second ke andar dobara mat bolo — summary + child
        // notifications aik hi alert ki hoti hain.
        val lastSpokenTime = prefs.getLong(KEY_LAST_SPOKEN_TIME, 0L)
        if (now - lastSpokenTime < DEDUPE_WINDOW_MS) {
            return
        }

        prefs.edit()
            .putString(KEY_LAST_SPOKEN_TEXT, fields.joinToString(" | "))
            .putLong(KEY_LAST_SPOKEN_TIME, now)
            .putString(KEY_LAST_ALERT, FIXED_ANNOUNCEMENT)
            .putLong(KEY_LAST_ALERT_TIME, now)
            .apply()

        // Fixed jumla 3 dafa sunayi de, peeche fun tune chalti rahe —
        // Speaker.speakAlert dono ka khayal rakhta hai.
        Speaker.playAlert(applicationContext)
    }

    /** Notification ke tamam text fields ikatthe karta hai (jo null/blank hon wo skip). */
    private fun gatherTextFields(sbn: StatusBarNotification): List<String> {
        val fields = mutableListOf<String>()
        fun add(value: CharSequence?) {
            val s = value?.toString().orEmpty()
            if (s.isNotBlank()) fields.add(s)
        }

        val extras = sbn.notification.extras
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        add(sbn.notification.tickerText)
        return fields
    }

    /**
     * True agar [field] trim hone ke baad "VIDEO" lafz se shuru ho aur
     * us ke foran baad end, ":" ya space ho — yani text == "VIDEO" ho ya
     * "VIDEO:" / "VIDEO " se shuru ho.
     */
    private fun startsWithVideoWord(field: String): Boolean {
        val s = field.trim()
        if (!s.startsWith(ALERT_TEXT)) return false
        if (s.length == ALERT_TEXT.length) return true
        val next = s[ALERT_TEXT.length]
        return next == ':' || next == ' '
    }

    companion object {
        const val PREFS_NAME = "kwalert"
        const val KEY_LAST_ALERT = "last_alert"
        const val KEY_LAST_ALERT_TIME = "last_alert_time"
        const val KEY_LAST_SPOKEN_TEXT = "last_spoken_text"
        const val KEY_LAST_SPOKEN_TIME = "last_spoken_time"

        private const val TELEGRAM_PACKAGE_MARKER = "telegram"
        private const val BOT_TITLE = "KW Video Alert"
        private const val ALERT_TEXT = "VIDEO"
        private const val FIXED_ANNOUNCEMENT = "Video aa gayi!"
        private const val DEDUPE_WINDOW_MS = 60_000L
        private const val REPEAT_COUNT = 3
    }
}
