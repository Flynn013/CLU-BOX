"""mcp_stdio_server — in-process MCP server router for LnkBoxBridge.

Chaquopy hosts a single, long-running CPython interpreter inside the
CLU/BOX Android process.  This module turns that interpreter into an MCP
"stdio" peer **without** spawning a sub-process: the Kotlin side instead
calls :func:`start_server` to obtain a tiny object with `serve(frame)` /
`shutdown()` methods and feeds JSON-RPC frames straight in.

The router accepts any user module that exposes either:

* a callable named ``handle(method, params)`` returning a JSON-encodable
  result, **or**
* a class named ``Server`` whose instance exposes the same callable.

If neither is present we fall back to the built-in echo server so that the
agent can verify connectivity end-to-end without authoring a real MCP
implementation first.
"""

from __future__ import annotations

import importlib
import json
import sys
import traceback
from typing import Any, Callable


def start_server(module_name: str, args_json: str = "{}") -> "_ServerHandle":
    """Resolve *module_name* into a server handle.

    The Kotlin side does not need to know whether the module is the built-in
    echo router or a user-supplied implementation; both look identical from
    outside.
    """
    args = json.loads(args_json or "{}")
    handler: Callable[[str, Any], Any]

    if module_name in ("echo", "default", ""):
        handler = _echo_handler
    else:
        try:
            mod = importlib.import_module(module_name)
        except Exception as exc:  # noqa: BLE001 — re-raise as MCP error
            raise RuntimeError(f"failed to import MCP server '{module_name}': {exc}") from exc

        if hasattr(mod, "Server"):
            instance = mod.Server(**args) if args else mod.Server()
            if not hasattr(instance, "handle"):
                raise RuntimeError(f"module {module_name}.Server has no 'handle' method")
            handler = instance.handle
        elif hasattr(mod, "handle"):
            handler = mod.handle
        else:
            raise RuntimeError(
                f"MCP module {module_name} must expose 'handle(method, params)' or a 'Server' class"
            )

    return _ServerHandle(handler)


class _ServerHandle:
    """Thin object owning a single user handler.

    Exposes :meth:`serve` (JSON-RPC frame in -> JSON-RPC frame out) and
    :meth:`shutdown` (best-effort cleanup).
    """

    def __init__(self, handler: Callable[[str, Any], Any]) -> None:
        self._handler = handler

    def serve(self, frame_json: str) -> str:
        """Process one JSON-RPC frame and return the encoded response."""
        try:
            frame = json.loads(frame_json)
            method = frame.get("method", "")
            params = frame.get("params", {})
            req_id = frame.get("id")
            try:
                result = self._handler(method, params)
                response = {"jsonrpc": "2.0", "id": req_id, "result": result}
            except Exception as exc:  # noqa: BLE001 — wrap into MCP error
                response = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32000,
                        "message": str(exc),
                        "data": traceback.format_exc(limit=4),
                    },
                }
        except json.JSONDecodeError as exc:
            response = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": f"Parse error: {exc}"},
            }
        return json.dumps(response, ensure_ascii=False)

    def shutdown(self) -> None:
        """Drop the handler reference; allows GC of any heavy state."""
        self._handler = _noop_handler


# ── built-in handlers ──────────────────────────────────────────────────────


def _echo_handler(method: str, params: Any) -> Any:
    """Default loopback handler used when no user module is supplied."""
    return {
        "method": method,
        "params": params,
        "python": sys.version,
        "echo": True,
    }


def _noop_handler(_method: str, _params: Any) -> Any:
    return {"shutdown": True}
