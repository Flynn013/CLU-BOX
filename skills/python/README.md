# CLU/BOX Python Skills

Python skills run **directly inside the Chaquopy 3.11 interpreter** that is
embedded in the CLU/BOX Android app. Unlike JS skills (which spin up a hidden
WebView) or SKILL.md-only skills (which rely on native tools), Python skills
have bidirectional access to every CLU/BOX subsystem through the `Splinter`
facade.

## How Python Skills Work

1. The SKILL.md for a Python skill instructs the LLM to call the `run_python`
   tool with the target script module name and any JSON arguments.
2. `PythonBridge.executeScript` (or `PythonBridge.introspectModule`) is called
   by the Kotlin side.
3. The Python interpreter executes the script in an **isolated namespace** so
   scripts cannot pollute each other's state.
4. `Splinter` (an alias for `SplinterAPI.INSTANCE`) is pre-injected into the
   namespace, giving the script full CRUD access to FILE_BOX, BRAIN_BOX,
   SKILL_BOX, LNK_BOX, and the shell.

## Script Contract

Each skill script MUST expose a top-level `run(**kwargs) -> str` function.
The `kwargs` are the JSON parameters passed by the LLM via `run_python`.

```python
def run(**kwargs) -> str:
    """One-line description of what this skill does."""
    ...
    return "result text"
```

Helper functions and module-level constants are allowed; only `run` is called
by the framework.

## Available Skills

| File                    | Description                                           |
|-------------------------|-------------------------------------------------------|
| `context_pager.py`      | Page through large files/logs that exceed token limit |
| `file_manager.py`       | Read, write, list, and export FILE_BOX workspace      |
| `terminal_commander.py` | Execute shell commands inside the sandbox             |

## Adding a New Python Skill

1. Create `skills/python/my_skill.py` with a `run(**kwargs) -> str` function.
2. Create `skills/python/my_skill/SKILL.md` (or add a top-level SKILL.md for
   the skill) that instructs the LLM to call `run_python` with
   `module="my_skill"`.
3. `PythonBridge.introspectModule("my_skill")` will automatically generate a
   JSON tool schema from your function's type annotations and docstring.

## Accessing CLU/BOX APIs

Inside any skill script `Splinter` is always available:

```python
# FILE_BOX
Splinter.fileBoxRead("notes.md")
Splinter.fileBoxWrite("output.txt", "hello world")

# BRAIN_BOX
Splinter.brainBoxStore("key", "value")
Splinter.brainBoxRecall("key")

# Shell (sandboxed)
Splinter.shell("ls -la")
```
