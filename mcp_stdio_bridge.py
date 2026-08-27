#!/usr/bin/env python3
"""
Stdio <-> HTTP bridge for Sketchware Pro's embedded MCP server.

Lets desktop MCP clients (Claude Desktop, opencode, etc.) talk to the MCP
server running inside Sketchware Pro on an Android device.

Usage:
  1. In Sketchware Pro, open AI & MCP settings, enable MCP, and (recommended)
     leave "Bind to network" OFF so the server listens on 127.0.0.1 only.
  2. Forward the device port to your desktop:
         adb forward tcp:8765 tcp:8765
  3. Run this bridge (it speaks MCP over stdin/stdout):
         python mcp_stdio_bridge.py
  4. Register it as an MCP server in your client. Example opencode config:
         {
           "mcpServers": {
             "sketchware-pro": {
               "command": "python",
               "args": ["/path/to/mcp_stdio_bridge.py"]
             }
           }
         }
     Or with a token:
         MCP_TOKEN=yourtoken python mcp_stdio_bridge.py

The bridge forwards newline-delimited JSON-RPC messages from stdin to the
device's HTTP /mcp endpoint and writes the JSON-RPC responses back to stdout.
It does NOT implement MCP itself -- the device server does; this only adapts
the transport (stdio <-> streamable HTTP).
"""

import os
import sys
import json
import urllib.request
import urllib.error

ENDPOINT = os.environ.get("MCP_URL", "http://127.0.0.1:8765/mcp")
TOKEN = os.environ.get("MCP_TOKEN", "")

# Per JSON-RPC over stdio, notifications have no "id" and the server replies
# with an empty body; we must not echo those to stdout.
def _is_notification(message):
    try:
        return "id" not in json.loads(message)
    except Exception:
        return False


def forward(line):
    payload = line.encode("utf-8")
    req = urllib.request.Request(ENDPOINT, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("X-MCP-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
    except Exception:
        # Keep the stdio channel alive even if a single request fails.
        return
    if body and body.strip():
        sys.stdout.write(body.strip() + "\n")
        sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        # Skip notifications: server returns empty, nothing to echo.
        if _is_notification(line):
            # Still forward so the server processes it, but expect no reply.
            forward(line)
            continue
        forward(line)


if __name__ == "__main__":
    main()
