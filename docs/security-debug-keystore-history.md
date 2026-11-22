# Old debug keystore in Git history — assessment and proposal

**Status: no action taken. Nothing has been force-pushed. Owner approval is needed for Option B.**

## What is in history
- `keystore/debug.keystore` was added in commit `a629076` (2026-11-21) and removed in `7681c9b` (M5 security fix).
- Its passwords were the public Android defaults (`android` / `androiddebugkey`), so anyone with the file can sign APKs with it.
- It was only ever used for **debug** builds (`com.pkos.app.debug`). The release app (`com.pkos.app`) was never signed with it.
- Since `7681c9b` debug builds use a new local key that is not in Git (`local.properties`/`keystore/` are ignored).

## Real risk
Android only installs an update if it is signed with the **same** key as the installed app. The old key is therefore
dangerous only if a phone still has a `com.pkos.app.debug` build that was signed with the old key: someone could
then push a fake "update" signed with that key. If a Google OAuth client was registered with the old SHA-1, that
client would also accept APKs signed with the old key.

## Option A — recommended, non-destructive (owner does it, 5 minutes)
1. On the phone: Settings → Apps → *PKOS (debug)*. If it was installed **before 2026-11-21 M5 fix**, uninstall it and
   install the current debug APK (signed with the new local key). An installed app's signing key can also be checked with
   `apksigner verify --print-certs app.apk` — the current SHA-1 starts with `7D:B7`.
2. Google Cloud Console → APIs & Services → Credentials → Android OAuth clients: keep only the client with the **new**
   SHA-1 (`7D:B7:…:48:9A`); delete any client registered with the old key's SHA-1.
3. Never install an APK of `com.pkos.app.debug` from anywhere except your own build or this repo's CI artifact.

After these steps the old key can sign nothing that your phone or your OAuth setup accepts. History stays intact, all
commit IDs in the reports stay valid.

## Option B — remove the file from history (destructive, only with explicit approval)
Rewrites every commit after `a629076`: all commit IDs change (the IDs quoted in m5–m7 reports become invalid), every
clone must be re-cloned, open PRs break. GitHub may keep the old commits reachable by ID until GitHub Support purges
caches, and any fork keeps the file. Option A is still required afterwards.

```bash
# on a fresh mirror clone, never in the working copy
git clone --mirror https://github.com/mushfiqurabrar631-star/personal-knowledge-os.git pkos-mirror.git
cd pkos-mirror.git
pip install git-filter-repo
git filter-repo --path keystore/debug.keystore --invert-paths
git log --all -- keystore/debug.keystore          # must print nothing
git push --force --mirror                         # ← the destructive step; needs owner approval
```
Then ask GitHub Support to purge cached views of the old commits and re-clone everywhere.

**Recommendation:** Option A now. Option B is optional hygiene; the file contains only a debug key with public
passwords, no user data, token or API key.
