# Word lists bundled with the keyboard

## `app/src/main/res/raw/wordlist.txt` (frequency list)

There are 9,704 words, ranked by frequency, used by swipe typing and spelling. The word order matches
[google-10000-english](https://github.com/first20hours/google-10000-english), which comes from the
Google Web Trillion Word Corpus. That project's licence notes say the data is for non-commercial use, so
its provenance needs Keith's decision before any commercial release. This was flagged in KEYBOARD-020;
the file itself is unchanged.

## `spelling_extra.txt` and `spelling_known_only.txt` (KEYBOARD-020)

These hold common English words that are missing from the frequency list, for example "discern".
They come from **SCOWL** (Spell Checker Oriented Word Lists) 2020.12.07 by Kevin Atkinson,
<http://wordlist.aspell.net/>. Its permissive licence allows use, modification, distribution and sale,
provided the copyright and permission notice is kept. The full notice ships in the APK as
`app/src/main/assets/third_party/SCOWL-Copyright.txt`.

How the two files were built:

1. Take `english-words` and `american-words` at levels 10, 20 and 35 (SCOWL's "small" size), keeping
   lower-case a–z words only.
2. Drop words already in `wordlist.txt`, stripped contractions such as `dont` (see `Contractions.kt`),
   and `LocalSpelling.COMMON_SLANG`.
3. Order the result by SCOWL level, then by length, then alphabetically. All of these words rank below
   the frequency list.
4. Move vulgar and offensive words into `spelling_known_only.txt`. These words are recognised, so they
   are never "corrected", but they are never offered as a suggestion.

Only `LocalSpelling` uses these files. Swipe typing still uses `wordlist.txt` alone.
