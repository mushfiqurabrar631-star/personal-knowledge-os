# ইনস্টলেশন গাইড — PKOS 1.0.0

## কোন ফাইলটি নেবেন
| ফাইল | কাদের জন্য |
|---|---|
| `app-arm64-v8a-release.apk` | প্রায় সব আধুনিক Android ফোন (২০১৭-এর পরের) — **প্রথম পছন্দ** |
| `app-armeabi-v7a-release.apk` | পুরোনো ৩২-বিট ফোন |
| `app-universal-release.apk` | কোনটা লাগবে নিশ্চিত না হলে — সব ফোনে চলে, তবে বড় (≈৮৪ MB) |
| `app-release.aab` | শুধু Google Play-তে আপলোডের জন্য; সরাসরি ফোনে install হয় না |
| `app-*-debug.apk` | পরীক্ষার build (`com.pkos.app.debug`), আসল অ্যাপের পাশাপাশি আলাদা থাকে |

প্রয়োজন: Android 8.0 (API 26) বা নতুন, ফাঁকা জায়গা অন্তত ৩০০ MB (অ্যাপ + Bengali OCR মডেল + আপনার ফাইল)।

> Release APK শুধু **আপনার নিজের release key দিয়ে সাইন করার পরেই** ফোনে install হবে
> (দেখুন [release-signing-and-oauth.md](release-signing-and-oauth.md))। GitHub Actions-এর release ফাইল unsigned।

## ধাপগুলো
1. নিজের কম্পিউটারে `./scripts/build-signed-release.sh` চালিয়ে signed APK বানান (অথবা debug APK নিন:
   GitHub → Actions → সর্বশেষ সফল run → `pkos-<নম্বর>-debug-apk`)।
2. APK ফোনে পাঠান (USB, বা নিজের Drive থেকে ডাউনলোড)।
3. ফোনে Files অ্যাপ থেকে APK খুলুন → "Install unknown apps" অনুমতি চাইলে ওই অ্যাপের জন্য চালু করুন → Install।
4. Install শেষে "Install unknown apps" অনুমতি আবার বন্ধ করে দিতে পারেন।
5. অ্যাপ খুলুন → পরিচিতি পর্দা → শেষে হোম। এরপর [setup-guide.md](setup-guide.md) অনুযায়ী Notion/Drive/AI (সবই ঐচ্ছিক)।

## আপডেট
- নতুন version একই release key দিয়ে সাইন করা হলে পুরোনোর ওপর সরাসরি install করুন — ডাটা থাকে।
- ভিন্ন key-এর APK Android নেবে না ("App not installed")। তখন uninstall করার **আগে** Drive ব্যাকআপ নিন এবং রিকভারি কী হাতে রাখুন।
- ডাটাবেস স্বয়ংক্রিয়ভাবে নতুন version-এ উন্নীত হয় (v1→v7 পর্যন্ত পরীক্ষিত)।

## Debug থেকে release-এ যাওয়া
Debug (`com.pkos.app.debug`) আর release (`com.pkos.app`) আলাদা অ্যাপ — একটির ডাটা আরেকটিতে নিজে থেকে যায় না।
Debug-এ থাকা ডাটা release-এ নিতে: debug অ্যাপে Drive ব্যাকআপ → release অ্যাপে একই Google অ্যাকাউন্ট + রিকভারি কী দিয়ে restore।
(release অ্যাপের Drive সংযোগের জন্য Google Cloud-এ release OAuth client লাগবে।)

## আনইনস্টল
Settings → Apps → PKOS → Uninstall। ফোনের সব PKOS ডাটা মুছে যায়। Notion পেজ ও Drive ব্যাকআপ আপনার অ্যাকাউন্টে থেকে যায়।
