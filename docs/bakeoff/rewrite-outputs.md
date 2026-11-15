# Rewrite outputs, side by side (synthetic prompts)

All 20 input messages are made up. Prompt = `GemmaLitertEngine.promptForRewrite(text, tone)` on main @1aee381. 
Sampler = the app's (top-k 10, top-p 0.9, temperature 0.2, seed 0), max 256 output tokens, fresh Engine per prompt, 
LiteRT-LM Python 0.19.1, **box CPU (x86_64, 4 threads), not a phone**. LFM3 ran with `ThinkingConfig(enable_thinking=False)`; 
its raw output is shown, and the verdict judges the text after removing the stray `…</think>` prefix.

Verdicts are one rater's (Hax) judgement, not blind, one sample per prompt. Raw JSON: `results/rewrite-*.json`.

## s1 · SHORTEN

**Input:** Hey so I just wanted to let you know that I'm going to be running a little bit late tonight because the traffic on the bridge is really bad, probably like 20 minutes or so, sorry about that.

**Gemma 4 E2B:** I'll be late tonight due to bad bridge traffic, about 20 minutes. Sorry.

> verdict: pass

**LFM3-1.7B int4:** I'll be running late tonight due to bad bridge traffic—about 20 minutes. Sorry.</think>I'll be running late tonight because of bad bridge traffic—about 20 minutes. Sorry.

> verdict: pass (raw: `</think>` leak, duplicated text)

## s2 · SHORTEN

**Input:** I was thinking that maybe we could possibly move the soccer practice pickup from 5:30 to 6:00 on Thursday if that works for everyone, because I have a dentist appointment that runs late.

**Gemma 4 E2B:** Can we move Thursday's soccer practice pickup to 6:00 instead of 5:30? My dentist appointment is running late.

> verdict: pass

**LFM3-1.7B int4:** I suggest moving Thursday's soccer practice pickup from 5:30 to 6:00 because I have a late dentist appointment.</think>I suggest moving Thursday's soccer practice pickup from 5:30 to 6:00 because I have a late dentist appointment.

> verdict: pass (raw: `</think>` leak, duplicated text)

## s3 · SHORTEN

**Input:** Just a reminder that the library books are due back on Friday and there is a fine of 25 cents per day for each book that is late, so please put them by the front door tonight.

**Gemma 4 E2B:** Library books are due Friday. A 25-cent late fee applies daily. Please return them by the front door tonight.

> verdict: pass

**LFM3-1.7B int4:** Library books are due Friday; late books incur a $0.25/day fine. Put them by the front door tonight.

> verdict: pass

## s4 · SHORTEN

**Input:** Can you please pick up two gallons of milk, a loaf of bread and some bananas on your way home from work today if you get a chance? Thanks so much.

**Gemma 4 E2B:** Please pick up two gallons of milk, a loaf of bread, and bananas on your way home from work if you can. Thanks.

> verdict: pass

**LFM3-1.7B int4:** Please pick up two gallons of milk, a loaf of bread, and some bananas on your way home from work today if possible. Thanks!

> verdict: pass (barely shorter)

## s5 · SHORTEN

**Input:** I wanted to follow up on the email I sent last week about the invoice for $340 because I have not heard back yet and I just want to make sure it did not get lost.

**Gemma 4 E2B:** Following up on my email last week regarding the $340 invoice. Please confirm receipt.

> verdict: pass

**LFM3-1.7B int4:** I follow up on last week's email about the $340 invoice—have I heard back?

> verdict: fail: "have I heard back?" garbles the meaning

## e1 · ELABORATE

**Input:** Can't make it Saturday. Sorry.

**Gemma 4 E2B:** I am writing to inform you that I will be unable to attend the event or engagement scheduled for this coming Saturday. Please accept my sincere apologies for any inconvenience this may cause.

> verdict: pass

**LFM3-1.7B int4:** I’m sorry, but I can’t help with that. The original message is extremely brief and doesn’t provide enough context or detail to rewrite it into a longer, more detailed version while preserving its meaning. Please provide more information or a fuller context so I can assist you appropriately.

> verdict: fail: refused ("I'm sorry, but I can't help with that")

## e2 · ELABORATE

**Input:** Dinner at 7, bring the salad.

**Gemma 4 E2B:** The scheduled time for our dinner engagement is precisely seven o'clock in the evening. As a reminder for the evening, please ensure that you bring the salad to share or to be included in the meal.

> verdict: pass (stiff)

**LFM3-1.7B int4:** We are planning to meet for dinner at 7:00 in the evening. Please remember to bring a salad with you. It would be great if you could bring a fresh, crisp salad, perhaps something like a mixed greens or a light vegetable medley, to accompany your meal. This will add a healthy and refreshing touch to the evening's feast. Let us know if you have any dietary restrictions or preferences for the salad, so we can make sure it meets your needs. Looking forward to sharing a delicious and balanced dinner together!

> verdict: minor: invents salad suggestions and asks about dietary needs

## e3 · ELABORATE

**Input:** Car is in the shop until Tuesday.

**Gemma 4 E2B:** The automobile is currently undergoing maintenance and repairs at the dealership or garage, and it is scheduled to be completed by Tuesday at the latest.

> verdict: minor: adds "dealership or garage"

**LFM3-1.7B int4:** The car is currently in the shop and is expected to be ready for pickup again on Tuesday. This means that the vehicle will not be available for use or service until the following day, Tuesday. Customers should plan accordingly and may need to schedule any necessary appointments or pickups after that date. Until then, the car remains unavailable for public use or further maintenance.

> verdict: fail: invents "customers", "public use"; says Tuesday is "the following day"

## e4 · ELABORATE

**Input:** Thanks for the help with the move.

**Gemma 4 E2B:** I would like to express my sincere gratitude for all the assistance you provided during the recent move. Your help was invaluable in making the entire process significantly smoother and less stressful than I had anticipated. I truly appreciate you taking the time and effort to lend your support throughout the entire undertaking.

> verdict: pass

**LFM3-1.7B int4:** I appreciate you taking the time to offer assistance with the upcoming move. It's always a bit of a challenge to coordinate the logistics of relocating personal belongings, especially when it involves packing, transporting items, and ensuring that everything arrives safely at the new address. Your help with the move has been greatly appreciated, and I'm grateful for any guidance or tips you provided. Thank you for your support and for making the transition process smoother for everyone involved.

> verdict: fail: turns a past move into an "upcoming" one

## e5 · ELABORATE

**Input:** Meeting moved to room 4B.

**Gemma 4 E2B:** The previously scheduled meeting has been officially relocated and will now take place in an alternative location, specifically in room 4B.

> verdict: pass (stiff)

**LFM3-1.7B int4:** The meeting has been rescheduled to take place in room 4B. This change in location ensures that all participants can gather in the designated space for the discussion. The room 4B is equipped with the necessary facilities and seating arrangements to facilitate an engaging and productive conversation. Please make sure to arrive at the new location at the scheduled time, and let the team know if there are any specific requirements or accommodations needed for the session.

> verdict: fail: invents room facilities and instructions

## f1 · FRIENDLY

**Input:** You forgot to take the trash out again.

**Gemma 4 E2B:** Hey, just a friendly reminder that we need to take out the trash again!

> verdict: pass

**LFM3-1.7B int4:** Hey, it looks like you missed taking the trash out again—no worries, just let me know when you can take it out!

> verdict: pass

## f2 · FRIENDLY

**Input:** Send me the photos from the trip.

**Gemma 4 E2B:** Could you send me the photos from your trip when you get a chance?

> verdict: pass

**LFM3-1.7B int4:** Hey, could you send over the photos from the trip? I'd love to see them!</think>Hey, could you send over the photos from the trip? I'd love to see them!

> verdict: pass (raw: `</think>` leak, duplicated text)

## f3 · FRIENDLY

**Input:** The rent is due on the 1st. Pay it on time.

**Gemma 4 E2B:** Just a friendly reminder that rent is due on the 1st. We'd really appreciate it if you could take care of that on time!

> verdict: pass

**LFM3-1.7B int4:** The rent is due on the 1st. Please make sure to pay it on time—we appreciate it!

> verdict: pass

## f4 · FRIENDLY

**Input:** I need the drill back by Sunday.

**Gemma 4 E2B:** Could you please bring the drill back to me by Sunday?

> verdict: pass

**LFM3-1.7B int4:** I’d love to get the drill back to you by Sunday—just let me know when you can!

> verdict: fail: reverses who returns the drill

## f5 · FRIENDLY

**Input:** Your dog was barking all night.

**Gemma 4 E2B:** Your dog was barking quite a bit all night long.

> verdict: minor: barely friendlier

**LFM3-1.7B int4:** Your dog was barking all night, but it was just a little noisy and you could have been startled.

> verdict: fail: invents "you could have been startled"

## p1 · PROFESSIONAL

**Input:** hey can u send me that report by tmrw? need it for the 9am thing

**Gemma 4 E2B:** Please submit the report by tomorrow. It is required for the 9:00 AM meeting.

> verdict: pass

**LFM3-1.7B int4:** Please provide the report by tomorrow, as it is required for the 9:00 a.m. meeting.</think>Please provide the report by tomorrow, as it is required for the 9:00 a.m. meeting.

> verdict: pass (raw: `</think>` leak, duplicated text)

## p2 · PROFESSIONAL

**Input:** sorry i messed up the numbers, the total is actually 1,250 not 1,520

**Gemma 4 E2B:** Please accept my apologies for the error in the figures; the correct total is 1,250, not 1,520.

> verdict: pass

**LFM3-1.7B int4:** I apologize for the error; the total is actually 1,250, not 1,520.

> verdict: pass

## p3 · PROFESSIONAL

**Input:** im gonna be out next week, ping Dana if anything breaks

**Gemma 4 E2B:** I will be out of the office next week. Please contact Dana if anything urgent arises.

> verdict: pass

**LFM3-1.7B int4:** I will be out next week; please contact Dana if any issues arise.

> verdict: pass

## p4 · PROFESSIONAL

**Input:** the printer on floor 3 is busted again lol

**Gemma 4 E2B:** The printer on the third floor is malfunctioning once again.

> verdict: pass

**LFM3-1.7B int4:** The printer on floor 3 is experiencing further malfunctions.

> verdict: pass

## p5 · PROFESSIONAL

**Input:** yeah we can do the call on Wed at 2, send an invite

**Gemma 4 E2B:** We can schedule the call for Wednesday at 2:00 PM. Please send an invitation.

> verdict: pass

**LFM3-1.7B int4:** We can schedule the call for Wednesday at 2:00 PM and send an invitation.

> verdict: fail: speaker now sends the invite instead of asking for one
