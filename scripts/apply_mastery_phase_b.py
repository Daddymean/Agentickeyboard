from pathlib import Path


VIEW_MODEL = Path(
    "app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt"
)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    text = VIEW_MODEL.read_text()
    text = replace_once(
        text,
        "import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMastery\n",
        "import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMastery\n"
        "import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMasteryMissions\n",
        "mastery mission import",
    )
    anchor = """    fun resetMasteryProgress() {
        val reset = MasteryState.fresh(enabled = _isMasteryEnabled.value)
        _masteryState.value = reset
        settings?.masteryState = MasteryStateCodec.encode(reset)
    }

"""
    replacement = anchor + """    fun dismissMasteryMission(missionId: String) {
        val updated = KeyboardMasteryMissions.dismiss(
            state = _masteryState.value,
            missionId = missionId,
            epochDay = System.currentTimeMillis() / DAY_MS
        )
        if (updated != _masteryState.value) {
            _masteryState.value = updated
            settings?.masteryState = MasteryStateCodec.encode(updated)
        }
    }

"""
    text = replace_once(text, anchor, replacement, "mission dismissal method")
    VIEW_MODEL.write_text(text)


if __name__ == "__main__":
    main()
