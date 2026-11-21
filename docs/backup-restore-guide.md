# Backup & restore guide

## One-time setup (owner)
1. **Google Cloud (free):** console.cloud.google.com → new project → *APIs & Services* → enable **Google Drive API**.
2. *OAuth consent screen* → External, Testing; add your own Google account as a test user; scope `.../auth/drive.file`
   (non-sensitive, no verification needed).
3. *Credentials* → Create OAuth client ID → **Android**:
   - debug APK: package `com.pkos.app.debug`, SHA-1 `97:39:AE:B6:D9:13:DA:5B:63:91:C2:A2:CB:B8:72:3E:74:2B:8B:F7`
     (repo key `keystore/debug.keystore`);
   - release: package `com.pkos.app` + the SHA-1 of your own release key.
4. PKOS → Settings → **Backup & Sync** → *Connect Google Drive* → choose account → allow.
5. *Create recovery key* → write it on paper / in a password manager → tick, type the last 4 characters → Done.
   **If you lose this key, your encrypted backups cannot be opened.**
6. *Backup now*, or switch on *Daily automatic backup*.

## Restore on a new phone
Install PKOS → Settings → Backup & Sync → connect the same Google account → *Choose a backup* → type the recovery key →
check the preview → tick "I understand" → *Restore*. The app restarts. Re-enter the Notion token and Gemini key
(secrets are never in backups).

## What is never uploaded
Plaintext data, the recovery key, Notion token, Gemini key, Keystore keys, app settings.
