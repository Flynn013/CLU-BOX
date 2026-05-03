"""
CLU/BOX Python runner helper — loaded once by Chaquopy at startup.

This module exposes two entry points used by the Kotlin side:

* ``run(code)`` — compile and execute *code* in an isolated namespace, with
  stdout/stderr captured via in-memory ``StringIO`` buffers. The God-mode
  ``Splinter`` Kotlin facade (an instance of
  ``com.google.ai.edge.gallery.data.splinter.SplinterAPI``) is injected into
  the namespace before execution, so dynamically-loaded skills can call
  ``Splinter.fileBoxRead("notes.md")`` and friends without any boilerplate.

* ``introspect(module_path)`` — load a Python module from disk and return a
  list of ``(name, doc, signature)`` triples for every top-level function. The
  Kotlin ``DynamicSchemaGenerator`` consumes this to produce JSON tool schemas
  for the active LLM (Gemini Cloud or LiteRT) at runtime.
"""

from __future__ import annotations

import contextlib
import importlib.util
import inspect
import io
import os
import sys
import traceback
from types import ModuleType
from typing import List, Tuple

# ``Splinter`` is set by ``PythonBridge.initialize`` via
# ``runner.put("Splinter", SplinterAPI.INSTANCE)`` immediately after this module
# is first imported. Default to ``None`` so ``import clu_runner`` never fails
# during static analysis or tests.
Splinter = None  # type: ignore[assignment]


def run(code: str) -> Tuple[str, str]:
    """Execute *code* and return ``(stdout, stderr)`` as plain strings.

    The isolated namespace prevents scripts from polluting the interpreter's
    global state between calls (e.g. clobbering imported modules). The
    ``Splinter`` God-mode facade is injected so skills can perform CRUD on
    every CLU/BOX subsystem.
    """
    buf_out = io.StringIO()
    buf_err = io.StringIO()
    namespace = {"__name__": "clu_skill", "Splinter": Splinter}

    try:
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            compiled = compile(code, "<clu_script>", "exec")
            exec(compiled, namespace)  # noqa: S102 — intentional sandboxed exec
    except BaseException:  # catch SystemExit, KeyboardInterrupt, etc.
        buf_err.write(traceback.format_exc())

    return buf_out.getvalue(), buf_err.getvalue()


def introspect(module_path: str) -> List[Tuple[str, str, str]]:
    """Return ``[(name, doc, signature), ...]`` for top-level functions in *module_path*.

    *module_path* may be:
      * an absolute filesystem path to a ``.py`` file, or
      * a dotted module name resolvable from ``sys.path``.

    The signature string includes parameter names, default values, and any
    ``typing`` annotations, exactly as produced by ``inspect.signature``.

    Failures are swallowed and reported as a single-element list whose ``name``
    is ``"__error__"`` and ``doc`` field carries the traceback — this lets the
    Kotlin side surface the issue without raising across the JNI boundary.
    """
    try:
        module = _load_module(module_path)
    except Exception as exc:  # pragma: no cover — surfaced via UI
        return [("__error__", f"failed to load {module_path}: {exc!s}", "")]

    out: List[Tuple[str, str, str]] = []
    for name, obj in inspect.getmembers(module, inspect.isfunction):
        if name.startswith("_"):
            continue
        try:
            sig = str(inspect.signature(obj))
        except (TypeError, ValueError):
            sig = "(*args, **kwargs)"
        doc = (inspect.getdoc(obj) or "").strip()
        out.append((name, doc, sig))
    return out


def _load_module(module_path: str) -> ModuleType:
    """Load a ``.py`` file or dotted module name and return the module object."""
    if os.path.sep in module_path or module_path.endswith(".py"):
        # Filesystem path — use importlib.util to load it fresh every time.
        spec = importlib.util.spec_from_file_location("clu_dynamic_skill", module_path)
        if spec is None or spec.loader is None:
            raise ImportError(f"could not build spec for {module_path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    # Dotted module name — go through the standard import machinery.
    return importlib.import_module(module_path)
