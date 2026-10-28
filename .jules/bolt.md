## 2024-05-15 - Fast word counting optimization
**Learning:** `text.split(Regex("\\s+")).size` allocates memory for every word and is computationally expensive because it generates intermediate arrays and uses a full Regex engine for just spaces.
**Action:** Introduced a `String.wordCount()` extension function that computes word count efficiently in O(N) by manually iterating through the string characters without additional allocations, providing a significant performance boost in hot paths like `KeyboardViewModel.kt`. Next time, look for other inefficient string/regex uses.
