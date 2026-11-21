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
| assembleDebug / assembleRelease | ✅ debug 77 MB (signed, SHA-1 97:39:AE:…:8B:F7), release 69 MB unsigned |

## Known limitations
- Real Google Drive was not reachable from the build sandbox; Drive behaviour is verified with an in-memory Drive and a
  local HTTP server only.
- One full encrypted archive per snapshot (attachments are not uploaded incrementally yet).
- Per-item BACKED_UP / SYNCED states are not set; backup status is shown per snapshot.
- Restore replaces the active library with the backup; items only on the phone stay in the safety snapshot (shown in
  the preview), not merged.
- Argon2id is not available in the platform; PBKDF2-HMAC-SHA256 (600k) is used as the spec allows.
- Release APK is unsigned; Google sign-in needs the owner's release SHA-1 registered.
