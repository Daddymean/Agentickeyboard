package io.github.daddymean.agentickeyboard.ondevice

/**
 * Pure decision for whether a model download may start.
 *
 * Downloads require an explicit opt-in and a prior acknowledgement of the
 * file size. They run over unmetered (Wi-Fi) networks by default; cellular
 * needs a separate allow flag. Never bundles, never starts in the background
 * unprompted.
 */
object ModelDownloadPolicy {

    data class Request(
        val userOptedIn: Boolean,
        val sizeAcknowledged: Boolean,
        val unmeteredNetwork: Boolean,
        val allowMetered: Boolean,
        val alreadyReady: Boolean,
        val alreadyInProgress: Boolean
    )

    sealed class Decision {
        data object Start : Decision()
        data object AlreadyReady : Decision()
        data object InProgress : Decision()
        data class Refuse(val reason: Reason) : Decision()
    }

    enum class Reason {
        OPT_IN_REQUIRED,
        SIZE_NOT_ACKNOWLEDGED,
        WIFI_REQUIRED
    }

    fun decide(request: Request): Decision {
        if (request.alreadyReady) return Decision.AlreadyReady
        if (request.alreadyInProgress) return Decision.InProgress
        if (!request.userOptedIn) return Decision.Refuse(Reason.OPT_IN_REQUIRED)
        if (!request.sizeAcknowledged) return Decision.Refuse(Reason.SIZE_NOT_ACKNOWLEDGED)
        if (!request.unmeteredNetwork && !request.allowMetered) {
            return Decision.Refuse(Reason.WIFI_REQUIRED)
        }
        return Decision.Start
    }
}
