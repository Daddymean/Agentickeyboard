# Validation checklist

- [ ] `CloudTextSanitizerTest` passes.
- [ ] Debug APK builds.
- [ ] Release/R8 build succeeds.
- [ ] A request containing an email or credential-shaped value reaches the network layer with a redaction marker instead of the original value.
- [ ] Ordinary writing without sensitive patterns is unchanged.
