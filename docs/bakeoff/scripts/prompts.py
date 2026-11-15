"""Synthetic rewrite prompts for the bake-off. Every message is made up.
Prompt text mirrors GemmaLitertEngine.promptForRewrite on main @1aee381."""
INSTR = {
 "SHORTEN": "Rewrite the following English text so it is noticeably shorter and tighter, keeping the meaning.",
 "ELABORATE": "Rewrite the following English text so it is longer and more detailed, keeping the meaning.",
 "FRIENDLY": "Rewrite the following English text in a warmer, friendlier tone, keeping the meaning.",
 "PROFESSIONAL": "Rewrite the following English text in a more formal, professional tone, keeping the meaning.",
}
def prompt(tone, text):
    return f"{INSTR[tone]} Return only the rewritten text, with no quotes or commentary.\n\n{text}"

# (id, tone, synthetic message, facts that must survive)
CASES = [
 ("s1","SHORTEN","Hey so I just wanted to let you know that I'm going to be running a little bit late tonight because the traffic on the bridge is really bad, probably like 20 minutes or so, sorry about that.",["20"]),
 ("s2","SHORTEN","I was thinking that maybe we could possibly move the soccer practice pickup from 5:30 to 6:00 on Thursday if that works for everyone, because I have a dentist appointment that runs late.",["5:30","6:00","Thursday"]),
 ("s3","SHORTEN","Just a reminder that the library books are due back on Friday and there is a fine of 25 cents per day for each book that is late, so please put them by the front door tonight.",["Friday","25"]),
 ("s4","SHORTEN","Can you please pick up two gallons of milk, a loaf of bread and some bananas on your way home from work today if you get a chance? Thanks so much.",["milk","bread","bananas"]),
 ("s5","SHORTEN","I wanted to follow up on the email I sent last week about the invoice for $340 because I have not heard back yet and I just want to make sure it did not get lost.",["340"]),
 ("e1","ELABORATE","Can't make it Saturday. Sorry.",["Saturday"]),
 ("e2","ELABORATE","Dinner at 7, bring the salad.",["7","salad"]),
 ("e3","ELABORATE","Car is in the shop until Tuesday.",["Tuesday"]),
 ("e4","ELABORATE","Thanks for the help with the move.",["move"]),
 ("e5","ELABORATE","Meeting moved to room 4B.",["4B"]),
 ("f1","FRIENDLY","You forgot to take the trash out again.",["trash"]),
 ("f2","FRIENDLY","Send me the photos from the trip.",["photos"]),
 ("f3","FRIENDLY","The rent is due on the 1st. Pay it on time.",["1st"]),
 ("f4","FRIENDLY","I need the drill back by Sunday.",["drill","Sunday"]),
 ("f5","FRIENDLY","Your dog was barking all night.",["dog"]),
 ("p1","PROFESSIONAL","hey can u send me that report by tmrw? need it for the 9am thing",["report","9"]),
 ("p2","PROFESSIONAL","sorry i messed up the numbers, the total is actually 1,250 not 1,520",["1,250","1,520"]),
 ("p3","PROFESSIONAL","im gonna be out next week, ping Dana if anything breaks",["Dana"]),
 ("p4","PROFESSIONAL","the printer on floor 3 is busted again lol",["3"]),
 ("p5","PROFESSIONAL","yeah we can do the call on Wed at 2, send an invite",["Wed","2"]),
]
