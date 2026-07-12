package io.github.daddymean.agentickeyboard.util

/**
 * Reply intents the user can pick before generating reply suggestions, steering
 * every suggestion in that direction (accept an offer, decline it, negotiate...).
 */
object ReplyIntents {

    val ALL = listOf(
        "Accept",
        "Decline",
        "Negotiate",
        "Counteroffer",
        "Soften",
        "Clarify",
        "Apologize",
        "Confirm",
        "Close sale"
    )

    /** Prompt directive appended to the reply-suggestion request for [intent]. */
    fun promptDirective(intent: String): String = when (intent) {
        "Accept" -> "All replies must accept or agree to what the message proposes."
        "Decline" -> "All replies must politely but clearly decline what the message proposes."
        "Negotiate" -> "All replies must push back and propose different terms while keeping the door open."
        "Counteroffer" -> "All replies must make a clear counteroffer, name the changed term, and keep the conversation moving."
        "Soften" -> "All replies must de-escalate and soften the exchange while keeping the point."
        "Clarify" -> "All replies must ask for the missing details needed before committing."
        "Apologize" -> "All replies must acknowledge fault and apologize sincerely."
        "Confirm" -> "All replies must confirm the arrangement and restate the key detail."
        "Close sale", "Close" -> "All replies must close the sale or agreement, confirm the next step, and make it easy for the other person to say yes."
        else -> ""
    }

    /** Canned offline replies heading in the direction of [intent], or null for unknown intents. */
    fun offlineReplies(intent: String): List<String>? = when (intent) {
        "Accept" -> listOf("Yes, that works.", "Sounds good, I'm in.", "Happy to go ahead with that — thanks!")
        "Decline" -> listOf("No, thank you.", "I'll have to pass this time.", "Thanks for thinking of me, but I can't commit to this.")
        "Negotiate" -> listOf("Can we adjust that?", "I'm interested, but I'd need different terms.", "That's close — could we meet in the middle on this?")
        "Counteroffer" -> listOf("Would you do this instead?", "I can move forward if we adjust the terms a bit.", "I appreciate the offer — my counter would be a little different so it works for both sides.")
        "Soften" -> listOf("No worries at all.", "I understand — let's not stress over it.", "I see where you're coming from; let's figure it out together.")
        "Clarify" -> listOf("Can you clarify?", "What exactly would that involve?", "Before I confirm, could you share a few more details?")
        "Apologize" -> listOf("I'm really sorry.", "My apologies — that's on me.", "I'm sorry about that; it won't happen again.")
        "Confirm" -> listOf("Confirmed, see you then.", "Yes, we're all set as planned.", "Just confirming we're still on — looking forward to it.")
        "Close sale", "Close" -> listOf("Great, let's lock it in.", "Perfect — send me the details and we'll finish it up.", "Sounds good; I'm ready to move forward whenever you are.")
        else -> null
    }
}
