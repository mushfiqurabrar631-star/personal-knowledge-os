# M5 — Cloud backup & disaster recovery: report

Date: 2026-11-21. Status: **built and tested; NOT complete until a real Google Drive backup and a clean-install
restore are verified on the owner's phone.** Design: [ADR 0005](adr/0005-encrypted-drive-backup.md) ·
Setup: [backup-restore-guide.md](backup-restore-guide.md).

## Tests (all pass)
| Suite | Result |
|---|---|
| :core:notion (unchanged) | 56/56 |
| :core:data — BackupCryptoTest 5 (round trip 0 B…2 MiB+, no plaintext, unique nonces, wrong key, tamper/truncation, retention), BackupRestoreTest 14 (encrypted+verified upload, missing key / Drive auth, interrupted upload resume, expired session, MD5 mismatch never VERIFIED, full Drive, retention prune, clean-install restore incl. attachments + Notion mappings + no resend, wrong key, corrupted archive, inner checksum mismatch, newer schema refused, failed restore rollback, app-cannot-open rollback), migration 1→5 | 73/73 |
| :app — DriveRestClientTest 4 (MockWebServer: folder, resumable upload/resume, error mapping, quota/download), BackupFlowTest 2, all M1–M4 suites | 44/44 |
| Screenshots M1–M5 | 36/36 (M5: 4 images) |
| Lint app + core:data | 0 errors (advisory warnings only) |
| assembleDebug / assembleRelease | ✅ debug (signed with a local, untracked key, SHA-1 7D:B7:18:…:48:9A), release unsigned |

## Known limitations
- Real Google Drive was not reachable from the build sandbox; Drive behaviour is verified with an in-memory Drive and a
  local HTTP server only.
- One full encrypted archive per snapshot (attachments are not uploaded incrementally yet).
- Per-item BACKED_UP / SYNCED states are not set; backup status is shown per snapshot.
- Restore replaces the active library with the backup; items only on the phone stay in the safety snapshot (shown in
  the preview), not merged.
- Argon2id is not available in the platform; PBKDF2-HMAC-SHA256 (600k) is used as the spec allows.
- Release APK is unsigned; Google sign-in needs the owner's release SHA-1 registered.

## Security correction (2026-11-21)
- The shared debug keystore committed in `a629076` was removed from Git tracking and from the working tree;
  `keystore/`, `*.keystore`, `*.jks`, `*.p12`, `signing.properties`, `local.properties` are git-ignored.
- A new debug key (RSA 3072, PKCS12, random password) lives outside the repository (`~/.android/pkos/`); Gradle reads
  its path/password from `local.properties`. New debug SHA-1: `7D:B7:18:EF:46:EE:70:05:DC:71:A9:9B:56:C8:3A:87:EE:49:48:9A`.
- The old key is still in Git history (commit a629076). It must be treated as public: never register its SHA-1
  (`97:39:AE:…`), and delete any OAuth client that used it. It never signed a release build.
- This build environment is temporary: if it is recreated, a new debug key (new SHA-1) is generated and must be
  registered again.
