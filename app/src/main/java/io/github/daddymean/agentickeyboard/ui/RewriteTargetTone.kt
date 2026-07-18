package io.github.daddymean.agentickeyboard.ui

/**
 * Resolves the current persona when a rewrite fails outside the local success
 * path's captured tone scope. The local `targetTone` in `rewriteTone` shadows
 * this value while the request is running; the catch path uses this fallback.
 */
internal val KeyboardViewModel.targetTone: String
    get() = effectivePersona()
