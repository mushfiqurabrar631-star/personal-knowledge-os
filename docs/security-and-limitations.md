# Security considerations and known limitations — PKOS 1.0.0

Status note: everything below is implemented and covered by automated tests (JVM/Robolectric; device smoke tests run on a
CI emulator). **None of it has been verified on the owner's phone yet** — that is Phase 4 Final Testing
([final-testing-plan.md](final-testing-plan.md)).

## Security model
- **Local first.** Every capture is written to the on-phone database before any network step. A Drive or Notion
  failure never deletes local data.
- **Secrets.** Notion token, Gemini key and OpenAI-compatible key are encrypted with Android Keystore (AES-GCM) and
  stay on that phone. They are never in backups, exports, logs, the repository or CI.
- **Backups.** AES-256-GCM, random nonces, per-attachment data keys wrapped by a key-encryption key derived from the
  recovery key (PBKDF2, 600k iterations). Plaintext, the recovery key and tokens are never uploaded. Restore verifies
  every checksum before replacing data and keeps a safety snapshot.
- **Google scope.** Only `drive.file` (files PKOS created). No `drive`/`drive.readonly`.
- **Cloud AI.** Off by default. Only title, trimmed text, URL, platform and category names are sent; never files,
  notes or local-only items. A sensitive-data check (card numbers with Luhn, OTP, passwords, secret keys, national ID,
  bank account) makes an item local-only before any cloud step. Every cloud send is listed in *Privacy & data*.
- **No silent paid use.** PKOS never enables a paid plan. Quota/payment answers (`429` quota, `402`) stop cloud work
  for that item instead of retrying. The OpenAI-compatible option shows a cost warning: the owner's endpoint may charge.
- **Notion.** No property/option is added, removed or renamed; legacy pages are never touched; owner edits in Notion
  raise a conflict instead of being overwritten.
- **Signing.** Release keystore and passwords exist only on the owner's computer (env vars / git-ignored
  `local.properties`). CI release artifacts are unsigned. v2 + v3 APK signatures.
- **Old debug key.** A debug keystore was once public in Git history (not rewritten, owner decision). It is no longer
  used; see [security-debug-keystore-history.md](security-debug-keystore-history.md) for the clean-up steps.
- **Repository is public.** It contains no secrets; treat any secret that ever appears in it as compromised.
- **R8.** Release code is shrunk/obfuscated; keep rules cover JNI (Tesseract), resource-path users (pdfbox), BouncyCastle
  and stored enum names. Keep `mapping.txt` per release.

## Opt-in features with privacy trade-offs (all off by default)
| Feature | Trade-off |
|---|---|
| Link previews | The linked website (or YouTube's oEmbed service) sees a request from the phone. |
| Large Notion files via Drive | The file is stored **unencrypted** in the owner's Drive ("Personal Knowledge OS Files"); the link opens only for the owner's Google account (sharing is never changed). |
| OpenAI-compatible AI | Data goes to an endpoint the owner chose; its privacy and cost rules apply. |
| Reminders | A notification can show an item title on the lock screen. |

## Known limitations (Phase 1 and earlier)
- Not yet verified on a real phone: Share from real apps, OCR quality, Notion live writes, Drive backup/restore, Gemini,
  TalkBack, battery/performance, release-build runtime (R8) — see the Final Testing Plan.
- Bengali OCR uses the Tesseract 3.04 `ben` model (≈15.5 MB inside the app); accuracy on real photos is not measured.
  Scanned PDFs: first 10 pages only.
- Ask answers one question at a time (no multi-turn chat).
- Meaning-search embeddings only with Gemini; with no cloud AI or with the compatible provider an offline
  related-words + spelling layer is used.
- Export ZIP cannot be imported back; in-app recovery is the Drive restore.
- A full restore replaces the whole local library with the chosen backup.
- Large Notion files without the Drive option are skipped (Notion free plan limit 5 MB).
- ABI-split APKs share one versionCode; install the same ABI variant for updates (or the universal APK).
- The Gradle wrapper checksum (`distributionSha256Sum`) is not pinned yet ([reproducible-build.md](reproducible-build.md)).
- CI emulator tests run on API 34 x86_64 only.
