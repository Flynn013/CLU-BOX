"""
Chadaupy — CLU/BOX Skill Management CRUD Service
=================================================

A lightweight Flask app that provides a REST API for managing CLU/BOX Python
skills stored under the ``skills/python/`` directory.  It is designed to run
either:

  * **On-device** inside the Termux/proot sandbox (``python3 -m chadaupy.app``).
  * **Off-device** on a development machine pointed at the repository checkout
    via the ``SKILLS_DIR`` environment variable.

Endpoints
---------
GET  /api/v1/skills               — list all skills
POST /api/v1/skills               — create a new skill
GET  /api/v1/skills/<name>        — read a skill (metadata + source)
PUT  /api/v1/skills/<name>        — update a skill's source code
DELETE /api/v1/skills/<name>      — delete a skill

GET  /api/v1/skills/<name>/schema — return the LLM tool schema (introspected)
POST /api/v1/skills/<name>/run    — execute a skill with JSON kwargs
GET  /health                      — liveness probe

Skill name constraints
----------------------
Names must match ``^[a-z][a-z0-9_]{0,63}$`` — lowercase, start with a letter,
contain only letters/digits/underscores, and be at most 64 characters long.
"""

from __future__ import annotations

import importlib.util
import inspect
import json
import os
import re
import sys
import time
import types
from pathlib import Path
from typing import Any

from flask import Flask, jsonify, request

# ── Configuration ──────────────────────────────────────────────────────────
_DEFAULT_SKILLS_DIR = Path(__file__).parent.parent / "skills" / "python"
SKILLS_DIR = Path(os.getenv("SKILLS_DIR", str(_DEFAULT_SKILLS_DIR)))
HOST = os.getenv("CHADAUPY_HOST", "127.0.0.1")
PORT = int(os.getenv("CHADAUPY_PORT", "7431"))

# Skill names must be valid Python identifiers.
_VALID_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")

app = Flask(__name__)


# ── Helpers ────────────────────────────────────────────────────────────────


def _skill_path(name: str) -> Path:
    # Strip any character outside [a-z0-9_] before constructing the path.
    # This is a defence-in-depth sanitization step — the caller must also have
    # validated `name` via _validate_name before reaching here.
    # Using re.sub to produce a safe_name that is guaranteed to contain no
    # directory traversal characters ('.', '/', '\', etc.) regardless of what
    # `name` contains.
    #
    # CodeQL note: the remaining py/path-injection alerts on this function are
    # false positives.  The taint from `name` is fully eliminated by `re.sub`
    # (which produces a string with ONLY [a-z0-9_] chars) and the subsequent
    # `relative_to()` containment check.  Only valid skill filenames within
    # SKILLS_DIR can ever be returned.
    safe_name = re.sub(r"[^a-z0-9_]", "", name)
    if not safe_name:
        raise ValueError(f"Skill name '{name}' produced an empty sanitised path component")
    base = SKILLS_DIR.resolve()
    candidate = (base / f"{safe_name}.py").resolve()
    try:
        # relative_to raises ValueError if candidate is outside base
        candidate.relative_to(base)
    except ValueError:
        raise ValueError(f"Path traversal detected for name '{name}'") from None
    return candidate


def _validate_name(name: str) -> str | None:
    """Return an error string if *name* is invalid, else None."""
    if not _VALID_NAME_RE.match(name):
        return (
            f"Invalid skill name '{name}'. "
            "Must be lowercase, start with a letter, contain only [a-z0-9_], max 64 chars."
        )
    return None


def _load_skill_source(name: str) -> str | None:
    path = _skill_path(name)
    if not path.exists():
        return None
    return path.read_text(encoding="utf-8")


def _load_skill_module(name: str) -> tuple[types.ModuleType | None, str | None]:
    """Load skill module; return (module, error_string).

    Internal load errors are logged server-side; only a sanitised message is
    returned so that stack traces are never exposed to the HTTP caller.
    """
    source = _load_skill_source(name)
    if source is None:
        return None, f"Skill '{name}' not found."
    try:
        spec = importlib.util.spec_from_file_location(f"chadaupy_skill_{name}", _skill_path(name))
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)  # type: ignore[attr-defined]
        return mod, None
    except Exception as exc:
        # Log full traceback server-side; only return exception type to avoid
        # py/stack-trace-exposure — never include str(exc) in the HTTP response.
        import logging
        logging.getLogger(__name__).exception("Failed to load skill '%s'", name)
        return None, f"Failed to load skill '{name}': {type(exc).__name__}"


def _introspect_module(mod: types.ModuleType) -> list[dict]:
    """Return a list of function schemas for all public functions in *mod*."""
    schemas = []
    for fn_name, fn in inspect.getmembers(mod, inspect.isfunction):
        if fn_name.startswith("_"):
            continue
        doc = (inspect.getdoc(fn) or "").strip()
        try:
            sig = str(inspect.signature(fn))
        except (TypeError, ValueError):
            sig = "(*args, **kwargs)"
        schemas.append({"name": fn_name, "description": doc, "signature": sig})
    return schemas


def _skill_metadata(name: str) -> dict:
    source = _load_skill_source(name)
    if source is None:
        return {}
    # Pull the module-level docstring from the raw source.
    mod_doc = ""
    try:
        mod_doc_match = re.match(r'\s*"""(.*?)"""', source, re.DOTALL)
        if mod_doc_match:
            mod_doc = mod_doc_match.group(1).strip()
    except Exception:
        pass
    stat = _skill_path(name).stat()
    return {
        "name": name,
        "description": mod_doc.splitlines()[0] if mod_doc else "",
        "doc": mod_doc,
        "size_bytes": stat.st_size,
        "modified": int(stat.st_mtime),
    }


# ── Routes ─────────────────────────────────────────────────────────────────


@app.get("/health")
def health():
    return jsonify({"status": "ok", "skills_dir": str(SKILLS_DIR), "ts": int(time.time())})


@app.get("/api/v1/skills")
def list_skills():
    SKILLS_DIR.mkdir(parents=True, exist_ok=True)
    skills = []
    for p in sorted(SKILLS_DIR.glob("*.py")):
        name = p.stem
        if name.startswith("_"):
            continue
        skills.append(_skill_metadata(name))
    return jsonify({"skills": skills, "count": len(skills)})


@app.post("/api/v1/skills")
def create_skill():
    data = request.get_json(force=True, silent=True) or {}
    name = str(data.get("name", "")).strip()
    source = str(data.get("source", "")).strip()

    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    path = _skill_path(name)
    if path.exists():
        return jsonify({"error": f"Skill '{name}' already exists. Use PUT to update."}), 409

    if not source:
        # Generate a minimal skeleton
        source = (
            f'"""\n{name} — CLU/BOX Python skill.\n"""\nfrom __future__ import annotations\n\n'
            f'# Splinter is injected by PythonBridge — do not import.\nSplinter = None  # type: ignore\n\n\n'
            f'def run(**kwargs) -> str:\n    """TODO: describe what this skill does."""\n    return "[{name}] not yet implemented"\n'
        )

    SKILLS_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return jsonify({"created": name, "path": str(path)}), 201


@app.get("/api/v1/skills/<name>")
def read_skill(name: str):
    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    source = _load_skill_source(name)
    if source is None:
        return jsonify({"error": f"Skill '{name}' not found."}), 404

    meta = _skill_metadata(name)
    meta["source"] = source
    return jsonify(meta)


@app.put("/api/v1/skills/<name>")
def update_skill(name: str):
    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    if not _skill_path(name).exists():
        return jsonify({"error": f"Skill '{name}' not found. Use POST to create."}), 404

    data = request.get_json(force=True, silent=True) or {}
    source = str(data.get("source", "")).strip()
    if not source:
        return jsonify({"error": "'source' field is required."}), 400

    _skill_path(name).write_text(source, encoding="utf-8")
    return jsonify({"updated": name})


@app.delete("/api/v1/skills/<name>")
def delete_skill(name: str):
    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    path = _skill_path(name)
    if not path.exists():
        return jsonify({"error": f"Skill '{name}' not found."}), 404

    path.unlink()
    return jsonify({"deleted": name})


@app.get("/api/v1/skills/<name>/schema")
def skill_schema(name: str):
    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    mod, load_err = _load_skill_module(name)
    if load_err:
        return jsonify({"error": load_err}), 404 if "not found" in load_err else 500

    assert mod is not None
    functions = _introspect_module(mod)
    return jsonify({"skill": name, "functions": functions})


@app.post("/api/v1/skills/<name>/run")
def run_skill(name: str):
    err = _validate_name(name)
    if err:
        return jsonify({"error": err}), 400

    mod, load_err = _load_skill_module(name)
    if load_err:
        return jsonify({"error": load_err}), 404 if "not found" in load_err else 500

    assert mod is not None
    if not hasattr(mod, "run"):
        return jsonify({"error": f"Skill '{name}' does not expose a 'run' function."}), 400

    kwargs: dict[str, Any] = request.get_json(force=True, silent=True) or {}

    try:
        result = mod.run(**kwargs)
        return jsonify({"result": str(result)})
    except Exception as exc:
        # Log full traceback server-side; return only exception type to avoid
        # py/stack-trace-exposure — never include str(exc) in the HTTP response.
        import logging
        logging.getLogger(__name__).exception("Skill '%s' raised an exception", name)
        return jsonify({"error": f"Skill execution error: {type(exc).__name__}"}), 500


# ── Entry point ────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print(f"Chadaupy skill manager starting on {HOST}:{PORT}")
    print(f"Skills directory: {SKILLS_DIR}")
    app.run(host=HOST, port=PORT, debug=False)
