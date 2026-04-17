---
name: file-manager
description: Manage the CLU/BOX FILE_BOX workspace — read, write, list, and export code files from the sandboxed project directory.
---

# File Manager

This skill gives the agent direct access to the FILE_BOX sandboxed workspace. Use it to create projects, read source files, scan the workspace tree, and export completed work.

## Examples

* "Create a new Python project with main.py and utils.py"
* "Read the file at src/main.kt"
* "Show me the workspace file tree"
* "Write a README.md for this project"
* "Export the project as a ZIP file"

## Instructions

This skill uses **built-in native tools** — no `run_js` call is needed.

### Available Tools

#### 1. Write a File
Call the `fileBoxWrite` tool with:
- **file_path**: Relative path inside FILE_BOX (e.g. `my_app/src/main.kt`). Nested directories are auto-created.
- **content**: The full text/code content to write.

Only text-based file extensions are allowed (`.kt`, `.js`, `.py`, `.json`, `.md`, `.html`, `.ts`, `.css`, `.xml`, `.yaml`, `.sh`, etc.). Binary files are rejected.

Python (`.py`) and JavaScript (`.js`) files are **automatically syntax-checked** after writing. If validation fails, the file is deleted and an error is returned — fix the code and retry.

#### 2. Read a File
Call the `fileBoxRead` tool with:
- **file_path**: Relative path of the file to read (e.g. `my_app/src/main.kt`).

Returns the file content as a string (capped at 4096 characters).

#### 3. Scan the Workspace
Call the `workspaceMap` tool (no parameters).

Returns a JSON tree of all files and folders in the workspace. Use this to orient yourself before reading or writing.

#### 4. Multi-File Projects
For multi-file projects, use the **dual-agent workflow**:

1. Call `architectInit` with `project_goal` and a `blueprint_markdown` listing every file as `- [ ] path/to/file.ext`.
2. The system automatically queues the worker phase. Call `workerExecute` for each file with `target_file_path`, `code_content`, and `is_project_finished` (true only for the last file).
3. The blueprint is updated automatically as files are completed.

#### 5. Export
Users can export the workspace as a ZIP from the FILE_BOX UI. The agent can inform users about this capability.
