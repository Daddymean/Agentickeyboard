from pathlib import Path


CARD = Path(
    "app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardMasteryCard.kt"
)


def main() -> None:
    text = CARD.read_text()
    anchor = """            Spacer(modifier = Modifier.height(5.dp))
            WeeklyReportSummary(report)

            Spacer(modifier = Modifier.height(12.dp))
"""
    replacement = """            Spacer(modifier = Modifier.height(5.dp))
            WeeklyReportSummary(report)

            Spacer(modifier = Modifier.height(12.dp))
            MasteryConstellationSection(
                state = state,
                settings = masterySettings
            )

            Spacer(modifier = Modifier.height(12.dp))
"""
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"constellation anchor: expected exactly one match, found {count}")
    CARD.write_text(text.replace(anchor, replacement, 1))


if __name__ == "__main__":
    main()
