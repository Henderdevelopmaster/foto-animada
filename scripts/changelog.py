#!/usr/bin/env python3
"""Genera docs/CHANGELOG.md desde los tags build-N (Releases) y los mensajes de los commits.

Uso (desde la raíz del repo, con los tags bajados: git fetch --tags):
    python3 scripts/changelog.py
"""
import subprocess

REPO = "https://github.com/Henderdevelopmaster/foto-animada"


def git(*a):
    return subprocess.run(["git", *a], capture_output=True, text=True).stdout


tags = sorted([t for t in git("tag").split() if t.startswith("build-")], key=lambda t: int(t.split("-")[1]))
out = [
    "# Registro de cambios (Camaratrix)",
    "",
    "Cada versión es un Release de GitHub (`build-N` → `Camaratrix-v1.0.N.apk`). Lo más nuevo arriba.",
    "Debajo de cada versión van los cambios que trae (mensajes de los commits).",
    "Se genera con `python3 scripts/changelog.py`.",
    "",
]
entries = []
prev = None
for t in tags:
    rng = f"{prev}..{t}" if prev else t
    log = git("log", "--date=short", "--pretty=%ad\t%s", rng).strip().splitlines()
    date = git("log", "-1", "--date=short", "--pretty=%ad", t).strip()
    entries.append((t, date, log))
    prev = t
pending = git("log", "--date=short", "--pretty=%ad\t%s", f"{tags[-1]}..HEAD").strip().splitlines() if tags else []
if pending:
    out += [f"## Versión siguiente a v1.0.{tags[-1].split('-')[1]}", ""]
    out += [f"- {l.split(chr(9), 1)[1]}" for l in pending] + [""]
for t, date, log in reversed(entries):
    n = t.split("-")[1]
    out += [f"## v1.0.{n} ({date}) · [{t}]({REPO}/releases/tag/{t})", ""]
    if not log:
        out.append("- (misma versión que la anterior, solo se volvió a compilar)")
    out += [f"- {l.split(chr(9), 1)[1]}" for l in log] + [""]
open("docs/CHANGELOG.md", "w").write("\n".join(out))
print(f"docs/CHANGELOG.md: {len(tags)} versiones")
