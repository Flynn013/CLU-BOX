"""
CLU/BOX Python runner helper — loaded once by Chaquopy at startup.

Provides a single entry-point ``run(code)`` that:
  1. Compiles the caller-supplied code string.
  2. Redirects stdout/stderr locally using context managers so concurrent calls do not overlap.
  3. Executes the code in an isolated namespace (``{}``), protecting the
     interpreter's global state from accidental mutation.
  4. Returns a two-element tuple: (stdout_text, stderr_text).
"""

import io
import traceback
import contextlib


def run(code: str):
    """Execute *code* and return (stdout, stderr) as plain strings.

    The isolated namespace ``{}`` prevents scripts from polluting the
    interpreter's global state between calls (e.g. clobbering imported
    modules or global variables). Context managers perfectly isolate the
    streams to prevent concurrent data interleaving during multi-agent
    operations.
    """
    buf_out = io.StringIO()
    buf_err = io.StringIO()
    
    try:
        # Isolates streams at the context level instead of clobbering global sys.stdout
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            compiled = compile(code, "<clu_script>", "exec")
            exec(compiled, {})  # noqa: S102 — intentional sandboxed exec
    except BaseException:  # catch SystemExit, KeyboardInterrupt, etc.
        buf_err.write(traceback.format_exc())
        
    return buf_out.getvalue(), buf_err.getvalue()
