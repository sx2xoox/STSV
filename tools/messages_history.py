"""
Writes <plugin>/src/main/resources/default-messages-history.txt: for every message key, the SHA-1 of every
value it ever had in a committed messages.yml. The plugin uses it to tell an untouched old default from
a server owner's own wording: an old default is replaced by the new one on start, an edited text is kept.

    python3 tools/messages_history.py plugin
    python3 tools/messages_history.py economy

Hash of "key\\0value"; a list is its lines joined by "\\n" (the same in Lang.java).
"""

import hashlib
import subprocess
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
MODULE = sys.argv[1] if len(sys.argv) > 1 else "plugin"
SRC = f"{MODULE}/src/main/resources/messages.yml"
OUT = REPO / MODULE / "src/main/resources/default-messages-history.txt"


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, check=True).stdout


def leaves(doc):
    out = {}

    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, f"{path}.{k}" if path else str(k))
        elif isinstance(node, list):
            out[path] = "\n".join("" if x is None else str(x) for x in node)
        elif node is not None:
            out[path] = str(node)

    walk(doc, "")
    return out


lines = set()
commits = git("log", "--format=%H", "--", SRC).decode().split()
for commit in commits:
    try:
        doc = yaml.safe_load(git("show", f"{commit}:{SRC}").decode("utf-8"))
    except subprocess.CalledProcessError:
        continue
    for key, value in leaves(doc or {}).items():
        lines.add(hashlib.sha1(f"{key}\0{value}".encode("utf-8")).hexdigest())
OUT.write_text("\n".join(sorted(lines)) + "\n")
print(f"{len(lines)} message versions from {len(commits)} commits -> {OUT.relative_to(REPO)}")
