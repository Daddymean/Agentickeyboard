# Legacy-twin dead-man release (KEYBOARD-027)

Keith's decisions (2026-10-17, trigger revised 2026-10-18):
- The trigger is **Google Inactive Account Manager**: after **6 months** without activity on his Google account, Google emails the key holders. The email is only a signal; the fallback is the holders' 6-month rule.
- Recipients are **Kid 1 to Kid 6**.
- **3 key holders**, starting as Guardian 1–3. Any **2 of 3** together can open the archive; one alone can't.
- Each kid can **replace a guardian** as a key holder from their **18th birthday**.
- Open-source tools only. The archive stays encrypted on the phone until Keith moves it, and nothing goes to the cloud.

## The pieces

| Piece | What it is | Who keeps it |
|---|---|---|
| `twin-release-<date>.aktx` | The twin: scrubbed messages, journal and recordings, plus approved memories and core memories (KEYBOARD-032; see README), encrypted with a random 256-bit **data key**. | Wherever Keith puts it by hand. We recommend at least two copies, e.g. a USB drive with the executor and one with the kids' guardian. |
| `twin-release-key-set<N>.json` | The data key, **wrapped** (AES-256-GCM) by a key-encryption key for holder set N, plus the policy and holder labels. It contains no secret that is usable alone. | **Next to the archive.** Replace it whenever the holders change; see below. |
| Share card (`AKDM1-…`) | One Shamir share of set N's key-encryption key. | Each holder keeps **only their own** card: printed, written down, or in their password manager. |

The phone keeps only the data key, sealed under the twin's Android Keystore key, so it can re-key and write new archives. It never stores the key-encryption key or the cards: they are shown once, and you may save them to a folder on the phone. Hand them out, then delete the card files.

## The trigger: Google Inactive Account Manager (Keith, 2026-10-18)

Google's **Inactive Account Manager (IAM)** watches Keith's Google account. After **6 months with no activity**, Google emails the key holders a message Keith wrote. **That email is the signal to start the unlock. It is never a key.** No share card, passphrase, key file, archive or link to them goes into the IAM message, Google Drive, or any data shared through the plan. Anyone who reads the email, Google included, learns only that the plan exists.

What Google's help pages say (checked 2026-10-18 on [About Inactive Account Manager](https://support.google.com/accounts/answer/3036546) and Google's [security blog post](https://security.googleblog.com/2021/10/google-protects-your-accounts-even-when.html)):

| Item | Google's documentation |
|---|---|
| Wait period | 3, 6, 12 or 18 months of inactivity (we use **6**). Google alerts Keith by text and email about a month before. |
| Activity signals | Sign-ins, recent activity in My Activity, Gmail use (including the Gmail app), and Android check-ins. |
| Contacts | Up to **10** people. They hear nothing at set-up, only when the plan fires. |
| What they get | An email with the subject and message Keith wrote, plus a Google footer such as "Keith … (keith@…) instructed Google to send you this mail automatically after Keith stopped using his account. Sincerely, The Google Accounts Team". If data sharing is chosen, it also lists data and a download link (needs the contact's phone number). **We use notify-only.** |
| Deletion | Optional. If turned on, the account is deleted 3 months after it becomes inactive. |
| No plan | Google may delete accounts inactive for 2 years (Inactive Google Account Policy). |

Google may change these; the app asks Keith to review the plan every 12 months.

### Keith: setting up IAM

On the phone: Legacy twin → Dead-man switch → **Set up the Google trigger…** shows these steps and the message text.

1. Open `myaccount.google.com/inactive`.
2. **Wait period: 6 months.** Keep the recovery phone number and email current, so Google's one-month warning reaches you.
3. **Contacts:** the email address of each current key holder (Guardian 1–3 for now). Choose **notify only**: don't share any data. Add the executor too if you like; they get no card.
4. **Subject and message**, with no secrets:

   > **Subject:** Keith's legacy-twin plan: signal to start
   >
   > This is the automatic signal Keith set up for his legacy-twin plan. It is not a key and it asks you to do nothing online: don't click links or sign in anywhere because of this email.
   > 1. Check it is genuine: it came from a google.com address, Gmail's details show mailed-by and signed-by google.com, and Google's footer names Keith's own address.
   > 2. Phone the other key holders on numbers you already know, and try to reach Keith and the family.
   > 3. Only if two of you agree, follow the instructions that came with your share card.
   >
   > Nothing secret is in this email or in Google.

5. **Account deletion:** leaving it off is simplest. If you turn it on, the account goes 3 months after the trigger.
6. In the app, tap **I've set it up this way**. The app can't see your Google account; it only records the date and reminds you to review after 12 months. **When a key holder changes, the confirmation is cleared:** add the new holder as an IAM contact, remove the old one, and confirm again.
7. Show each holder the message text in person, so they know what the real email looks like.

Using the account (signing in, Gmail, My Activity, or an Android phone signed in to it) resets Google's timer. Keith does nothing else. A phone left switched on after his death can keep the account looking active, so the family should switch his devices off.

### Fallback: the holders' 6-month rule

If the Google email can't come, because IAM was changed or dropped, the account was suspended or deleted, or the plan was turned off, the holders apply the rule themselves. They proceed when Keith has been out of contact for 6 months, or is known to have died, and **never before the key file's `policy.not_before`**. That date is 6 months after the last in-app check-in recorded in that key file. The in-app **I'm here: check in** moves it; save the key file again afterwards so the copy next to the archive carries the new date.

## Holders: when the Google email arrives

1. **Don't act on the email itself.** Don't click links, sign in, or reply with anything. Your card never goes into an email, a website or Google.
2. **Check that it is genuine:**
   - The sender is an `@google.com` address and the display name is Google's.
   - In Gmail, open the message details: **mailed-by** and **signed-by** both show `google.com`. Or use **Show original**: SPF, DKIM and DMARC are PASS, and the DKIM domain is `d=google.com`.
   - The footer names Keith's real Google address, and the subject and text match what Keith showed you.

   Treat these checks as necessary, not sufficient. Authenticated google.com mail has been abused through Google auto-replies, so the headers alone don't prove the plan fired.
3. **Cross-check with the other holders** by phone or in person, on numbers you already know (never ones from the email). The real email goes to every holder at about the same time. If only one of you got it, stop.
4. **Try to reach Keith** and the family. The trigger can fire while he is alive, for example after a long hospital stay or time offline, even though Google warned him a month before. If he answers, stop; he signs in, and the plan resets.
5. Only when **2 of you agree**, meet with the archive, the key file and your two cards, and follow "Opening it" below for Kid 1–6.

The 2-of-3 threshold is the real protection. The email only says when to start.

## Tradeoffs of the Google trigger

| Risk | What happens | Mitigation |
|---|---|---|
| Google changes or drops IAM, or changes its options | No email, or a different one. | The fallback rule above. The 12-month review in the app prompts Keith to re-check the plan. |
| The account is suspended, locked or deleted | IAM may never fire. | The fallback rule. |
| Phishing: a fake "inactive account" email | A holder might start early. | Genuineness checks, cross-checking with the other holders, and contacting Keith. Even if it is believed, it opens nothing: 2 cards are still needed, and the email has no link or action to fall for. |
| Account takeover | An attacker's activity resets the timer, which **delays** release. They could also edit or turn off the plan or change the contacts, so it fails silently. They cannot open the archive: no secret is in Google. | The fallback rule. The 12-month review. A takeover is a confidentiality non-event and an availability risk only. |
| Keith simply doesn't log in for 6 months (hospital, off-grid) | False signal. | Google's one-month warning by text and email. Holders try to reach Keith first. 2-of-3 agreement is still needed. |
| Keith's devices stay on after his death | Android check-ins may keep the account active, which delays release. | The family switches the devices off. Otherwise the fallback rule applies. |
| Two holders collude early | They can open it, with or without the email. | Unchanged from before: this is what a not-before time-lock (option B) would address. |
| Privacy | Google knows the plan exists and who the holders' email addresses are. | Only Keith's own message and his chosen contacts go to Google; no twin data or secrets. |

## Further options (Keith decides)

| Option | How | Trade-off |
|---|---|---|
| **IAM + holders** (implemented) | As above. | The trigger is automatic and free with no server, but it is only a signal. The cards are the lock. |
| **B. Add a drand `tlock` not-before lock** (open source, [drand/tlock](https://github.com/drand/tlock), League of Entropy beacon) | Off-device, time-lock the key file with `tle -e -D 183d`, so even all 3 holders can't unwrap it before the date. At release, `tle -d` fetches the public beacon round. | The date is **fixed** when the file is made, while IAM's deadline slides with Keith's activity. Keith would have to re-make and re-place the locked key file every few months, or the lock opens on the old date. It needs internet and the drand network at release; if drand ends, the locked copy is stuck, so an unlocked copy or a fallback is still needed, which undoes the lock. Doing it in the app needs BLS12-381 identity-based encryption, and we know of no maintained Kotlin or Android tlock implementation. |
| **C. Minimal self-hosted timer** | A small server Keith runs that emails instructions (never a key). | It duplicates what IAM now does, costs money, and must outlive Keith. Not needed. |

**Recommendation:** use IAM as the signal and keep 2-of-3 as the lock, with the fallback rule. **Don't add drand now**. It only addresses two holders colluding, which IAM doesn't change. It also adds a fixed date that fights the sliding trigger, a refresh chore and a network dependency at release. It stays available as an off-device step (option B) with no app change, if Keith later wants a hard not-before date.

## Changing a key holder (for example Kid 1 turns 18)

1. On the phone: Dead-man switch → **Replace a key holder** → choose the guardian → choose **Kid N** and enter Kid N's date of birth. It is checked (18 or older) and not stored.
2. The phone makes a **new set** (N+1): a new key-encryption key, a new wrap of the same data key, and **three new cards**. The archive is not re-encrypted.
3. Give all three holders their new card, and ask them to destroy their old card. **Update the IAM contacts**: add the new holder and remove the old one. Then confirm the Google trigger again in the app.
4. **Replace the key file next to every archive copy** with `twin-release-key-set<N+1>.json`, and destroy the old key files.

Why step 4 matters: old cards cannot open the new key file. However, an old key file together with two old cards still opens the archive, because the data key is unchanged. Revocation therefore depends on old key files being destroyed.

**Reissue all share cards** does the same thing with unchanged labels, for when a card is lost.

## Opening it (two holders, on a computer)

```sh
python3 -m pip install cryptography
python3 open_twin_release.py --verify-only twin-release-2026-10-17.aktx twin-release-key-set2.json \
    --card guardian-2-card.txt --card kid-1-card.txt
python3 open_twin_release.py twin-release-2026-10-17.aktx twin-release-key-set2.json \
    --card guardian-2-card.txt --card "AKDM1-…"  twin-data/
```

The script checks, in order:
- each card's checksum;
- that both cards and the key file are the same set;
- that the key file belongs to this archive.

It then recombines the key (Shamir over GF(2^8), the same as the app), unwraps the data key, and decrypts and verifies every chunk and every SHA-256 in `manifest.json`. Contents and layout are as in [README.md](README.md). If a kid is still a minor at release, their guardian opens it for them.

## Use rules (KEYBOARD-033)

These come from Hollanek & Nowaczyk-Basińska, *Griefbots, Deadbots, Postmortem Avatars* (Philosophy & Technology, 2024). They also travel inside the release key file (`policy.use_rules`), and `open_twin_release.py` prints them.

- **Each kid's own choice.** Kid 1–6 each decide whether to use the twin, and each can pause or retire it for themselves. On the phone, the twin answers a kid only after that kid opts in. Under 18, a guardian must be present. A retired twin can come back only after 30 days, and only if the kid types BRING BACK.
- **Always an AI.** Every answer starts with "AI twin built from Dad's own words. This is not Dad." Anything built from this archive must keep that label.
- **No public platforms.** Do not post the twin, its answers, its voice or this archive anywhere public, and do not upload them to an online AI or "griefbot" service. The phone app has no share, copy or notification path: the screen is FLAG_SECURE, the answer text can't be selected, and a guard test enforces this.
- **No decisions.** The twin refuses new decisions about money, inheritance or legal matters, and permission for them. It points to Keith's real documents and the guardians.
- **Never pushes.** No notifications, no reminders, and nothing is sent by itself.
