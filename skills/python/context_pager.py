"""
context_pager — CLU/BOX Python skill for paging large files and log streams.

Provides windowed access to any text artefact in the FILE_BOX workspace that
is too large to fit in a single LLM context window.  The pager keeps a tiny
cursor state in BRAIN_BOX so the agent can call ``run(action="next")``
repeatedly without re-reading the whole file on every turn.

Entry point: ``run(**kwargs) -> str``
  Required kwargs:
    action  : "open" | "next" | "prev" | "search" | "reset"
  Action-specific kwargs:
    open    : path (str), page_size (int, default=100)
    next    : (none — uses stored cursor)
    prev    : (none — uses stored cursor)
    search  : query (str), path (str, optional — re-opens if given)
    reset   : (none — clears stored cursor)
"""

from __future__ import annotations

import json
import os
import re
from typing import Any

# ``Splinter`` is injected by PythonBridge.initialize before this module runs.
# It is already in the clu_runner namespace; we just reference it here for
# type-checker clarity.  Do not import it — it is a module-global injected by
# the host.
Splinter = None  # type: ignore[assignment]

_CURSOR_KEY = "__ctx_pager_cursor__"


def run(**kwargs: Any) -> str:
    """Page through large files stored in the CLU/BOX FILE_BOX workspace.

    Actions
    -------
    open   : Open *path* and return the first page.
             kwargs: path (str), page_size (int = 100)
    next   : Return the next page of the currently-open file.
    prev   : Return the previous page of the currently-open file.
    search : Find lines containing *query* and return up to 20 matches.
             kwargs: query (str), path (str, optional)
    reset  : Clear the stored cursor (forget the open file).
    """
    action = str(kwargs.get("action", "open")).lower()

    if action == "open":
        return _open(
            path=str(kwargs.get("path", "")),
            page_size=int(kwargs.get("page_size", 100)),
        )
    if action == "next":
        return _turn(direction=1)
    if action == "prev":
        return _turn(direction=-1)
    if action == "search":
        return _search(
            query=str(kwargs.get("query", "")),
            path=str(kwargs.get("path", "")),
        )
    if action == "reset":
        return _reset()
    return f"[context_pager] Unknown action '{action}'. Use: open | next | prev | search | reset"


# ── private helpers ────────────────────────────────────────────────────────


def _cursor() -> dict:
    """Load the cursor from BRAIN_BOX; return empty dict if not set."""
    try:
        raw = Splinter.brainBoxRecall(_CURSOR_KEY)
        if raw:
            return json.loads(raw)
    except Exception:
        pass
    return {}


def _save_cursor(path: str, offset: int, page_size: int) -> None:
    Splinter.brainBoxStore(_CURSOR_KEY, json.dumps({
        "path": path,
        "offset": offset,
        "page_size": page_size,
    }))


def _open(path: str, page_size: int = 100) -> str:
    if not path:
        return "[context_pager] 'path' is required for action=open"
    try:
        raw = Splinter.fileBoxRead(path)
    except Exception as exc:
        return f"[context_pager] Cannot open '{path}': {exc}"
    lines = raw.splitlines()
    total = len(lines)
    page = lines[:page_size]
    _save_cursor(path, page_size, page_size)
    header = f"[context_pager] {path} — lines 1-{min(page_size, total)} of {total}\n"
    return header + "\n".join(page)


def _turn(direction: int) -> str:
    cur = _cursor()
    if not cur:
        return "[context_pager] No file open. Use action=open first."
    path = cur["path"]
    page_size = cur["page_size"]
    offset = cur["offset"] + (direction * page_size)
    offset = max(0, offset)
    try:
        raw = Splinter.fileBoxRead(path)
    except Exception as exc:
        return f"[context_pager] Cannot read '{path}': {exc}"
    lines = raw.splitlines()
    total = len(lines)
    if offset >= total:
        return f"[context_pager] End of file ({total} lines). Use action=prev to go back."
    page = lines[offset:offset + page_size]
    _save_cursor(path, offset + page_size, page_size)
    start = offset + 1
    end = min(offset + page_size, total)
    header = f"[context_pager] {path} — lines {start}-{end} of {total}\n"
    return header + "\n".join(page)


def _search(query: str, path: str = "") -> str:
    if not query:
        return "[context_pager] 'query' is required for action=search"
    cur = _cursor()
    target = path or cur.get("path", "")
    if not target:
        return "[context_pager] No file open and no 'path' given. Use action=open first."
    try:
        raw = Splinter.fileBoxRead(target)
    except Exception as exc:
        return f"[context_pager] Cannot read '{target}': {exc}"
    lines = raw.splitlines()
    pattern = re.compile(re.escape(query), re.IGNORECASE)
    matches = [(i + 1, line) for i, line in enumerate(lines) if pattern.search(line)]
    if not matches:
        return f"[context_pager] No matches for '{query}' in {target}"
    result_lines = [f"  L{ln}: {txt}" for ln, txt in matches[:20]]
    note = f"  … and {len(matches) - 20} more" if len(matches) > 20 else ""
    header = f"[context_pager] {len(matches)} match(es) for '{query}' in {target}:\n"
    return header + "\n".join(result_lines) + note


def _reset() -> str:
    try:
        Splinter.brainBoxStore(_CURSOR_KEY, "")
    except Exception:
        pass
    return "[context_pager] Cursor cleared."
