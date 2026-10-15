# KW Video Alert

Chhoti Android app (package `com.shani.kwalert`) jo Telegram par **KW Video Alert**
bot ka alert aate hi awaz me bolti hai: **"Video aa gayi! <title>"** — screen off
ho tab bhi.

## v1.1 — NotificationListener design (current)

**Kyun badla:** v1.0 bot token se Telegram Bot API ko `getUpdates` poll karti thi,
lekin getUpdates bot ke **apne bheje hue** messages (sendMessage alerts) kabhi
return nahi karta — yani server-side alerts app tak pohanch hi nahi sakte thay.
Alerts user ki Telegram chat me sahi aate hain, is liye v1.1 unhi ko
**Telegram ki notification** se sunti hai.

### Kaise kaam karta hai

1. App khol kar **"Notification Access Do"** dabao → Settings khul jayegi →
   wahan **KW Video Alert** ko notification access de do. (Ye access user deta
   hai; app khud nahi le sakti. Status line app me dikhta hai: mila hua hai ya
   nahi.)
2. `KwNotificationListener` (NotificationListenerService) har notification
   check karta hai. Sirf tab bolta hai jab **teeno** sharten poori hon:
   - notification Telegram se ho (`org.telegram.messenger` ya Challegram),
   - title me **"KW Video Alert"** ho (bot wali chat ka naam),
   - text **"VIDEO:"** se shuru ho.
3. Match hote hi bolta hai: **"Video aa gayi! <VIDEO: ke baad wala text>"**
   (TTS: ur-PK, na mile to US English). Bolte waqt partial wake lock liya jata
   hai taake screen off ho tab bhi awaz aye.
4. Telegram notification bar bar re-post/edit karta hai, is liye aik hi alert
   **15 second** ke andar dobara nahi bola jata (dedupe, SharedPreferences).
   Aakhri alert app ki screen par bhi dikhta hai.
5. **"Test Voice"** button baghair bot ke awaz test kar deta hai:
   "Video aa gayi! Ye test awaz hai".
6. **Koi token nahi hai** — na APK me, na app ki settings me. Bot ka message
   Telegram app tak aata hai, wahan se notification, wahan se awaz.
7. Zaroori: Telegram me bot wali chat ko **mute na karna** — mute chat ki
   notification nahi aati, to awaz bhi nahi aayegi.

### Permissions

Sirf do: `WAKE_LOCK` (bolte waqt phone jagaye rakhne ke liye) aur
`POST_NOTIFICATIONS`. Notification-read access manifest permission nahi hai —
wo user Settings se deta hai (service `BIND_NOTIFICATION_LISTENER_SERVICE`
se protected hai).

## Build

```bash
export JAVA_HOME=~/android-build/jdk-17.0.20.1+1
export ANDROID_HOME=~/android-sdk ANDROID_SDK_ROOT=~/android-sdk
export GRADLE_USER_HOME=/home/hatch/.gradle
export GRADLE_OPTS="-Xmx1024m -Djava.net.preferIPv4Stack=true"
~/android-build/gradle-8.10.2/bin/gradle assembleRelease --offline -Djava.net.preferIPv4Stack=true
```

(Note: GRADLE_USER_HOME zaroor /home/hatch/.gradle rakhna — root user honay ki
wajah se default /root/.gradle ban jata hai jahan dependency cache nahi hai,
aur daemon "Could not receive a message" error deta hai. preferIPv4Stack bhi
daemon communication ke liye zaroori hai is sandbox me.)

Signing: debug + release dono `~/workspace/android-keystore/proxypilot.jks`
(alias `proxypilot`) se sign hote hain — password build.gradle
`.storepass` file se read karta hai. Isi liye v1.1, v1.0 ke upar install ho
jati hai (signature mismatch nahi hota).
