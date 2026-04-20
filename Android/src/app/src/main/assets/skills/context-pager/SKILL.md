---
name: context-pager
description: Paginate and search large files or truncated tool outputs to save memory. Use when output was truncated with a SYSTEM OVERRIDE notice.
---

# Context Pager

When a tool output is too large for the context window, the system automatically truncates it and saves the full version to a spill file. This skill lets you retrieve specific portions or search for keywords without loading the entire file.

## Examples

* "Read lines 50–100 of the truncated log"
* "Search the spill file for 'NullPointerException'"
* "Show me the last 30 lines of the build output"
* "Find 'FAILED' in the test log"

## Instructions

This skill uses **built-in native tools** — no `run_js` call is needed.

### When to Use

After any tool returns a `[SYSTEM OVERRIDE: Output too large...]` notice, the full output has been saved to a file path shown in the message (e.g. `BrainBox/temp_out/spill_1713567746.txt`). Use the tools below to inspect it.

### Available Tools

#### 1. Read Lines (Paginator)
Call the `fileBoxReadLines` tool with:
- **file_path**: Path to the file (e.g. `BrainBox/temp_out/spill_123.txt`)
- **start_line**: 0-based start line number (inclusive)
- **end_line**: 0-based end line number (exclusive)

Returns only the requested line range plus metadata (total line count). Use this to page through large files in chunks of 50 lines.

#### 2. Grep (Keyword Search)
Call the `brainBoxGrep` tool with:
- **file_path**: Path to the file to search
- **keyword**: The keyword or error string to search for (case-insensitive)

Returns all matching lines with 2 lines of context above and below each match. Matching lines are prefixed with `>>>`.

### Tips

- Start with `brainBoxGrep` to jump directly to errors — it's faster than paging
- Use `fileBoxReadLines` with start_line=0, end_line=50 to see the beginning of a file
- The response includes `total_lines` so you know how far to page
- These tools also work on any FILE_BOX file, not just spill files
