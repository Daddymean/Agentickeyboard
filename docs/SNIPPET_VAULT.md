# Snippet Vault

## Phase 1 boundary

Snippet Vault introduces one local search contract over three user-owned sources:

- saved snippets, which insert reusable text;
- existing shortcut templates, which keep their current expansion behavior; and
- existing custom slash commands, which keep running their rewrite instruction.

The first slice does not replace or copy the existing shortcut and custom-command tables. `SavedSnippet` is a separate Room entity, while `SnippetVaultEntry` is the pure Kotlin domain model that lets all three sources participate in the same deterministic search. This avoids a destructive migration and keeps the current keyboard palette stable while the recall UI is built.

## Recall commands

Only two dedicated leading commands enter vault recall:

- `/v <query>`
- `/find <query>`

Other slash commands continue through `CommandPalette` unchanged. The parser does not treat slash-looking text in the middle of a draft as a command.

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

## Privacy

This slice reads only records the user already stored locally. It does not:

- read the clipboard;
- inspect contacts, recipients, or surrounding app content;
- upload snippets or search queries;
- persist typed recall queries;
- run in secure fields by itself; or
- insert, rewrite, or execute anything automatically.

The later keyboard UI must continue to bypass recall in secure fields and require an explicit tap before inserting text or running a custom rewrite.

## Deferred work

- keyboard chips for `/v` and `/find` results;
- companion-app create, edit, tag, and delete surfaces;
- usage-count updates after explicit selection;
- Keyboard Passport inclusion for saved snippets;
- optional clipboard history, which remains off by default and requires a separate sensitive-content retention design.
