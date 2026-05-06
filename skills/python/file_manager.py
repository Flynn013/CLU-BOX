"""
file_manager — CLU/BOX Python skill for FILE_BOX workspace operations.

Wraps the ``Splinter.fileBoxRead / fileBoxWrite / workspaceMap`` APIs in a
single entry-point function so the LLM can perform CRUD on the sandboxed
project directory with a simple ``run_python`` tool call.

Entry point: ``run(**kwargs) -> str``
  Required kwargs:
    action: "read" | "write" | "list" | "delete" | "exists" | "stat"
  Action-specific kwargs:
    read   : path (str)
    write  : path (str), content (str)
    list   : path (str, default="") — directory to list; empty = workspace root
    delete : path (str)
    exists : path (str)
    stat   : path (str)
"""

from __future__ import annotations

import json
import os
from typing import Any

# Injected by PythonBridge.initialize — do not import.
Splinter = None  # type: ignore[assignment]


def run(**kwargs: Any) -> str:
    """CRUD operations on the CLU/BOX FILE_BOX workspace.

    Actions
    -------
    read   : Return the content of *path*.
             kwargs: path (str)
    write  : Write *content* to *path* (creates parent dirs automatically).
             kwargs: path (str), content (str)
    list   : Return a tree/listing of *path* (default: workspace root).
             kwargs: path (str = "")
    delete : Delete the file at *path*.
             kwargs: path (str)
    exists : Return "true" or "false" depending on whether *path* exists.
             kwargs: path (str)
    stat   : Return JSON metadata (size, last-modified) for *path*.
             kwargs: path (str)
    """
    action = str(kwargs.get("action", "list")).lower()

    if action == "read":
        return _read(str(kwargs.get("path", "")))
    if action == "write":
        return _write(str(kwargs.get("path", "")), str(kwargs.get("content", "")))
    if action == "list":
        return _list(str(kwargs.get("path", "")))
    if action == "delete":
        return _delete(str(kwargs.get("path", "")))
    if action == "exists":
        return _exists(str(kwargs.get("path", "")))
    if action == "stat":
        return _stat(str(kwargs.get("path", "")))
    return (
        f"[file_manager] Unknown action '{action}'. "
        "Use: read | write | list | delete | exists | stat"
    )


# ── private helpers ────────────────────────────────────────────────────────


def _read(path: str) -> str:
    if not path:
        return "[file_manager] 'path' is required for action=read"
    try:
        content = Splinter.fileBoxRead(path)
        return content if content is not None else f"[file_manager] '{path}' is empty"
    except Exception as exc:
        return f"[file_manager] read error for '{path}': {exc}"


def _write(path: str, content: str) -> str:
    if not path:
        return "[file_manager] 'path' is required for action=write"
    try:
        Splinter.fileBoxWrite(path, content)
        byte_count = len(content.encode("utf-8"))
        return f"[file_manager] Wrote {byte_count} bytes to '{path}'"
    except Exception as exc:
        return f"[file_manager] write error for '{path}': {exc}"


def _list(path: str = "") -> str:
    try:
        tree_json = Splinter.workspaceMap()
        if not tree_json:
            return "[file_manager] Workspace is empty."
        # Parse and pretty-print if path filter is requested
        if path:
            try:
                tree = json.loads(tree_json)
                subtree = _find_subtree(tree, path)
                if subtree is not None:
                    return json.dumps(subtree, indent=2)
                return f"[file_manager] Path '{path}' not found in workspace"
            except Exception:
                pass
        return tree_json
    except Exception as exc:
        return f"[file_manager] list error: {exc}"


def _find_subtree(node: Any, target: str) -> Any:
    """Recursively find the subtree rooted at *target* in the workspace map."""
    if isinstance(node, dict):
        if node.get("name") == target or node.get("path") == target:
            return node
        for child in node.get("children", []):
            result = _find_subtree(child, target)
            if result is not None:
                return result
    elif isinstance(node, list):
        for item in node:
            result = _find_subtree(item, target)
            if result is not None:
                return result
    return None


def _delete(path: str) -> str:
    if not path:
        return "[file_manager] 'path' is required for action=delete"
    try:
        # Use shell to delete since SplinterAPI may not expose a delete method
        result = Splinter.shell(f"rm -rf '{path}'")
        return f"[file_manager] Deleted '{path}'" + (f"\n{result}" if result else "")
    except Exception as exc:
        return f"[file_manager] delete error for '{path}': {exc}"


def _exists(path: str) -> str:
    if not path:
        return "[file_manager] 'path' is required for action=exists"
    try:
        content = Splinter.fileBoxRead(path)
        return "true" if content is not None else "false"
    except Exception:
        return "false"


def _stat(path: str) -> str:
    if not path:
        return "[file_manager] 'path' is required for action=stat"
    try:
        result = Splinter.shell(f"stat -c '%n %s %Y' '{path}' 2>&1")
        if result and not result.startswith("stat:"):
            parts = result.strip().split()
            if len(parts) >= 3:
                meta = {"path": path, "size_bytes": int(parts[1]), "modified_epoch": int(parts[2])}
                return json.dumps(meta)
        return f"[file_manager] stat error for '{path}': {result}"
    except Exception as exc:
        return f"[file_manager] stat error for '{path}': {exc}"
