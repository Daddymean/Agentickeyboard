# Legacy-twin dead-man release (KEYBOARD-027)

Keith's decisions (2026-10-17):
- Release after **6 months without a check-in**.
- Recipients are **Kid 1 to Kid 6**.
- **3 key holders**, starting as Guardian 1–3. Any **2 of 3** together can open the archive; one alone can't.
- Each kid can **replace a guardian** as a key holder from their **18th birthday**.
- Open-source tools only. The archive stays encrypted on the phone until Keith moves it, and nothing goes to the cloud.

## The pieces

| Piece | What it is | Who keeps it |
|---|---|---|
| `twin-release-<date>.aktx` | The twin: scrubbed messages, journal and recordings, encrypted with a random 256-bit **data key**. | Wherever Keith puts it by hand. We recommend at least two copies, e.g. a USB drive with the executor and one with the kids' guardian. |
| `twin-release-key-set<N>.json` | The data key, **wrapped** (AES-256-GCM) by a key-encryption key for holder set N, plus the policy and holder labels. It contains no secret that is usable alone. | **Next to the archive.** Replace it whenever the holders change; see below. |
| Share card (`AKDM1-…`) | One Shamir share of set N's key-encryption key. | Each holder keeps **only their own** card: printed, written down, or in their password manager. |

The phone keeps only the data key, sealed under the twin's Android Keystore key, so it can re-key and write new archives. It never stores the key-encryption key or the cards: they are shown once, and you may save them to a folder on the phone. Hand them out, then delete the card files.

## Check-ins and the 6-month rule

Tap **I'm here: check in** on the phone (Legacy twin → Dead-man switch). The screen shows:
- the last check-in;
- the date release becomes due (the last check-in plus 6 months);
- a warning in the last 30 days.

The key file states the rule and, as of when you last saved it, the last check-in and the due date.

**There is no server, so nothing automatic happens at the deadline.** The holders apply the rule: when Keith hasn't checked in for 6 months (or they know he has died), two of them meet and open the archive for the kids. The 2-of-3 threshold is what stops early opening: no single person can do it, though two holders acting together could.

If you want the deadline enforced by maths instead of by people, there are options below for Keith to decide.

| Option | How | Cost/trust | Trade-off |
|---|---|---|---|
| **A. Holders apply the rule** (implemented) | 2-of-3 holders, as above. | No server, nothing to run. | Two colluding holders could open early. Release depends on people noticing. |
| **B. Time-lock with drand `tlock`** (open source; League of Entropy public randomness beacon) | Before handing out the key file, also time-lock it with `tle -e -D 183d` (or `--round`), so it can't be decrypted before the date even by all three holders. At release, `tle -d` fetches the public beacon round. | No server of Keith's. It relies on the League of Entropy beacon still existing, and needs internet at release. | The date is fixed when the file is made: phone check-ins don't extend it. Keith must re-make and re-place the time-locked key file every ~5 months, or the lock simply opens on the old date (still 2-of-3 protected). |
| **C. Minimal self-hosted timer** | A tiny server or cron job that receives check-ins and, after 6 months, emails the holders instructions (never a key). | Keith runs and pays for it; it must outlive him. | This is the only fully automatic notice. It conflicts with "no server if possible". |

Recommendation: **A now**. B is worth adding if Keith wants an enforced "not before" date and accepts refreshing the file. C is only needed if the holders must be *told* automatically.

## Changing a key holder (for example Kid 1 turns 18)

1. On the phone: Dead-man switch → **Replace a key holder** → choose the guardian → choose **Kid N** and enter Kid N's date of birth. It is checked (18 or older) and not stored.
2. The phone makes a **new set** (N+1): a new key-encryption key, a new wrap of the same data key, and **three new cards**. The archive is not re-encrypted.
3. Give all three holders their new card, and ask them to destroy their old card.
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
