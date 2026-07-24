from pathlib import Path
import subprocess

view_model = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt").read_text()
layout = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/AgenticKeyboardLayout.kt").read_text()

already_integrated = (
    "private fun publishAiPanel(state: AiPanelState)" in view_model
    and "private fun VoiceMatchBadge(match: VoiceMatchState)" in layout
)

if already_integrated:
    print("Sounds Like You source is already integrated; no transform needed.")
else:
    subprocess.run(["python", "scripts/apply_voice_match.py"], check=True)
