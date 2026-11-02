"""
Writes plugin/src/main/resources/default-content-history.txt: the SHA-1 of every version of every
file under content/ that was ever committed. The plugin uses it to recognise untouched default files
on servers installed before update tracking existed, so they can be upgraded safely.

    python3 tools/content_history.py
"""

import hashlib
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "plugin" / "src" / "main" / "resources" / "default-content-history.txt"


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, check=True).stdout


lines = set()
commits = git("log", "--format=%H", "--", "content").decode().split()
for commit in commits:
    for path in git("ls-tree", "-r", "--name-only", commit, "--", "content").decode().splitlines():
        data = git("show", f"{commit}:{path}")
        lines.add(f"{hashlib.sha1(data).hexdigest()} {path.removeprefix('content/')}")
OUT.write_text("\n".join(sorted(lines, key=lambda l: (l.split(' ', 1)[1], l))) + "\n")
print(f"{len(lines)} file versions from {len(commits)} commits -> {OUT.relative_to(REPO)}")
