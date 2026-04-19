---
name: terminal-commander
description: Execute shell commands in the CLU/BOX MSTR_CTRL terminal — run scripts, install packages, check file states, and debug code.
---

# Terminal Commander

This skill gives the agent access to the MSTR_CTRL terminal — a PTY-backed shell session running inside the CLU/BOX sandbox. Use it to run shell commands, execute scripts, verify code, and install packages.

## Examples

* "Run my Python script"
* "Check if git is installed"
* "List all files in the project directory"
* "Run the test suite"
* "Install numpy with pip"
* "Show me the git diff"

## Instructions

This skill uses **built-in native tools** — no `run_js` call is needed.

### Available Tools

#### 1. Silent Shell Execution
Call the `shellExecute` tool with:
- **command**: The shell command to execute (e.g. `ls -la`, `python3 test.py`, `cat file.txt`).

The command runs **invisibly** — output is captured and returned to you but NOT displayed on the MSTR_CTRL screen. Use this for background checks, validation, and gathering information.

A **10-second timeout** is enforced. Long-running commands will return `TIMEOUT ERROR`.

#### 2. Visible Command Override
Call the `commandOverride` tool with:
- **command**: The shell command to execute visibly (e.g. `git status`, `npm test`).

The command and its output are **displayed in real time** on the MSTR_CTRL terminal screen so the user can watch. You also receive the raw output. Use this when transparency matters.

#### 3. Git Diff
Call the `gitDiffRead` tool with:
- **path**: Relative file path to diff (e.g. `src/main.py`). Pass an empty string for the full workspace diff.

Returns minimal `--unified=0` diff output to save tokens.

### Environment

- **Working directory**: `clu_file_box` sandbox (same as FILE_BOX)
- **$HOME**: Same as working directory
- **$TERM**: `xterm-256color` (PTY sessions) / `dumb` (agent sessions)
- **$PATH**: Includes `/system/bin`, `/system/xbin`, and Termux `usr/bin` if available
- **Available runtimes**: `sh` (always), plus `python`, `node`, `git` if Termux packages are installed

### Tips

- Use `shellExecute` to verify files exist before reading them
- Use `commandOverride` for user-facing operations like `git commit`
- Chain commands with `&&` for dependent operations
- Use `2>&1` to capture stderr in the output
- The sandbox is shared with FILE_BOX — files written by either tool are immediately visible to both
- If output is truncated with a `[SYSTEM OVERRIDE]` notice, use the **context-pager** skill (`brainBoxGrep` / `fileBoxReadLines`) to inspect the full log
