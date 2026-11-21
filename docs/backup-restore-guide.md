# Backup & restore guide

## One-time setup (owner) — Google Cloud OAuth (free)
1. console.cloud.google.com → create a project (e.g. "PKOS"). No billing account is needed.
2. *APIs & Services → Library* → **Google Drive API** → Enable.
3. *Google Auth Platform → Branding* (OAuth consent screen): app name "PKOS", your email as support/developer contact.
   *Audience*: **External**, publishing status **Testing**, add your own Google account under *Test users*.
4. *Data Access* → Add scope `https://www.googleapis.com/auth/drive.file` (non-sensitive; no Google verification).
   Do not add `drive`, `drive.readonly` or `drive.appdata`.
5. *Clients* → Create client → type **Android**:
   - Package name: `com.pkos.app.debug` (debug APK)
   - SHA-1: `7D:B7:18:EF:46:EE:70:05:DC:71:A9:9B:56:C8:3A:87:EE:49:48:9A` (the debug APK delivered on 2026-11-21)
   - For a release build later: a second Android client with package `com.pkos.app` and your own release-key SHA-1.
   No client secret or JSON file goes into the app (Android clients are identified by package + SHA-1).
6. If you created an Android client earlier with SHA-1 `97:39:AE:…:8B:F7`, **delete it**: that debug key was public in
   the repository and is no longer used.
7. PKOS → Settings → **Backup & Sync** → *Connect Google Drive* → choose the test-user account → allow.
8. *Create recovery key* → write it on paper / in a password manager → tick, type the last 4 characters → Done.
   **If you lose this key, your encrypted backups cannot be opened.**
9. *Backup now*, or switch on *Daily automatic backup*.

If Google shows "DEVELOPER_ERROR" / code 10, the package name or SHA-1 in the Android client does not match the
installed APK. Check the SHA-1 with `keytool -printcert -jarfile app-debug.apk`.

## Debug signing key (developers)
The debug key is **not** in Git. Each machine creates its own and points `local.properties` (git-ignored) at it:
```
pkos.debugKeystore=/home/<you>/.android/pkos/pkos-debug.keystore
pkos.debugKeystorePassword=<random>
pkos.debugKeyAlias=pkosdebug
```
Without these lines the standard Android debug key is used. A different key = a different SHA-1, so register that
SHA-1 as another Android OAuth client.

## Restore on a new phone
Install PKOS → Settings → Backup & Sync → connect the same Google account → *Choose a backup* → type the recovery key →
check the preview → tick "I understand" → *Restore*. The app restarts. Re-enter the Notion token and Gemini key
(secrets are never in backups).

## What is never uploaded
Plaintext data, the recovery key, Notion token, Gemini key, Keystore keys, app settings.
