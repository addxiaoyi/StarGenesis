"""Fail builds when simulation or public contracts acquire server/unsafe dependencies."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
forbidden = ("org.bukkit", "io.papermc", "dev.helstera", "net.momirealms", "net.kaleidoscope", "java.lang.reflect")
errors = []
for module in ("stargenesis-api", "stargenesis-sim", "stargenesis-design", "stargenesis-integrations"):
    for path in (root / module / "src/main/java").rglob("*.java"):
        for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if line.strip().startswith("import ") and any(token in line for token in forbidden):
                errors.append(f"{path.relative_to(root)}:{line_number}: forbidden boundary {line.strip()}")
for path in (root / "stargenesis-paper/src/main/java").rglob("*.java"):
    for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if line.strip().startswith("import ") and any(token in line for token in ("dev.helstera", "net.momirealms", "net.kaleidoscope")):
            errors.append(f"{path.relative_to(root)}:{line_number}: optional provider leaked into base plugin {line.strip()}")
if errors:
    raise SystemExit("\n".join(errors))
print("PASS: public API, simulation, design and discovery are free of server/reflective imports; base plugin has no optional provider imports")
