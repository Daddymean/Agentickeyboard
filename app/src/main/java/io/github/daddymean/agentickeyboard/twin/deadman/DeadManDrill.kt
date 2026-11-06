package io.github.daddymean.agentickeyboard.twin.deadman

import java.security.SecureRandom

/**
 * Round 2: guardian drill using **fake data only**.
 *
 * Walks Keith (or a guardian) through a practice reconstruct with synthetic shares.
 * It never reads or writes the sealed on-device dead-man state, never touches a real release archive, and
 * prefixes every card with [DRILL_PREFIX] so practice material cannot be mistaken for
 * live share cards. Security-sensitive surface → open as HOLD for Keith.
 */
object DeadManDrill {
    const val DRILL_PREFIX = "DRILL-ONLY"
    const val HOLDERS = 3
    const val THRESHOLD = 2
    private const val SECRET_BYTES = 32

    data class DrillCard(val label: String, val card: ShareCard) {
        fun encode(): String = "$DRILL_PREFIX\n${card.encode()}"
    }

    data class Session(
        val setId: String,
        val epoch: Int,
        val cards: List<DrillCard>,
        /** The fake secret; shown only after a successful reconstruct in tests / debug UI. */
        internal val secret: ByteArray,
    )

    data class Result(val ok: Boolean, val message: String)

    /** Starts a new practice session. All material is synthetic. */
    fun start(random: SecureRandom = SecureRandom(), epoch: Int = 0): Session {
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val setId = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val shares = Shamir.split(secret, HOLDERS, THRESHOLD, random)
        val labels = listOf("Guardian A", "Guardian B", "Guardian C")
        val cards = shares.zip(labels).map { (share, label) ->
            DrillCard(label, ShareCard(setId, epoch, THRESHOLD, HOLDERS, share))
        }
        return Session(setId, epoch, cards, secret)
    }

    /**
     * Practice reconstruct from two (or more) encoded drill cards. Rejects anything that
     * is not prefixed [DRILL_PREFIX], so a live share card cannot be fed in by mistake.
     */
    fun reconstruct(session: Session, encodedCards: List<String>): Result {
        if (encodedCards.size < THRESHOLD) {
            return Result(false, "Need at least $THRESHOLD drill cards.")
        }
        val parsed = ArrayList<Shamir.Share>()
        for (raw in encodedCards) {
            val text = raw.trim()
            if (!text.startsWith(DRILL_PREFIX)) {
                return Result(false, "Refused: card is not marked $DRILL_PREFIX. Live cards are never used in a drill.")
            }
            val body = text.removePrefix(DRILL_PREFIX).trim()
            val card = try {
                ShareCard.parse(body)
            } catch (e: Exception) {
                return Result(false, "Could not parse a drill card: ${e.message}")
            }
            if (card.setId != session.setId || card.epoch != session.epoch) {
                return Result(false, "Drill card is from a different practice set.")
            }
            parsed += card.share
        }
        val recovered = try {
            Shamir.combine(parsed.take(THRESHOLD))
        } catch (e: Exception) {
            return Result(false, "Combine failed: ${e.message}")
        }
        return if (recovered.contentEquals(session.secret)) {
            Result(true, "Drill passed: $THRESHOLD of $HOLDERS synthetic cards opened the practice secret. No real twin data was touched.")
        } else {
            Result(false, "Drill failed: reconstructed secret did not match. Try a different pair of cards.")
        }
    }
}
