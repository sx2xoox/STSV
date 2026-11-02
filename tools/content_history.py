"""
Writes plugin/src/main/resources/default-content-history.txt: the SHA-1 of every version of every
file under content/ that was ever committed. The plugin uses it to recognise untouched default files
on servers installed before update tracking existed, so they can be upgraded safely.

    python3 tools/content_history.py                      # core: content/ -> plugin/...
    python3 tools/content_history.py economy/content economy/src/main/resources/default-content-history.txt
"""

import hashlib
import subprocess
from pathlib import Path

import sys

REPO = Path(__file__).resolve().parent.parent
SRC = sys.argv[1] if len(sys.argv) > 1 else "content"
OUT = REPO / (sys.argv[2] if len(sys.argv) > 2 else "plugin/src/main/resources/default-content-history.txt")


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, check=True).stdout


lines = set()
commits = git("log", "--format=%H", "--", SRC).decode().split()
for commit in commits:
    for path in git("ls-tree", "-r", "--name-only", commit, "--", SRC).decode().splitlines():
        data = git("show", f"{commit}:{path}")
        lines.add(f"{hashlib.sha1(data).hexdigest()} {path.removeprefix(SRC.rstrip('/') + '/')}")
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(sorted(lines, key=lambda l: (l.split(' ', 1)[1], l))) + ("\n" if lines else ""))
print(f"{len(lines)} file versions from {len(commits)} commits -> {OUT.relative_to(REPO)}")
