# Branch salvage and cleanup

This note records what was salvaged from obsolete comparison branches before cleanup.

## Salvaged into `salvage/product-command-pack`

### Marketplace and negotiation commands

Source: `claude/keyboard-app-continuation-ha3a5a`

Ported as current-architecture slash-command presets in `CommandPalette`:

- `/sell` — strengthen a sales message.
- `/close` — close a sale or agreement and confirm the next step.
- `/counteroffer` — make a counteroffer while keeping the door open.
- `/followup` — follow up and ask for a concrete next step.
- `/boundary` — set a calm, firm boundary.
- `/extract` — extract facts, dates, amounts, promises, unanswered questions, and action items.

The old branch is not merge-ready because it is far behind `main`, uses the old `com.example` package layout, and predates the newer on-device AI, selection-state, prompt, and undo architecture.

### Reply intents

Source: `claude/keyboard-app-continuation-ha3a5a`

Ported as current-architecture reply intents in `ReplyIntents`:

- `Counteroffer`
- `Close sale`

`Close` remains supported as a legacy alias, but the user-facing intent is now `Close sale`.

## Useful ideas not ported in this branch

### `refactor/cleanup-v1`

Good idea: split monolithic UI/ViewModel files into smaller production components.

Do not merge this branch directly. PR #18 already ported the safest parts and explicitly skipped the dead stub components. Treat the branch as an architectural sketch for future small PRs, such as:

- Extract `AiShelf` / AI result cards.
- Extract `GestureOverlay`.
- Extract `KeyboardRows` / `KeyboardMatrix`.
- Extract ViewModel responsibilities into small controllers.

### `ai/trust-hardening-patch`

Good idea: cloud-bound redaction and trust controls.

This should be a separate privacy PR, not mixed with the command-pack salvage. Future work:

- Add an AI-request-boundary `PrivacyTextSanitizer`.
- Add an optional "redact before cloud AI" setting.
- Show a visible cloud/offline/redacted status chip.
- Tighten backup/data extraction rules.

## Branch cleanup candidates

These branches can be deleted from GitHub after this salvage branch is reviewed, unless a human wants to keep them as historical reference:

- `claude/keyboard-app-continuation-ha3a5a` — useful product ideas have been ported here; code is stale.
- `refactor/cleanup-v1` — PR #18 already ported the useful parts; remaining code is a blueprint only.
- `fix-gemini-version-and-gradle-wrapper-662422074903232130` — closed, stale, and far behind `main`; current repo already has wrapper/build handling.

The connected GitHub tool available during this pass could create/update branches and files, but it did not expose a delete-branch/archive-branch action. Delete branch refs from the GitHub UI or CLI once this branch is merged.

Suggested CLI cleanup after review:

```bash
git push origin --delete claude/keyboard-app-continuation-ha3a5a
git push origin --delete refactor/cleanup-v1
git push origin --delete fix-gemini-version-and-gradle-wrapper-662422074903232130
```
