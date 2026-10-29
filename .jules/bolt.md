## 2024-05-14 - Replace Regex string split for word counting in hot paths
**Learning:** In Kotlin UI/ViewModel hot paths, using `String.split(Regex("\\s+"))` for word counting or trailing word extraction invokes the regex engine and triggers heavy intermediate array allocations.
**Action:** Use zero-allocation, O(N) string iteration like custom extension functions for word counting (`wordCount()`) and trailing word extraction (`lastWordOrNull()`).
