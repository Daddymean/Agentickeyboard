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

## Privacy

Snippet Vault reads only records the user already stored locally. It does not:

- read or retain clipboard history;
- inspect contacts, recipients, or surrounding app content;
- upload snippets or search queries;
- persist typed recall queries;
- render in secure fields;
- insert, rewrite, or execute anything automatically; or
- add analytics or a cloud account requirement.

Opening the manager requires an explicit tap. The manager activity is not exported to other apps.

## Deferred work

- Keyboard Passport inclusion for saved snippets;
- optional clipboard history, which remains off by default and requires a separate sensitive-content retention design;
- a future shortcut from the main companion-app navigation, if the dedicated keyboard entry point proves useful in testing.
