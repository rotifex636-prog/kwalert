package com.shani.kwalert2

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Shared TTS helper — NotificationListenerService aur MainActivity (Test Voice)
 * dono isi ko use karte hain.
 *
 * - TTS engine lazily banta hai; init async hai, is liye ready hone se pehle
 *   aaye hue jumlay queue me rakh kar ready hote hi bol diye jate hain.
 * - Bolte waqt partial wake lock liya jata hai taake screen off ho tab bhi
 *   awaz poori aye. Wake lock utterance khatam hone par release hota hai,
 *   aur safety ke liye 30s timeout bhi hai.
 */
object Speaker {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val pending = mutableListOf<String>()
    private val wakeLocks = mutableMapOf<String, PowerManager.WakeLock>()

    // --- Alert wali background tune (bonus feature) ---
    // Alert ke 3 jumlay chalte rehne tak res/raw/tune loop hoti rehti hai;
    // aakhri jumla khatam hote hi band. Kuch bhi fail ho jaye to awaz
    // (TTS) phir bhi chalti hai — tune kabhi blocker nahi banti.
    private var tunePlayer: MediaPlayer? = null
    private var alertUtterancesRemaining = 0
    private val tuneHandler = Handler(Looper.getMainLooper())
    private val tuneSafetyStop = Runnable { stopTune() }

    // --- v2.2: selectable alert sound (voice ya phone ki file) ---
    // "3 sec me 2 dafa" pattern: jo sound select ho wahi 2 dafa bajti hai.
    const val PREFS_NAME = "kw_alert_prefs"
    const val KEY_SOUND_TYPE = "alert_sound_type"
    const val KEY_SOUND_URI = "alert_sound_uri"
    const val KEY_SOUND_NAME = "alert_sound_name"
    const val TYPE_VOICE = "voice"
    const val TYPE_FILE = "file"

    private fun prefs(c: Context) =
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSoundType(c: Context): String =
        prefs(c).getString(KEY_SOUND_TYPE, TYPE_VOICE) ?: TYPE_VOICE

    fun getSoundName(c: Context): String =
        prefs(c).getString(KEY_SOUND_NAME, "") ?: ""

    fun setVoice(c: Context) {
        prefs(c).edit().putString(KEY_SOUND_TYPE, TYPE_VOICE).apply()
    }

    fun setFile(c: Context, uriString: String, displayName: String) {
        prefs(c).edit()
            .putString(KEY_SOUND_TYPE, TYPE_FILE)
            .putString(KEY_SOUND_URI, uriString)
            .putString(KEY_SOUND_NAME, displayName)
            .apply()
    }

    /**
     * Unified alert entry point (v2.2): selected sound 2 dafa, 3 second ke andar.
     * - voice: "Video aa gayi!" 2 dafa (peeche halki tune, pehle jaisi)
     * - file: phone se chuni hui audio file 2 dafa (~1.5s gap)
     */
    @Synchronized
    fun playAlert(context: Context) {
        val appContext = context.applicationContext
        val type = getSoundType(appContext)
        val uriStr = prefs(appContext).getString(KEY_SOUND_URI, null)
        if (type == TYPE_FILE && !uriStr.isNullOrEmpty()) {
            try {
                playFileTwice(appContext, android.net.Uri.parse(uriStr))
                return
            } catch (ignored: Exception) {
                // file na baje to voice par gir jao
            }
        }
        speakAlert(appContext, "Video aa gayi!", 2)
    }

    /**
     * Custom file 2 dafa, 3 second ke andar: abhi bajao, 1.5s baad dobara.
     * (Chhoti ringtone poori 2 dafa bajegi; lambi file ka doosra bajna
     * pehle ko kaat kar dobara shuru hoga — alert chhota hi rehna chahiye.)
     */
    private fun playFileTwice(appContext: Context, uri: android.net.Uri) {
        // Screen off par bhi poori baje — chhota wake lock.
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kwalert:file")
                .apply { setReferenceCounted(false); acquire(10_000L) }
        } catch (ignored: Exception) {
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(appContext, uri)
            mp.prepare()
        } catch (e: Exception) {
            try { mp.release() } catch (ignored: Exception) {}
            throw e
        }
        val h = Handler(Looper.getMainLooper())
        val secondPlay = Runnable {
            try { mp.seekTo(0); mp.start() } catch (ignored: Exception) {}
        }
        val finish = Runnable {
            h.removeCallbacks(secondPlay)
            try { if (mp.isPlaying) mp.stop() } catch (ignored: Exception) {}
            try { mp.release() } catch (ignored: Exception) {}
        }
        mp.start()
        h.postDelayed(secondPlay, 1_500L)   // doosri dafa — 1.5s baad
        h.postDelayed(finish, 6_000L)       // safety cleanup
    }

    /**
     * Alert wala flow: peeche halki si fun tune (loop) chalti hai aur
     * jumla [times] dafa bola jata hai. Tune aakhri jumlay ke khatam
     * hone par band hoti hai; safety ke liye 45s par khud bhi band.
     */
    @Synchronized
    fun speakAlert(context: Context, text: String, times: Int) {
        val appContext = context.applicationContext
        alertUtterancesRemaining = times
        startTune(appContext)
        repeat(times) { speak(appContext, text) }
    }

    private fun startTune(appContext: Context) {
        try {
            if (tunePlayer == null) {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                val afd = appContext.resources.openRawResourceFd(R.raw.tune)
                try {
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } finally {
                    afd.close()
                }
                mp.isLooping = true
                mp.setVolume(0.5f, 0.5f)
                mp.prepare()
                tunePlayer = mp
            }
            val player = tunePlayer ?: return
            if (!player.isPlaying) player.start()
            // Safety: jo bhi ho jaye, tune 45 second se zyada na chale.
            tuneHandler.removeCallbacks(tuneSafetyStop)
            tuneHandler.postDelayed(tuneSafetyStop, 45_000L)
        } catch (ignored: Exception) {
            // Tune na chale to koi baat nahi — awaz phir bhi ayegi.
            stopTune()
        }
    }

    private fun stopTune() {
        tuneHandler.removeCallbacks(tuneSafetyStop)
        val player = tunePlayer
        tunePlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) player.stop()
            } catch (ignored: Exception) {
            }
            try {
                player.release()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Aik jumla khatam (ya fail) — wake lock chhodo; alert ke jumlay gin kar aakhri par tune band karo. */
    @Synchronized
    private fun onUtteranceFinished(utteranceId: String?) {
        releaseWakeLock(utteranceId)
        if (alertUtterancesRemaining > 0) {
            alertUtterancesRemaining--
            if (alertUtterancesRemaining == 0) {
                stopTune()
            }
        }
    }

    @Synchronized
    fun speak(context: Context, text: String) {
        val engine = ensureEngine(context.applicationContext)
        if (engine != null && ready) {
            doSpeak(context.applicationContext, engine, text)
        } else {
            pending.add(text)
        }
    }

    @Synchronized
    private fun ensureEngine(appContext: Context): TextToSpeech? {
        if (tts != null) return tts
        val created = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                var result = engine.setLanguage(Locale("ur", "PK"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.US)
                    result = TextToSpeech.SUCCESS
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId)
                    @Deprecated("deprecated in API level 21")
                    override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        onUtteranceFinished(utteranceId)
                })
                val queued: List<String>
                synchronized(Speaker) {
                    ready = true
                    queued = pending.toList()
                    pending.clear()
                }
                queued.forEach { doSpeak(appContext, engine, it) }
            }
        }
        tts = created
        return created
    }

    private fun doSpeak(
        appContext: Context,
        engine: TextToSpeech,
        text: String
    ) {
        val utteranceId = "kw_" + UUID.randomUUID().toString()
        // Screen off ho tab bhi awaz rukni nahi chahiye — partial wake lock.
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "kwalert:speak"
            )
            lock.setReferenceCounted(false)
            lock.acquire(30_000L)
            synchronized(Speaker) { wakeLocks[utteranceId] = lock }
        } catch (ignored: Exception) {
            // Wake lock na bhi mile to bolna to hai hi.
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            onUtteranceFinished(utteranceId)
        }
    }

    @Synchronized
    private fun releaseWakeLock(utteranceId: String?) {
        if (utteranceId == null) return
        val lock = wakeLocks.remove(utteranceId) ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (ignored: Exception) {
        }
    }
}
