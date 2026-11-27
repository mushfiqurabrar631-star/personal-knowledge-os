# Release signing and Google OAuth (owner guide)

PKOS 1.0.0 can be built **signed** only on the owner's own computer. The repository and GitHub never contain the
release keystore or any password (`*.jks`, `*.keystore`, `*.p12`, `keystore/`, `signing.properties` and
`local.properties` are git-ignored; `scripts/build-signed-release.sh` refuses a keystore inside the repo folder).
CI builds the release **unsigned**.

## 1. Package names and signing keys

| Build | Application id | Signed with | Used for |
|---|---|---|---|
| debug | `com.pkos.app.debug` | your local debug key (`pkos.debugKeystore` in `local.properties`) or the standard Android debug key | development, Phase 4 testing |
| release | `com.pkos.app` | **your release key** (this guide) | the real app on your phone |
| r8check | `com.pkos.app.r8check` | debug key | CI only (runtime check of the shrunk code); never installed by you |

Debug and release have different ids, so both can be installed side by side and their data never mixes.
**The release key cannot be replaced later**: Android only installs an update signed with the same key. Lose the key
→ you must uninstall (local data is lost unless you have a Drive backup and the recovery key).

## 2. Create the release key (once, on your computer)

```bash
mkdir -p ~/secure/pkos && cd ~/secure/pkos          # any folder OUTSIDE the repository
keytool -genkeypair -v -keystore pkos-release.jks -alias pkos \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -dname "CN=PKOS, O=Personal"                # keytool asks for the password; use a long random one
```
- Store a copy of `pkos-release.jks` **and** the password in two safe places (password manager + offline USB).
- Never email it, upload it to chat, or put it in Google Drive unencrypted.

## 3. Build the signed release

Option A — environment variables (recommended, nothing written to disk):
```bash
export PKOS_RELEASE_STORE_FILE=~/secure/pkos/pkos-release.jks
export PKOS_RELEASE_KEY_ALIAS=pkos
read -rs PKOS_RELEASE_STORE_PASSWORD; export PKOS_RELEASE_STORE_PASSWORD
./scripts/build-signed-release.sh
```
`PKOS_RELEASE_KEY_PASSWORD` is optional (defaults to the store password).

Option B — `local.properties` (git-ignored, stays on your computer):
```
pkos.releaseKeystore=/home/<you>/secure/pkos/pkos-release.jks
pkos.releaseStorePassword=<password>
pkos.releaseKeyAlias=pkos
```

Outputs (signed with APK Signature Scheme v2 + v3):
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk` — most modern phones (≈34 MB)
- `app-armeabi-v7a-release.apk` (older 32-bit phones), `app-x86_64-release.apk`, `app-x86-release.apk` (emulators)
- `app-universal-release.apk` — works on every phone (≈84 MB)
- `app/build/outputs/bundle/release/app-release.aab` — for Google Play (built in a separate Gradle run)
- `app/build/outputs/mapping/release/mapping.txt` — keep it with every release to read crash stack traces.

Without the variables the same commands give `*-release-unsigned.apk` — Android will not install an unsigned APK.

Which APK for my phone? Settings → About phone → processor, or install **universal** if unsure.

## 4. Get the SHA-1 for Google OAuth

```bash
keytool -list -v -keystore ~/secure/pkos/pkos-release.jks -alias pkos | grep SHA1
# or from a built APK:
apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep SHA-1
```

## 5. Google Cloud: add a release Android OAuth client (free)

Your existing project, consent screen (Testing, your account as test user) and the `drive.file` scope stay as they
are — **nothing there changes**. Only add one more client:

1. console.cloud.google.com → your PKOS project → *Google Auth Platform → Clients* → **Create client**.
2. Type **Android**. Package name: `com.pkos.app`. SHA-1: the value from step 4.
3. Keep the existing debug client (`com.pkos.app.debug`, SHA-1 `7D:B7:…:48:9A`) for testing builds.
4. No client secret and no JSON file go into the app; Android clients are matched by package name + SHA-1.

| Client | Package | SHA-1 |
|---|---|---|
| Debug (exists) | `com.pkos.app.debug` | `7D:B7:18:EF:46:EE:70:05:DC:71:A9:9B:56:C8:3A:87:EE:49:48:9A` |
| Release (new) | `com.pkos.app` | your release-key SHA-1 |
| Google Play (only if you publish there later) | `com.pkos.app` | the **App signing key** SHA-1 shown in Play Console → Setup → App integrity |

If Google shows `DEVELOPER_ERROR` / code 10 the package name or SHA-1 does not match the installed APK.

Scope rule: PKOS asks only for `https://www.googleapis.com/auth/drive.file` (files PKOS created). Do not add
`drive`, `drive.readonly` or `drive.appdata`.

## 6. Optional: signed builds in GitHub Actions

Not set up, on purpose. If you ever want it, store the keystore as an encrypted GitHub *Actions secret*
(base64) plus the passwords as secrets, and export them as the `PKOS_RELEASE_*` variables in a job — never as files in
the repository. Until then CI artifacts are unsigned.
