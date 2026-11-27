# মালিকের করণীয় — Final Testing শুরুর আগে (ধাপে ধাপে)

এই কাজগুলো **শুধু আপনি, নিজের কম্পিউটারে/অ্যাকাউন্টে** করবেন। আমাকে (বা কাউকে) কখনো দেবেন না:
keystore ফাইল, keystore/key পাসওয়ার্ড, Notion token, Gemini/API key, রিকভারি কী, Google পাসওয়ার্ড।
নিচের যেগুলো জানালে সমস্যা নেই (এগুলো গোপন নয়): release SHA-1/SHA-256 fingerprint, Gradle checksum, "ধাপ X শেষ"।

| ধাপ | কী | কতক্ষণ | কোন পরীক্ষা খোলে |
|---|---|---|---|
| ১ | Release keystore তৈরি ও নিরাপদে রাখা | ১০ মিনিট | AUTO-03/04 |
| ২ | Signed release build | ১৫–২০ মিনিট | DEV, CAP… (সব ফোন-পরীক্ষা) |
| ৩ | Release OAuth client | ১০ মিনিট (+ ৫–১০ মিনিট অপেক্ষা) | OAUTH-02, BAK, NOT-09/10, FLOW |
| ৪ | Gradle checksum | ১০ মিনিট | reproducible build |
| ৫ | ফোন ও ডাটা প্রস্তুতি | ২০ মিনিট | PR-SAFE, PR-FIX |
| ৬ | Notion প্রস্তুতি ও অনুমতি | ১০ মিনিট | NOT |

---

## ধাপ ০ — কম্পিউটারে যা লাগবে
- Git, এবং **JDK 21** (Temurin) বা Android Studio (এর ভেতরের JDK চলবে)।
- Android SDK (Android Studio → SDK Manager): *Android 17 (API 37.2) platform* ও *Build-Tools 37.0.0*।
- Repository: `git clone https://github.com/mushfiqurabrar631-star/personal-knowledge-os` (আগে থাকলে `git pull`)।
- Repo ফোল্ডারে `local.properties` ফাইলে শুধু: `sdk.dir=<আপনার Android SDK-এর পথ>`।

## ধাপ ১ — Release keystore তৈরি
1. Repo-র **বাইরে** একটি ফোল্ডার বানান, যেমন `~/secure/pkos` (Windows: `C:\Users\<আপনি>\secure\pkos`)।
2. ওই ফোল্ডারে টার্মিনাল খুলে চালান:
   ```
   keytool -genkeypair -v -keystore pkos-release.jks -alias pkos -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PKOS, O=Personal"
   ```
   (Windows-এ `keytool` না পেলে: `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`।)
3. পাসওয়ার্ড চাইলে **লম্বা এলোমেলো পাসওয়ার্ড** দিন (পাসওয়ার্ড ম্যানেজারে তৈরি করুন)। key-এর আলাদা পাসওয়ার্ড চাইলে একই দিন।
4. `pkos-release.jks` ও পাসওয়ার্ড **দুই জায়গায়** রাখুন: পাসওয়ার্ড ম্যানেজার + এনক্রিপ্টেড USB/অফলাইন কপি।
   ইমেইল, চ্যাট, সাধারণ Drive/GitHub-এ নয়। এটি হারালে PKOS-এর আর কখনো আপডেট বানানো যাবে না।
5. যাচাই: `git status` চালালে `.jks` কোথাও দেখাবে না (repo-র বাইরে আছে বলে)।

## ধাপ ২ — Signed release build (AUTO-03, AUTO-04)
**Linux/macOS** (repo ফোল্ডারে):
```
export PKOS_RELEASE_STORE_FILE=~/secure/pkos/pkos-release.jks
export PKOS_RELEASE_KEY_ALIAS=pkos
read -rs PKOS_RELEASE_STORE_PASSWORD; export PKOS_RELEASE_STORE_PASSWORD     # টাইপ করুন, Enter — স্ক্রিনে দেখাবে না
./scripts/build-signed-release.sh
unset PKOS_RELEASE_STORE_PASSWORD
```
**Windows (PowerShell)**, repo ফোল্ডারে:
```
$env:PKOS_RELEASE_STORE_FILE = "$HOME\secure\pkos\pkos-release.jks"
$env:PKOS_RELEASE_KEY_ALIAS = "pkos"
$s = Read-Host "Keystore password" -AsSecureString
$env:PKOS_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
.\gradlew.bat --no-daemon clean :app:assembleRelease
.\gradlew.bat --no-daemon :app:bundleRelease
Remove-Item Env:PKOS_RELEASE_STORE_PASSWORD
```
ফলাফল যাচাই:
- `app\build\outputs\apk\release\` — ৫টি `*-release.apk` (নামে `unsigned` **থাকবে না**), এবং `app\build\outputs\bundle\release\app-release.aab`।
- `apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk`
  (apksigner আছে `<SDK>/build-tools/37.0.0/`-এ) → "Verified using v2 scheme: true", "v3: true"; SHA-1 নোট করুন।
- `app/build/outputs/mapping/release/mapping.txt` এই release-এর সাথে নিরাপদে রেখে দিন (crash পড়তে লাগবে)।
- ফোনে নেওয়ার ফাইল: `app-arm64-v8a-release.apk` (না চললে `app-universal-release.apk`)।

## ধাপ ৩ — Release Android OAuth client (Google Drive-এর জন্য)
আগের সব সেটিং (project, consent screen *Testing*, test user, `drive.file` scope, debug client) **যেমন আছে থাকবে** — শুধু একটি client যোগ।
1. ধাপ ২-এর SHA-1 নিন (বা: `keytool -list -v -keystore ~/secure/pkos/pkos-release.jks -alias pkos` → "SHA1:" লাইন; পাসওয়ার্ড নিজে টাইপ করবেন)।
2. console.cloud.google.com → আপনার PKOS project → **Google Auth Platform → Clients → Create client**।
3. Application type: **Android** · Name: `PKOS release` · Package name: **`com.pkos.app`** · SHA-1: ধাপ ১-এর মান (`AA:BB:…` ফরম্যাট)।
4. Create → কোনো JSON/secret ডাউনলোড করার দরকার নেই।
5. *Data Access*-এ scope শুধু `.../auth/drive.file` আছে কিনা দেখুন; *Audience*-এ আপনার অ্যাকাউন্ট test user আছে কিনা দেখুন।
6. ৫–১০ মিনিট অপেক্ষা করুন (Google-এ ছড়াতে সময় লাগে)।
   এখন দুটি Android client থাকবে: `com.pkos.app.debug` (SHA-1 `7D:B7:…:48:9A`) ও `com.pkos.app` (আপনার release SHA-1)।

## ধাপ ৪ — Gradle wrapper checksum
1. ব্রাউজারে খুলুন: **https://gradle.org/release-checksums/** → "v9.9.2" → **Binary-only (-bin) ZIP Checksum** কপি করুন।
2. দ্বিতীয় উৎস থেকে মিলিয়ে নিন: `https://services.gradle.org/distributions/gradle-9.9.2-bin.zip` ডাউনলোড করে
   - Linux: `sha256sum gradle-9.9.2-bin.zip` · macOS: `shasum -a 256 gradle-9.9.2-bin.zip` · Windows: `certutil -hashfile gradle-9.9.2-bin.zip SHA256`
   — দুটো মান হুবহু এক হতে হবে। না মিললে থামুন, আমাকে জানান।
3. দুটো উপায়ের যেকোনো একটি:
   - **(ক) নিজে:** `gradle/wrapper/gradle-wrapper.properties`-এ শেষে একটি লাইন যোগ করুন
     `distributionSha256Sum=<৬৪ অক্ষরের মান>` → `./gradlew --version` (ঠিক থাকলে চলবে, ভুল হলে Gradle থামবে) → commit ও push।
   - **(খ) আমাকে মানটি পাঠান** (এটি প্রকাশ্য তথ্য, গোপন নয়) — আমি যোগ করে, যাচাই করে commit করব।

## ধাপ ৫ — ফোন ও ডাটা প্রস্তুতি
1. **পরীক্ষার ফোন:** Android 8+, ১ GB+ খালি জায়গা, স্ক্রিন লক চালু, তারিখ/সময় স্বয়ংক্রিয়।
2. **আগের ডাটা সুরক্ষা (PR-SAFE):** ফোনে PKOS debug-এ আসল ডাটা থাকলে আগে
   (ক) debug অ্যাপে "এখনই ব্যাকআপ" → "যাচাইসহ সম্পন্ন" দেখুন, (খ) "আমার ডাটা এক্সপোর্ট" → ZIP কম্পিউটারে কপি করুন,
   (গ) রিকভারি কী হাতে রাখুন। পরীক্ষার আগে debug অ্যাপ **uninstall করবেন না** — release আলাদা অ্যাপ হিসেবে পাশে বসবে।
3. **টেস্ট ফাইল (PR-FIX):** GitHub থেকে `testing/fixtures/` ফোল্ডারের ফাইলগুলো ফোনের *Download* ফোল্ডারে নিন;
   `SHA256SUMS.txt`-এর সাথে মিলিয়ে দেখতে পারেন। সব ফাইল কৃত্রিম — কোনো ব্যক্তিগত তথ্য নেই
   (`sensitive-sample.txt`-এর OTP ও কার্ড নম্বর ভুয়া/প্রকাশ্য টেস্ট নম্বর)।
4. Gemini পরীক্ষার জন্য aistudio.google.com থেকে নিজের **বিনামূল্যের** key (কোনো billing চালু করবেন না)।
   OpenAI-compatible এন্ডপয়েন্ট না থাকলে AI-05 "SKIPPED" হবে — কিছু কিনবেন না।

## ধাপ ৬ — Notion প্রস্তুতি ও অনুমতি
1. **PR-NOTION-BASE:** সিঙ্ক চালুর আগে Master Database-এর (ক) property তালিকার স্ক্রিনশট, (খ) পুরোনো ১৫টি এন্ট্রির
   স্ক্রিনশট বা ⋯ → Export → CSV — `testing/evidence/NOT-03-before-*` নামে রাখুন।
2. পরীক্ষায় Master Database-এ **"PKOS-TEST" দিয়ে শুরু হওয়া নতুন পেজ** তৈরি হবে (আনুমানিক ১০–১৫টি)।
   PKOS কখনো Notion পেজ মোছে না — পরীক্ষা শেষে আপনি নিজে সেগুলো archive/delete করবেন। এতে রাজি থাকলে জানান।
3. NOT-06-এ শুধু একটি PKOS-TEST পেজের শিরোনাম Notion-এ হাতে বদলাতে হবে — কখনো পুরোনো ১৫টির কোনোটি নয়।

## শেষ হলে আমাকে যা জানাবেন (কোনো গোপন তথ্য নয়)
- "ধাপ ১–২ শেষ: signed APK তৈরি, apksigner v2/v3 true" (চাইলে SHA-1 fingerprint)
- "ধাপ ৩ শেষ: release OAuth client তৈরি"
- ধাপ ৪: হয় "নিজে যোগ করে push করেছি", নয়তো checksum মানটি
- "ধাপ ৫ শেষ: ব্যাকআপ + এক্সপোর্ট নেওয়া, fixtures ফোনে"
- ধাপ ৬: Notion-এ PKOS-TEST পেজ তৈরির অনুমতি (হ্যাঁ/না)

এরপর আমি Wave 0 (CI) চালাব এবং আপনাকে ফোনের পরীক্ষাগুলো একটি একটি করে দেখাব। প্রতিটির ফল আপনি প্রমাণসহ দেবেন,
তারপরই আমি রেকর্ডে লিখব।
