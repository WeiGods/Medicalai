"""Load the same role instructions packaged in the Java backend."""
import json
from pathlib import Path


def load_role_prompt():
    packaged = Path(__file__).with_name("role-assignment.json")
    source = Path(__file__).resolve().parents[1] / "backend/src/main/resources/ai/role-assignment.json"
    prompt = json.loads((packaged if packaged.is_file() else source).read_text(encoding="utf-8"))
    if prompt.get("version") != "1" or not prompt.get("system") or not prompt.get("instruction"):
        raise RuntimeError("Invalid role-assignment prompt resource")
    return prompt


ROLE_PROMPT = load_role_prompt()
