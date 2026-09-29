# Cloud redaction guard

The keyboard sanitizes the final serialized Gemini request body immediately before OkHttp sends it.

## Why the network boundary

Redaction at the request boundary covers every current AI action and any future Gemini endpoint added through the shared Retrofit client. Individual ViewModel actions do not need to remember to sanitize text independently.

## Values currently redacted

- Credential-shaped assignments such as `password=`, `api_key=`, and `access_token=`
- Email addresses
- Card-like financial numbers
- Social Security numbers
- Long uninterrupted numeric identifiers
- Phone numbers
- IPv4 addresses
- HTTP, HTTPS, and FTP URLs

The sanitizer replaces values with neutral markers and never logs the original request body.

## Scope

This first slice is intentionally always on. A later Trust Prism UI can expose local/cloud/redacted state and user controls after CI proves the request-boundary implementation.

## Applying results (ADR-0002)

Round-trip actions (fix grammar, summarize, translate, rewrite, compose, continue) write the model's output back into the draft, and the model only ever saw markers. `RedactionApplyGuard` refuses Apply/Replace/Insert/Use/Append when the result contains a `[REDACTED_*]` marker the source text did not already contain; the result stays on screen to copy, and the keyboard says which values were hidden. Redaction itself is unchanged. Restoring originals into the result (placeholder round-trip) is a possible later step.

## Validation checklist

- `CloudTextSanitizerTest` passes.
- Debug APK builds.
- Release/R8 build succeeds.
- A request containing an email or credential-shaped value reaches the network layer with a redaction marker instead of the original value.
- Ordinary writing without sensitive patterns is unchanged.
- `RedactionApplyGuardTest` passes: a draft containing a phone/email/long number never acquires a marker through Apply.
- On device: Fix Grammar on `call me at 555-123-4567` → Apply is refused with a message and the draft keeps the number.
