# Sketchware Pro MCP Server

A lightweight, MCP-compatible JSON-RPC server (Java 17, `com.sun.net.httpserver`,
no framework) that lets an authorized AI agent inspect and safely modify
**Sketchware Pro project directories** — the same `.sketchware` structures the
app itself uses.

The server is a plain desktop module. It is **not** packaged into the Android
APK and never accesses Android private storage: it only operates on the folder
you pass via `--workspace`.

## Build

```bash
gradle :mcp-server:test      # run unit tests
gradle :mcp-server:fatJar    # build mcp-server/build/libs/mcp-server-1.0.0-all.jar
```

## Run

```bash
java -jar mcp-server/build/libs/mcp-server-1.0.0-all.jar \
  --workspace /path/to/.sketchware \
  --port 8080
```

| Flag | Meaning |
|---|---|
| `--workspace <dir>` | Workspace root (a `.sketchware` directory or any parent). All tool paths are confined to it. |
| `--host` | Bind address. Default `127.0.0.1`. Non-loopback requires `MCP_TOKEN`. |
| `--port` | Default `8080`. |
| `--allow-write` | Enables mutating tools (atomic writes with automatic backups). |
| `--allow-destructive` | Additionally enables delete/restore tools. |

Environment variables:

* `GEMINI_API_KEY` — enables the `generate_block_code` tool. Never logged,
  never written to disk by the server.
* `MCP_TOKEN` — bearer token required for non-loopback binds.

## Protocol

`POST /mcp` with JSON-RPC 2.0:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"project.metadata","arguments":{"projectId":"601"}}}
```

`GET /health` returns `ok`.

## Tools

| Tool | Description |
|---|---|
| `project.list` | Discover projects (`data/<id>/project_config`). |
| `project.metadata` | Read the raw `project_config` map (app name, package, SDKs…). |
| `project.inspect` | Unified overview: config, activities, custom blocks, injections, resources, assets, fonts, sounds. |
| `view.list` | List activity/view folders under `file/`. |
| `component.tree` | List layout/component files of one activity. |
| `component.update_property` | Update one attribute in a view XML (atomic + backup; refuses if property absent or oldValue mismatched). |
| `source.list` / `source.read` / `source.write` / `source.delete` | File access inside the workspace boundary. Delete is destructive-gated. |
| `resource.search` | Grep-like search across workspace text files. |
| `manifest.add_permission` | Insert `<uses-permission>` if not already present. |
| `snapshot.create` / `snapshot.restore` | Snapshot a project folder into `.ai_snapshots/`; restore is destructive-gated. |
| `generate_block_code` | Natural language → validated block plan JSON via Gemini. |
| `logcat.read` | Read log files placed inside the workspace only. |
| `build.run` | Returns guidance: Sketchware Pro builds happen on-device. |

## Project format

Sketchware Pro stores projects on device at
`.sketchware/data/<sc_id>/`:

```
.sketchware/
├── data/<sc_id>/
│   ├── project_config        # JSON map: my_app_name, my_ws_name, package_name, sc_id, ...
│   ├── file/<Activity>/      # per-activity views/components/logic
│   ├── custom_blocks         # JSON array of ExtraBlockInfo used by this project
│   └── injection/            # manifest/xml injection fragments
├── resources/block/My Block/block.json   # global custom ("extra") blocks
├── assets/  fonts/  sounds/
└── ...
```

All writes are atomic (tmp file + move) and the previous version is copied to
`.ai_snapshots/backup-<timestamp>-<name>` first.

## End-to-end example (Python client)

```python
import json, requests

URL = "http://127.0.0.1:8080/mcp"

def rpc(method, params=None):
    r = requests.post(URL, json={"jsonrpc": "2.0", "id": 1,
                                 "method": method, "params": params or {}})
    return r.json()

# 1. discover tools
print(rpc("tools/list"))

# 2. list projects
projects = rpc("tools/call", {"name": "project.list"})["result"]["content"][0]["text"]
pid = json.loads(projects)["projects"][0]["id"]

# 3. inspect project overview
overview = rpc("tools/call", {"name": "project.inspect", "arguments": {"projectId": pid}})

# 4. generate blocks from natural language (needs GEMINI_API_KEY)
plan = rpc("tools/call", {"name": "generate_block_code",
          "arguments": {"description": "When button1 clicked show toast Hello"}})

# 5. update a component property safely
rpc("tools/call", {"name": "component.update_property",
    "arguments": {"file": ".sketchware/data/%s/file/MainActivity/view/main.xml" % pid,
                  "property": "text", "value": "Login"}})

# 6. snapshot before bigger edits
rpc("tools/call", {"name": "snapshot.create",
    "arguments": {"directory": ".sketchware/data/%s" % pid, "name": "before-refactor"}})
```

## Security considerations

* Binds loopback only unless explicitly overridden **and** `MCP_TOKEN` set.
* Path traversal is rejected; every read/write stays inside the workspace.
* Writes are opt-in; deletes/restores are double opt-in.
* The Gemini key lives only in the process environment.
