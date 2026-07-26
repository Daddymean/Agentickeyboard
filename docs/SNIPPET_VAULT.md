# Snippet Vault

## Product boundary

Snippet Vault provides one local search contract over three user-owned sources:

- saved snippets, which insert reusable text;
- existing shortcut templates, which keep their current expansion behavior; and
- existing custom slash commands, which keep running their rewrite instruction.

The feature does not replace or copy the existing shortcut and custom-command tables. `SavedSnippet` is a separate Room entity, while `SnippetVaultEntry` is the pure Kotlin domain model that lets all three sources participate in the same deterministic search. This avoids a destructive migration and keeps the current keyboard palette stable.

## Recall commands

Only two dedicated leading commands enter vault recall:

- `/v <query>`
- `/find <query>`

Other slash commands continue through `CommandPalette` unchanged. The parser does not treat slash-looking text in the middle of a draft as a command.

The recall bar appears only after one of those exact tokens is typed. Matching results are chips, and nothing inserts or runs until the user taps a chip.

### Custom rewrite commands

A custom rewrite result has two explicit behaviors:

- `/v boss` followed by a tap stages the existing `/boss ` slash token. The user can then type the draft and use the normal command-palette flow.
- `/v boss :: move tomorrow's meeting to three` followed by a tap replaces the recall expression with the text after `::` and starts the chosen rewrite instruction.

The `::` form keeps the search query separate from the text being rewritten. Merely typing either form never executes a command.

## Ranking

`SnippetVaultSearch` is local, bounded, and deterministic. It considers, in order of strength:

1. exact title or alias matches;
2. title or alias prefixes;
3. phrase and all-token matches in titles and aliases;
4. tag matches;
5. bounded content matches;
6. a capped usage-count boost.

Ties use usage count, last-used time, updated time, source priority, normalized title, and stable ID. The engine returns at most 20 results and indexes only the first 4,000 characters of a snippet body so an unusually large entry cannot monopolize the IME thread.

## Storage contract

Room database version 7 adds `saved_snippets` with:

- title;
- content;
- newline-delimited aliases;
- newline-delimited tags;
- usage count;
- created, updated, and last-used timestamps.

The v6 to v7 migration creates the new empty table and indices without deleting, rewriting, or duplicating shortcuts or custom commands.

The manager screen can create, edit, tag, alias, and delete saved snippets. Existing shortcuts and custom commands remain managed by their original companion-app surfaces but still participate in unified recall.

Usage count and last-used time are updated only after the user taps a saved-snippet result. Displaying search results does not count as use.

## Optional clipboard history

Clipboard history is a separate, explicit opt-in. It is disabled by default and does not install a background `ClipboardManager` listener. When enabled and not paused, the IME checks only the current plain-text clipboard while the keyboard is visible or after the user taps **Capture**.

Room database version 8 adds `clipboard_history` with:

- normalized plain text;
- an opaque SHA-256 duplicate key;
- pin state;
- created, last-seen, and last-used timestamps.

The v7 to v8 migration creates the empty table and indices without reading the clipboard or changing any existing user data.

### Retention and controls

- duplicate clips collapse by hash and refresh their last-seen time;
- unpinned clips expire after a user-selectable 1, 7, or 30 days;
- at most 20 recent unpinned clips are retained;
- up to 10 clips may be intentionally pinned;
- Pause stops new capture but preserves existing history;
- disabling history stops capture but does not silently delete data;
- individual delete is immediate and explicit;
- Clear now requires a second confirmation and removes all clips, including pins.

### Sensitive-content rejection

`ClipboardHistoryPolicy` runs before Room. It rejects:

- password, PIN, secret, API-key, and token assignments;
- bearer tokens, JWTs, common provider-key formats, and private keys;
- one-time, verification, security, login, and authentication codes;
- Luhn-valid payment-card numbers and CVV/CVC-shaped content;
- SSN-shaped government identifiers;
- seed and recovery phrases with explicit context;
- blank clips and clips over 4,000 characters.

The filter is intentionally conservative. A rejected clip is not partially stored, hashed into the database, logged, uploaded, or echoed in error text.

## Privacy

Snippet Vault and clipboard history do not:

- inspect contacts or recipients;
- persist typed `/v` or `/find` queries;
- render or capture in secure fields;
- insert, rewrite, or execute anything automatically;
- send stored clips to cloud AI merely because they exist;
- add analytics or a cloud account requirement; or
- export either manager activity to other apps.

A user may still explicitly paste a history item into an editor or copy it back to the system clipboard. Existing clipboard AI actions remain separate user-triggered actions and are not invoked by history capture.

## Follow-up testing

The next milestone is release-candidate hardening: migration verification, Compose and screenshot coverage for secure-field suppression and destructive confirmations, and a manual device matrix covering Android clipboard behavior across supported API levels.
