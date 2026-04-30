"""
CLU/BOX Python runner helper — loaded once by Chaquopy at startup.

Provides a single entry-point ``run(code)`` that:
  1. Compiles the caller-supplied code string.
  2. Redirects stdout/stderr to in-memory StringIO buffers so the Kotlin
     layer can capture output without touching the Android log.
  3. Executes the code in an isolated namespace (``{}``), protecting the
     interpreter's global state from accidental mutation.
  4. Always restores the real stdout/stderr, even on exception.
  5. Returns a two-element tuple: (stdout_text, stderr_text).

All exceptions are caught inside the ``exec`` block; the formatted
traceback is written to stderr so the caller always gets a string, never
raises.
"""

import io
import sys
import traceback


def run(code: str):
    """Execute *code* and return (stdout, stderr) as plain strings.

    The isolated namespace ``{}`` prevents scripts from polluting the
    interpreter's global state between calls (e.g. clobbering imported
    modules or global variables).  It does **not** restrict OS-level access:
    scripts can still import the standard library, read/write the filesystem
    inside the app's sandbox, or make network calls.  This is intentional —
    CLU/BOX agents need those capabilities.  The Android OS sandbox (SELinux,
    app UID isolation) provides the outer security boundary.
    """
    old_out, old_err = sys.stdout, sys.stderr
    buf_out = io.StringIO()
    buf_err = io.StringIO()
    sys.stdout = buf_out
    sys.stderr = buf_err
    try:
        compiled = compile(code, "<clu_script>", "exec")
        exec(compiled, {})  # noqa: S102 — intentional sandboxed exec
    except BaseException:  # catch SystemExit, KeyboardInterrupt, etc.
        buf_err.write(traceback.format_exc())
    finally:
        sys.stdout = old_out
        sys.stderr = old_err
    return buf_out.getvalue(), buf_err.getvalue()
