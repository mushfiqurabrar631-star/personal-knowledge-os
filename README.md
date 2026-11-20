# Personal Knowledge OS (PKOS)

Android-first personal knowledge library: share anything → saved on device first →
organized by hybrid AI → encrypted Google Drive backup → synced to the owner's Notion
Master Database. Specification: *Personal Knowledge OS Master Build Specification v1.0*.

## Status
See [CHECKLIST.md](CHECKLIST.md). Current: Notion mapping and sync core (`:core:notion`).

## Modules
| Module | Type | Purpose |
|---|---|---|
| `core/notion` | Kotlin/JVM | Notion schema guard, option-safe mapper, page body, idempotent sync engine |

## Verify locally
```bash
./scripts/verify-core-notion.sh        # works offline with a local Gradle 8.14 install
./gradlew :core:notion:test            # once Maven repositories are reachable
```

## Notion setup (owner)
1. notion.so/profile/integrations → New integration → type **Internal**, capabilities: Read, Update, Insert content.
2. Open *My knowledge Hub → Master Database* → ⋯ → Connections → add the integration.
3. Paste the token in PKOS → Settings → Notion (stored encrypted with Android Keystore).
   Never paste the token in chat, code or GitHub.

Mapping rules: [docs/notion-mapping.md](docs/notion-mapping.md) · Decision: [ADR 0001](docs/adr/0001-notion-target-and-sync.md)
