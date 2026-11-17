# MashAllah Billing System — Android (SC03H thermal printer)

Offline bill entry + 58 mm (384-dot) thermal printing over Bluetooth LE for the
**SC03H / iPrint** printer (and its clones: FC02, GB01, GB02, WalkPrint, FunPrint).

Ye ek Android Studio **source project** hai — compiled APK nahi.

## Kya kaam karta hai

- Item name + quantity + rate add karna, per-item delete, running total (Rs)
- Bill app band hone ke baad bhi mehfooz (SharedPreferences) — rotation/restart pe data nahi jata
- **Bill No.** + date/time + itemised total ke saath 384-dot receipt
- **Test Print** button (chhota print — naya printer pehli dafa isi se test karein)
- **Print Bill** — SC03H binary protocol (packet + CRC-8) ke saath
- BLE: scan → connect → notify subscribe → MTU negotiate → paced 180-byte chunks
- Android 12+ (Nearby devices permission) aur Android 6–11 (location permission) dono handle
- Printer battery % aur "paper khatam" status (jab printer bhejta ho)
- **Debug log** — connect/print ke har step ka log; screen par aakhri 5 lines, "Debug log" button se poora log + WhatsApp share
- **Bill history** — har print hone wala bill save, main screen par "Aaj ki sale"
- **Backup / Share** — poora CSV (WhatsApp / email / Drive pe bhejne ke liye)

## Files

```
build.gradle.kts, settings.gradle.kts, gradle.properties
gradle/wrapper/gradle-wrapper.properties      (Gradle 8.9)
app/build.gradle.kts                          (compileSdk 35, minSdk 23)
app/src/main/AndroidManifest.xml              (BLE permissions)
app/src/main/java/com/mashallah/billing/MainActivity.kt
.github/workflows/build-apk.yml               (cloud build — bina install APK)
LIVE_GUIDE.md                                 (APK banane + install karne ka guide)
```

## Build

**Aasan tareeqa:** `LIVE_GUIDE.md` kholein — do raaste diye hain (GitHub cloud build,
ya Android Studio). Chhota khulasa:

1. Android Studio (Giraffe ya naya) → **Open** → ye folder select karein.
2. Gradle sync chalne dein (pehli dafa Gradle 8.9 + SDK download hoga).
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.
4. Phone pe install karein. Zaroorat: Android SDK 35, JDK 17.

## Test karne ka tareeqa (ahem)

1. Printer **on** karein, paper roll lagayein, Bluetooth on rakhein.
2. App kholein → **SC03H Printer Connect** → permission allow karein → 15 second tak scan.
   Status mein "SC03H ready hai" aana chahiye.
3. Pehle **Test Print** dabayein — chhoti si parchi niklegi.
   - Text saaf aa gaya → sab theek hai, ab **Print Bill** use karein.
   - Kuch nahi chhapa → neeche troubleshooting dekhein.
4. **Print Bill** → poora bill, bill number aur total ke saath.

## Troubleshooting

| Status / masla | Wajah aur hal |
|---|---|
| "SC03H nahi mila. Qareeb ke devices: …" | Status mein jo names aayein wo bhejein — ho sakta hai aapka printer ka naam list se different ho. Printer paas rakhein, off/on kar ke dobara scan karein. |
| "Printer ka write channel nahi mila" | Firmware alag hai (sirf standard ESC/POS). Is case mein SC03H protocol kaam nahi karega. |
| **Print / Test Print dabane par kuch nahi hota** | **Debug log** button dabayein → poora log dikhega (kis step par ruka). "Share" se WhatsApp par khud ko bhej dein. Ye log hi asli wajah batata hai. |
| "Print nahi hua: printer ready nahi hai" | Printer connect nahi hai ya link toot gaya — **SC03H Printer Connect** dobara dabayein. |
| Connect hua magar paper khali | Battery charge karein; paper ka shiny side printing head ki taraf hona chahiye; **Test Print** dobara. |
| Print fail (status 133) | Bluetooth link toot gaya. Printer off/on kar ke app mein dobara connect karein. |
| Aadha bill chhapa | Printer buffer — app 180-byte chunks bhejta hai aur har ~4 KB par 600 ms ka drain pause deta hai; print ke doran printer ke paas na rahein aur app background mein na karein. |
| "Paper khatam hai" | Naya roll lagayein. |

## Protocol note (zaroori)

SC03H protocol community reverse-engineering se hai (CRC-8/ATM poly `0x07`, packet
`51 78 <op> 00 <len16le> <payload> <crc8> FF`, 384 dot width, 48 bytes per row).
Code is documentation ke mutabiq likha gaya hai, lekin **har firmware pe verify nahi hua**.
Pehla asli test Test Print se karein. Agar us printer pe kuch na chhape to likely
farq in cheezon mein hoga: notify characteristic UUID, energy value (`0xAF`), ya
drawing mode (`0xBE`).

## Abhi baqi (agli marhala)

- Rate sirf poore rupay (decimal/paisa nahi) — `BillItem.rate` `Int` hai
- Item edit (delete hai, edit nahi)
- History se **reprint** aur bill delete
- Discount / tax / service charge
- Mahine ki sale report, expense/profit
- CSV file attachment (abhi CSV text share hota hai, file nahi)
- Print queue retry aur multi-copy

## Reference

Protocol guide: https://github.com/shubhambelbase/mini-print-studio/blob/main/iPrint_SC03h_Protocol_Documentation.md
