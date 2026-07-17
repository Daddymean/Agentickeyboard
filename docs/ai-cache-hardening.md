# AI response-cache hardening

The cloud response cache now uses typed in-memory stores instead of a shared `Any` map.

## Correctness

Every key includes the model and all inputs that can change the generated prompt. This fixes the previous summary and translation keys, which omitted personalization context and could return a response generated for a different persona or writing context.

## Privacy

Cache keys are SHA-256 digests built from length-prefixed inputs. Raw typed text, email addresses, identifiers, and personalization context are no longer retained inside map keys.

Cached response values remain process-memory only and expire after ten minutes. Each typed cache also has a small LRU size limit.

## Typed namespaces

Separate caches are used for:

- grammar responses
- reply suggestions
- plain text results
- tone-analysis responses

This removes unchecked casts from `GeminiManager` and prevents one response type from being read as another.
