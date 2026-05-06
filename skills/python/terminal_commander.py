"""
terminal_commander — CLU/BOX Python skill for sandboxed shell execution.

Wraps ``Splinter.shell`` and ``Splinter.shellVisible`` in a single entry-point
function so the LLM can execute commands in the aarch64 Termux clu_workspace
sandbox through a simple ``run_python`` tool call.

Entry point: ``run(**kwargs) -> str``
  Required kwargs:
    command: str — the bash command to execute
  Optional kwargs:
    visible: bool (default False) — display output on the MSTR_CTRL terminal
    timeout: int  (default 30)   — max seconds to wait (0 = no limit)
    cwd:     str  (default "")   — working directory override
"""

from __future__ import annotations

import re
import shlex
from typing import Any

# Injected by PythonBridge.initialize — do not import.
Splinter = None  # type: ignore[assignment]

# Shell command allowlist — block obviously dangerous patterns that should
# never be needed inside the CLU/BOX sandbox.
_BLOCKED_PATTERNS = [
    r"\brm\s+-rf\s+/\b",          # rm -rf /  (destroy root)
    r"\bmkfs\b",                   # format block device
    r"\bdd\b.*\bof=/dev/",         # write to raw device
    r">\s*/dev/(sd|nvme|mmcblk)",  # redirect to block device
]
_BLOCKED_RE = [re.compile(p) for p in _BLOCKED_PATTERNS]


def run(**kwargs: Any) -> str:
    """Execute a shell command inside the CLU/BOX sandbox.

    The command runs in the aarch64 Termux clu_workspace environment.
    stdout + stderr are captured and returned.

    kwargs
    ------
    command : str   — bash command to run (required).
    visible : bool  — if True, output is also mirrored on the MSTR_CTRL screen.
    timeout : int   — seconds before TIMEOUT ERROR (default 30; 0 = unlimited).
    cwd     : str   — change to this directory before running (FILE_BOX relative).
    """
    command = str(kwargs.get("command", "")).strip()
    if not command:
        return "[terminal_commander] 'command' is required."

    # Safety gate: block obviously destructive patterns
    for pattern in _BLOCKED_RE:
        if pattern.search(command):
            return (
                f"[terminal_commander] Command blocked by safety policy: "
                f"matches pattern '{pattern.pattern}'"
            )

    cwd = str(kwargs.get("cwd", "")).strip()
    visible = bool(kwargs.get("visible", False))
    timeout = int(kwargs.get("timeout", 30))

    # Prepend cd if cwd specified
    full_cmd = command
    if cwd:
        full_cmd = f"cd {shlex.quote(cwd)} && {command}"

    # Prepend timeout if requested
    if timeout > 0:
        full_cmd = f"timeout {timeout} sh -c {shlex.quote(full_cmd)}"

    try:
        if visible:
            result = Splinter.shellVisible(full_cmd)
        else:
            result = Splinter.shell(full_cmd)
        if result is None:
            return "[terminal_commander] Command produced no output."
        return str(result)
    except Exception as exc:
        return f"[terminal_commander] Execution error: {exc}"


def git_diff(path: str = "") -> str:
    """Return a minimal ``--unified=0`` diff for *path* (or the full workspace).

    kwargs
    ------
    path : str — relative path to diff; empty string diffs the whole workspace.
    """
    cmd = "git diff --unified=0"
    if path:
        cmd += f" -- {shlex.quote(path)}"
    try:
        result = Splinter.shell(cmd)
        return result if result else "[terminal_commander] No changes (clean diff)"
    except Exception as exc:
        return f"[terminal_commander] git_diff error: {exc}"
