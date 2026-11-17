# APK banane aur phone pe live chalane ka guide

## Sab se aasan tareeqa — seedha APK download (kuch install nahi)

Har build ke baad APK khud yahan aa jati hai (GitHub login ki bhi zaroorat nahi):

**https://github.com/osamacreative01-max/bill-system/raw/apk/app-debug.apk**

Phone ke browser mein ye link kholein → file download → install.
Code update karne ke ~4 minute baad ye link naya APK de deta hai.
Build ka status: https://github.com/osamacreative01-max/bill-system/actions

Neeche diye gaye do raaste sirf tab chahiye jab aap khud code build/change karna chahein.

---

Do raaste hain. Koi ek chunein — dono ka nateeja same APK hota hai
(`app-debug.apk`), jo phone pe install ho jata hai.

---

## Raasta A — GitHub cloud build (kuch install nahi karna)

Zaroorat: GitHub account (free) aur internet. Waqt: ~10 minute.

1. **Account:** https://github.com/signup par account banayein (pehle se ho to skip).
2. **Naya repository:** github.com → upar right **+** → **New repository**
   - Repository name: `mashallah-billing`
   - **Private** select karein
   - **Create repository** dabayein
3. **Code upload:** naye repo ke page par **uploading an existing file** link dabayein.
   - Is folder ka **poora content** drag-and-drop karein:
     `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.gitignore`,
     `app` folder, `gradle` folder, `.github` folder, `README.md`, `LIVE_GUIDE.md`
   - Neeche **Commit changes** dabayein.
4. **Build khud chalega:** repo mein **Actions** tab kholein.
   - "Build APK" workflow chal raha hoga (yellow dot). 5–8 minute lagega.
   - Green tick aa jaye to us run par click karein.
5. **APK download:** run ke page par neeche **Artifacts** → `MashAllahBilling-debug-apk`
   par click karein → ZIP download hogi → extract karein → andar `app-debug.apk`.
6. **Phone pe bhejein:** WhatsApp (apne aap ko), Google Drive, ya USB cable se copy karein.
   Install ke liye "unknown apps" ki permission dena hoga (neeche dekhein).

> Aage jab bhi code badlein, dobara commit karein — naya APK khud ban jayega
> (`Actions → run → Artifacts`).

---

## Raasta B — Android Studio (PC pe)

Zaroorat: ~3–4 GB download, 30–60 minute pehli dafa.

1. **Download:** https://developer.android.com/studio → install (Next, Next, Finish).
2. **Pehli dafa kholein:** Setup Wizard → **Standard** → licenses accept → SDK download
   hone dein. (Gradle 8.9 + Android SDK 35 khud aa jayenge.)
3. **Project kholein:** `File > Open` → ye folder select karein
   (`MashAllahBillingSystem`) → **Trust Project**.
4. **Sync:** Gradle sync khud shuru hoga. Agar "Gradle wrapper" ka message aaye to **OK**
   dabayein (Studio khud wrapper bana deta hai). Sync complete hone tak intezaar karein.
5. **APK banayein:** menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
   Neeche right corner mein notification aayegi → **locate** par click karein.
6. **File yahan milegi:**
   `app/build/outputs/apk/debug/app-debug.apk`
7. Phone USB se jodein aur ye file phone ke `Download` folder mein copy karein.

---

## Phone pe install

1. Phone → **Settings > Apps > Special access > Install unknown apps** →
   jo app se install karenge (Files / WhatsApp / Chrome) us ke liye **Allow**.
2. `app-debug.apk` par tap karein → **Install** → **Open**.
3. Pehli dafa **Nearby devices** permission allow karein (Android 12+).

---

## Live use ki checklist

| # | Kaam |
|---|---|
| 1 | Printer charge karein, 58 mm paper roll lagayein (shiny side head ki taraf). |
| 2 | App → **SC03H Printer Connect** → status: "SC03H ready hai" |
| 3 | **Test Print** → parchi saaf aaye. Tab hi aage barhein. |
| 4 | 2–3 item add kar ke **Print Bill** → bill number wali parchi nikalti hai. |
| 5 | Pehle 2–3 din manual register ke sath parallel chalayein. |
| 6 | Roz band hone par **History & Backup → Backup / Share** → WhatsApp (khud ko) bhej dein. |

**Zaroori baat:** bill data sirf isi phone ki app memory mein hai.
App uninstall karne ya phone reset hone par history chali jayegi — is liye roz backup share karein.

---

## Naya version install karna

Naya APK usi app ke upar install ho jata hai — **history aur bill number barqarar rehte hain**.
Sirf tab data jata hai jab app **uninstall** karein.

---

## Kuch ghalat ho to

| Masla | Hal |
|---|---|
| GitHub Actions red cross | Run kholein → jo step fail hua us ka log copy kar ke bhejein |
| Android Studio sync fail | Message copy karein; aksar SDK path (`local.properties`) ka masla hota hai — Studio khud bana deta hai, dobara sync karein |
| APK install nahi ho rahi | "Install unknown apps" allow karein; purani app ho to pehle uninstall? Nahi — same app upar install hoti hai, bas version code barha hona chahiye |
| Print nahi nikal raha | App mein **Debug log** button dabayein → **Share** → log WhatsApp par khud ko bhejein. Us log se pata chalta hai print kis step par ruka |
