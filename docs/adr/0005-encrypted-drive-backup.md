# ADR 0005 — Encrypted Google Drive backup & restore (M5)

Status: Accepted (2026-11-21)

## Decisions
- **Scope:** `drive.file` only (PKOS sees only files it created). Visible folder **"Personal Knowledge OS Backups"**,
  not appDataFolder (spec 8.1). OAuth via Google Identity Services `AuthorizationClient` (Android OAuth client:
  package + signing SHA-1; no client secret in the app). Free; no paid Google service.
- **Archive:** one versioned file per snapshot (`pkos-backup-<UTC time>-<id>.pkosbackup`) containing the spec 8.2 tree:
  `manifest.json`, `database.sqlite`, `attachments/<sha256>.<ext>`, `metadata/{schema,app}-version.json`,
  `integrity/checksums.sha256`, `encryption/header.json`.
- **DB snapshot:** inside a Room write transaction (no other writer can commit) the DB file + WAL are copied, then the
  copy is checkpointed and `PRAGMA integrity_check`ed.
- **Crypto:** random 256-bit data key per backup; AES-256-GCM in 1 MiB chunks, a fresh random 96-bit nonce per chunk
  and per key wrap; chunk AAD = SHA-256(header) ‖ index ‖ last-flag (reorder/truncation detected). Data key wrapped
  with a KEK from the owner's recovery key via **PBKDF2-HMAC-SHA256, 600 000 iterations, 16-byte salt** (Argon2id is not
  in the platform JCA). The recovery key (~140 bits, base32 groups) is shown once and never stored; the KEK is stored only
  Keystore-encrypted. `recovery_metadata` keeps key id, salt and KDF parameters.
- **Upload:** Drive resumable upload, 8 MiB chunks; session URI and uploaded bytes persisted in `backup_snapshots`,
  so a killed worker resumes the same session. Expired session → fresh session from byte 0.
- **Verification:** VERIFIED only when Drive's `size`, `md5Checksum` and `appProperties.pkos_backup_id` match the local
  encrypted archive. Mismatch → remote copy deleted, row FAILED, local data untouched.
- **Retention:** GFS over verified backups, default 7 daily / 4 weekly / 6 monthly, adjustable; newest always kept;
  only PKOS's own files are deleted.
- **Quota:** `about.storageQuota` checked before upload; upload refused (ACTION_REQUIRED) if it would not fit; warning
  at 90 %.
- **Restore:** download → staging → key check → decrypt (all chunks authenticated) → unzip (no path traversal) → every
  SHA-256 checked → schema ≤ app → integrity_check → preview (items, files, date, app/schema, items only on phone /
  changed) → explicit confirmation → current DB moved to a pre-restore safety snapshot → restored DB placed, missing
  attachments added (never deleted) → app restarts; if the app cannot open/migrate it, the safety snapshot is put back
  on start. Notion mappings, Capture IDs and sync states come back unchanged, so nothing is re-sent.
- **Scheduling:** WorkManager unique `pkos-backup-run` + daily periodic work; network constraint, optional charging;
  back-off with jitter from the service.
