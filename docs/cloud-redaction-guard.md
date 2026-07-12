# Cloud redaction guard

The keyboard now sanitizes the final serialized Gemini request body immediately before OkHttp sends it.

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
