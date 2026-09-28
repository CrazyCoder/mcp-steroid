Output contract: machine-readable results of steroid_execute_code and steroid_ui

For programs that call MCP Steroid: the "output":"json" envelope, its fields, and the rule that it only grows.

# Output contract

A model reads a tool's text result, which carries notices, hints and framing that change between releases. A
program that reads the result passes `"output":"json"` to `steroid_execute_code` or `steroid_ui`. The result is
then exactly one text content item that holds one JSON object, the envelope, with nothing before or after it.
`isError` is true exactly when the envelope's `ok` is false.

## The contract only grows

- Version 1 is the version this article describes: every envelope has `"contract": 1`.
- A released field keeps its name, type and meaning in every later version. New data comes as new fields, or as
  new values of `notices[].kind`.
- A reader ignores fields it does not know. It does not rely on the order of fields, or of kinds.
- A build of MCP Steroid fails its tests when a released field changes: a frozen copy of each version's fields is
  checked on every build.

## Fields of every envelope

| Field | Type | Meaning |
|---|---|---|
| `contract` | number | The contract version, `1` |
| `tool` | string | The tool that answered, such as `steroid_execute_code` |
| `ok` | boolean | False when the call failed; the same as `isError` negated |
| `notices` | array | The IDE's notices, each with `kind`, `side` and `text`; see below |
| `text` | string | Only in an envelope the tool did not build itself: the text of an error the call ran into before the tool ran, such as an unknown project |

`notices[].kind` is `IDE_FREEZE`, `IDE_ERRORS`, `EDITOR_BANNERS`, `EDITOR_ERRORS`, `IDE_NOTIFICATIONS`,
`BUILD_FAILED`, `RUN_FAILED`, `LOW_MEMORY` or `EDITOR_STATE`, or another name in the same upper-case form. `side` is `backend` or `frontend` in Split Mode and absent in a regular IDE. `text`
is the notice as a model reads it.

A call that a UI freeze holds up is answered with `still_running` set to true and the freeze in `notices`; the
call keeps running in the IDE. A call cancelled inside the IDE is answered with `cancelled` set to true. Both
have `ok` false and `text`.

## steroid_execute_code

| Field | Type | Meaning |
|---|---|---|
| `execution_id` | string | The execution's id, for `steroid_execute_feedback` and the execution folder |
| `stdout` | string | Exactly what the script printed with `println`, `printJson`, `printCsv` and `printToon`, one call per line, and nothing else. Line breaks are `\n` on every OS |
| `result` | any JSON | Present when `stdout` is one JSON document, as a script that ends with `printJson(value)` prints: that document, parsed |
| `messages` | array of strings | What MCP Steroid itself said: warnings, notes and hints, such as a compiler warning |
| `errors` | array | Each with `kind`, `message`, and for an exception `stack_trace`. `kind` is `exception` for an exception the script printed or hit, and `failed` for why the run failed |
| `images` | array | Each with `mime_type` and `file`, for an image the script returned; the image itself is not in the envelope |

A script's output never mixes with MCP Steroid's own lines: read `result` or `stdout`, and decide success from
`ok`.

## steroid_ui

| Field | Type | Meaning |
|---|---|---|
| `execution_id` | string | The execution's id |
| `verdict` | string | For a scenario or a call with a bug check: the verdict line, such as `REPRODUCED at step 4: ...` |
| `verdict_kind` | string | With `verdict`: `PASSED`, `FAILED`, `BROKEN`, `INCOMPLETE`, `REPRODUCED` or `NOT_REPRODUCED` |
| `report` | string | The report a model reads: one line per step, then the verdict |
| `files` | array | For a folder or a list of scenario files: one entry per file, with `file`, `verdict`, `ok` and `report`; the envelope's `report` is then the summary |

## Example

```
{"contract": 1, "tool": "steroid_execute_code", "ok": true,
 "execution_id": "eid_20260928T010203-123-mcp-t1",
 "stdout": "{\"files\":3}", "result": {"files": 3},
 "messages": [], "errors": [], "images": [],
 "notices": [{"kind": "IDE_ERRORS", "side": "backend", "text": "IDE ERRORS in the backend: ..."}]}
```
