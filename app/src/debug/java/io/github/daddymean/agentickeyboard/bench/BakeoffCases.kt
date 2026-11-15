package io.github.daddymean.agentickeyboard.bench

import io.github.daddymean.agentickeyboard.ondevice.GemmaLitertEngine
import io.github.daddymean.agentickeyboard.util.OnDeviceTone

/**
 * The 20 synthetic rewrite cases of the model bake-off (docs/bakeoff/). Every
 * message is made up; none comes from a real user. Prompts are built with the
 * app's own [GemmaLitertEngine.promptForRewrite], so the phone run uses exactly
 * the rewrite prompt format the keyboard sends today.
 *
 * Debug source set only: none of this is compiled into release builds.
 */
object BakeoffCases {
    data class Case(val id: String, val tone: OnDeviceTone, val text: String, val facts: List<String>)

    val CASES: List<Case> = listOf(
        Case("s1", OnDeviceTone.SHORTEN, "Hey so I just wanted to let you know that I'm going to be running a little bit late tonight because the traffic on the bridge is really bad, probably like 20 minutes or so, sorry about that.", listOf("20")),
        Case("s2", OnDeviceTone.SHORTEN, "I was thinking that maybe we could possibly move the soccer practice pickup from 5:30 to 6:00 on Thursday if that works for everyone, because I have a dentist appointment that runs late.", listOf("5:30", "6:00", "Thursday")),
        Case("s3", OnDeviceTone.SHORTEN, "Just a reminder that the library books are due back on Friday and there is a fine of 25 cents per day for each book that is late, so please put them by the front door tonight.", listOf("Friday", "25")),
        Case("s4", OnDeviceTone.SHORTEN, "Can you please pick up two gallons of milk, a loaf of bread and some bananas on your way home from work today if you get a chance? Thanks so much.", listOf("milk", "bread", "bananas")),
        Case("s5", OnDeviceTone.SHORTEN, "I wanted to follow up on the email I sent last week about the invoice for \$340 because I have not heard back yet and I just want to make sure it did not get lost.", listOf("340")),
        Case("e1", OnDeviceTone.ELABORATE, "Can't make it Saturday. Sorry.", listOf("Saturday")),
        Case("e2", OnDeviceTone.ELABORATE, "Dinner at 7, bring the salad.", listOf("7", "salad")),
        Case("e3", OnDeviceTone.ELABORATE, "Car is in the shop until Tuesday.", listOf("Tuesday")),
        Case("e4", OnDeviceTone.ELABORATE, "Thanks for the help with the move.", listOf("move")),
        Case("e5", OnDeviceTone.ELABORATE, "Meeting moved to room 4B.", listOf("4B")),
        Case("f1", OnDeviceTone.FRIENDLY, "You forgot to take the trash out again.", listOf("trash")),
        Case("f2", OnDeviceTone.FRIENDLY, "Send me the photos from the trip.", listOf("photos")),
        Case("f3", OnDeviceTone.FRIENDLY, "The rent is due on the 1st. Pay it on time.", listOf("1st")),
        Case("f4", OnDeviceTone.FRIENDLY, "I need the drill back by Sunday.", listOf("drill", "Sunday")),
        Case("f5", OnDeviceTone.FRIENDLY, "Your dog was barking all night.", listOf("dog")),
        Case("p1", OnDeviceTone.PROFESSIONAL, "hey can u send me that report by tmrw? need it for the 9am thing", listOf("report", "9")),
        Case("p2", OnDeviceTone.PROFESSIONAL, "sorry i messed up the numbers, the total is actually 1,250 not 1,520", listOf("1,250", "1,520")),
        Case("p3", OnDeviceTone.PROFESSIONAL, "im gonna be out next week, ping Dana if anything breaks", listOf("Dana")),
        Case("p4", OnDeviceTone.PROFESSIONAL, "the printer on floor 3 is busted again lol", listOf("3")),
        Case("p5", OnDeviceTone.PROFESSIONAL, "yeah we can do the call on Wed at 2, send an invite", listOf("Wed", "2")),
    )

    fun prompt(case: Case): String = GemmaLitertEngine.promptForRewrite(case.text, case.tone)

    /** Short synthetic sentences for the embedding latency check. Made up. */
    val EMBED_TEXTS: List<String> = listOf(
        "The spare key for the garden shed is under the blue flowerpot.",
        "Soccer practice pickup moved to 6:00 on Thursday.",
        "The library books are due back on Friday.",
        "Dinner is at 7 and someone is bringing the salad.",
        "The car is in the shop until Tuesday.",
        "The printer on floor 3 is broken again.",
        "Pancakes use 150 grams of flour and one egg.",
        "The 7:15 ferry was cancelled; the next boat is at 8:00.",
        "The fictional dog Pepper has a vet checkup in August.",
        "Rent is due on the first of the month.",
    )
}
