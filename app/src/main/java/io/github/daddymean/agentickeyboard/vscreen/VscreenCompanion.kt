package io.github.daddymean.agentickeyboard.vscreen

/**
 * Plans a localhost Agent-VScreen call. Returns [VscreenDecision.Allow] only
 * when the feature is on, the token is present, the host is loopback, and
 * [VscreenPrivacy] does not block the target. Otherwise nothing is sent.
 *
 * This type is the public entry for the isolated package. A later UI can
 * paste a token and flip [VscreenAction.enabled]; until then the default is
 * off and no HTTP client is invoked from the IME.
 */
object VscreenCompanion {

    fun plan(action: VscreenAction): VscreenDecision {
        if (!action.enabled) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.DISABLED, "companion is off")
        }
        val token = VscreenToken.normalize(action.token)
            ?: return VscreenDecision.Refuse(VscreenDecision.Reason.MISSING_TOKEN, "no token")
        if (!VscreenEndpoint.isLoopbackHost(action.host)) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.BAD_HOST, "host must be loopback")
        }
        if (!VscreenEndpoint.isValidPort(action.port)) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.BAD_PORT, "port out of range")
        }
        val privacy = VscreenPrivacy.evaluate(
            targetPackage = action.targetPackage,
            inputType = action.inputType,
            imeOptions = action.imeOptions,
            hints = action.hints,
            secureWindow = action.secureWindow,
            ownPackage = action.ownPackage,
            secureApps = action.secureApps,
        )
        if (privacy.blocked) {
            return VscreenDecision.Refuse(
                VscreenDecision.Reason.PRIVACY,
                privacy.blocks.joinToString(",") { it.name },
            )
        }
        val url = VscreenEndpoint.runUrl(action.host, action.port)
            ?: return VscreenDecision.Refuse(VscreenDecision.Reason.BAD_HOST, "cannot build url")
        if (VscreenEndpoint.urlContainsToken(url, token)) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.MISSING_TOKEN, "token leaked into url")
        }
        val request = VscreenHttpRequest(
            method = "POST",
            url = url,
            headers = VscreenEndpoint.headers(token),
            body = VscreenEndpoint.runBody(action.args),
        )
        if (token in request.url || token in request.body) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.MISSING_TOKEN, "token leaked into request")
        }
        if (request.headers.keys.singleOrNull() != VscreenEndpoint.AUTHORIZATION) {
            return VscreenDecision.Refuse(VscreenDecision.Reason.MISSING_TOKEN, "token must be only in Authorization")
        }
        return VscreenDecision.Allow(request)
    }
}

/** Normalizes a pasted Agent-VScreen token. Empty / whitespace-only is rejected. */
object VscreenToken {
    fun normalize(raw: String?): String? {
        val token = raw?.trim().orEmpty()
        return token.takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) }
    }

    /** Enough to show that a token is saved, without revealing it. */
    fun mask(token: String): String =
        if (token.length <= 8) "••••" else token.take(4) + "…" + token.takeLast(4)
}
