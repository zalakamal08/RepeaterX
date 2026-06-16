# RepeaterX — Burp Suite Extension

A professional Burp Suite extension that replicates the Burp Repeater workflow and adds a local REST API for AI-assisted testing, automation, and scripting.

[![Build RepeaterX](https://github.com/zalakamal08/RepeaterX/actions/workflows/build.yml/badge.svg)](https://github.com/zalakamal08/RepeaterX/actions/workflows/build.yml)

---

## Features

- **Repeater-style UI** — per-tab request/response editors with native Burp HTTP editors
- **History navigation** — ◄ ► arrows to browse per-tab history without leaving the view
- **Per-tab notes** — collapsible notes panel, persisted with the project
- **Color-coded status chips** — green 2xx · blue 3xx · orange 4xx · red 5xx
- **Ctrl+K shortcut** — send request from any focused field
- **Auto-save on close** — project is written to `~/.repeaterx/project.json` when Burp exits
- **30-second auto-save** — never lose work during a session
- **Context menu** — right-click any Burp request → "Send to RepeaterX"
- **Tab management** — new, duplicate, rename, close
- **Export** — save tabs or full history as JSON or plain text
- **Global search** — search across all history entries
- **Response diff** — side-by-side comparison with color highlighting
- **REST API** — local HTTP server (default `0.0.0.0:7331`) for AI agents and scripts
- **Configurable API server** — change host/port at runtime via the ⚙ button; config persists across restarts
- **MCP server** — built-in Model Context Protocol server so Claude and other AI agents can control Burp directly

---

## Requirements

| Requirement | Version |
|---|---|
| Burp Suite Pro / Community | 2024.x+ |
| Java | 17+ |

---

## Installation

1. Download the latest `RepeaterX-all.jar` from [GitHub Releases](https://github.com/zalakamal08/RepeaterX/releases) or from the [CI Actions page](https://github.com/zalakamal08/RepeaterX/actions).
2. In Burp Suite: **Extensions → Installed → Add → Select file** → choose the JAR.
3. The **RepeaterX** tab appears in the main Burp toolbar.

---

## Building from Source

Builds run exclusively through GitHub Actions — no local JAR generation needed.

The workflow (`.github/workflows/build.yml`) triggers on every push to `main`:

```
JDK 17 (Temurin)  →  Gradle 8.5  →  gradle shadowJar  →  upload artifact
```

Download the artifact from the **Actions** tab after a successful run.

---

## UI Overview

```
┌────────────────────────────────────────────────────────────────────────────┐
│  + New Tab  Duplicate  Rename │  Save  Load  Export  Search  Diff          │
│                                                    [API 0.0.0.0:7331] ⚙   │
├────────────────────────────────────────────────────────────────────────────┤
│  [Tab 1] ×  [GET /api/user] ×  [POST /login] ×                            │
├────────────────────────────────────────────────────────────────────────────┤
│  [Send]  [Cancel]  │ ◄  ►  3 sent │  [Notes]   Target: https://…  [200]   │
├────────────────────────────────────────────────────────────────────────────┤
│              Request              │             Response                   │
│                                   │                                        │
│                                   │                                        │
├────────────────────────────────────────────────────────────────────────────┤
│  1.2 kB  |  234 ms                                                         │
└────────────────────────────────────────────────────────────────────────────┘
```

### Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+K` | Send current tab's request |

### History Navigation

Use **◄** (older) and **►** (newer) to step through previously sent requests. The nav counter shows your position (`2 / 5`). Browsing history is read-only — return to the live editor by navigating to the newest entry or pressing **Send**.

### Status Code Colors

| Code range | Color | Example |
|---|---|---|
| 2xx | Green | 200 OK |
| 3xx | Blue | 302 Found |
| 4xx | Orange | 403 Forbidden |
| 5xx | Red | 500 Internal Server Error |

### API Server Settings

Click **⚙** (next to the API indicator in the toolbar) to open the settings dialog. Change host and port, then click **Apply & Restart**. The new config is saved to `~/.repeaterx/api-config.json` when Burp closes.

**Default:** `0.0.0.0:7331` (all interfaces).  
Set to `127.0.0.1` to restrict to localhost only.

---

## Project Auto-Save

RepeaterX saves your session to `~/.repeaterx/project.json` automatically:

- **On Burp close** — via the extension unload handler
- **Every 30 seconds** — while Burp is open

On next launch the project is restored automatically. Use **Save** / **Load** in the toolbar for named project files.

---

## REST API

The embedded HTTP server at `http://0.0.0.0:7331` (configurable) exposes a REST API for AI agents and automation tools.

See **[docs/API.md](docs/API.md)** for the full endpoint reference with request/response schemas and examples.

Quick health check:

```bash
curl http://localhost:7331/status
```

```json
{
  "status": "running",
  "version": "1.0.0",
  "host": "0.0.0.0",
  "port": 7331,
  "historySize": 42
}
```

---

## Architecture

```
src/main/java/com/repeaterx/
├── RepeaterXExtension.java      # Burp extension entry point
├── model/
│   ├── RequestData.java         # HTTP request model
│   ├── ResponseData.java        # HTTP response model
│   ├── HistoryEntry.java        # Single history entry
│   ├── TabData.java             # Per-tab state
│   └── ProjectData.java         # Full project state
├── ui/
│   ├── RepeaterXPanel.java      # Main panel + tab container
│   ├── RepeaterTab.java         # Individual tab UI
│   ├── DiffPanel.java           # Side-by-side diff viewer
│   └── SearchPanel.java         # Global history search
├── api/
│   └── ApiServer.java           # REST API server + MCP host
├── mcp/
│   └── McpSseServer.java        # Embedded MCP SSE server (PortSwigger-style)
├── core/
│   ├── ApiConfig.java           # API host/port config model
│   ├── HistoryManager.java      # History storage and search
│   ├── ProjectManager.java      # Project save/load/auto-save
│   └── RequestSender.java       # HTTP sender via Montoya API
└── burp/
    └── ContextMenuHandler.java  # "Send to RepeaterX" context menu
```

---

## Data Storage

| File | Purpose |
|---|---|
| `~/.repeaterx/project.json` | Auto-saved project (tabs, requests, responses, notes, history) |
| `~/.repeaterx/api-config.json` | API server host/port preference |

---

## MCP Server (AI Agent Integration)

RepeaterX embeds an MCP SSE server **directly inside the Burp extension JAR** — no separate process needed. This follows the same architecture as [PortSwigger's official burp-mcp extension](https://github.com/PortSwigger/mcp-server).

The MCP server starts automatically alongside the REST API on the same port (`0.0.0.0:7331` by default):

```
GET  /sse      → SSE stream (MCP transport)
POST /message  → JSON-RPC 2.0 requests
```

### Claude Desktop Setup

Claude Desktop requires stdio transport. Use [PortSwigger's MCP proxy JAR](https://github.com/PortSwigger/mcp-server/raw/main/libs/mcp-proxy-all.jar) as the bridge:

**1. Download the proxy:**
```bash
# macOS / Linux
curl -L -o ~/mcp-proxy-all.jar \
  https://github.com/PortSwigger/mcp-server/raw/main/libs/mcp-proxy-all.jar
```

**2. Add to Claude Desktop config** (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

```json
{
  "mcpServers": {
    "repeaterx": {
      "command": "java",
      "args": [
        "-jar",
        "/Users/yourname/mcp-proxy-all.jar",
        "--sse-url",
        "http://127.0.0.1:7331"
      ]
    }
  }
}
```

**3. Restart Claude Desktop** — RepeaterX tools will appear.

### SSE Clients (Cursor, VS Code, etc.)

Connect directly to the SSE endpoint:

```
http://127.0.0.1:7331/sse
```

### Available MCP Tools

| Tool | Description |
|---|---|
| `get_status` | Server status and open tab/history count |
| `list_repeater_tabs` | All open tabs with names, counts, notes |
| `create_repeater_tab` | Open a new tab (optionally pre-load a request) |
| `delete_repeater_tab` | Close a tab |
| `send_http_request` | Send a request through Burp's engine |
| `get_request_history` | Paginated history with filters (status, method, tab, search) |
| `search_history` | Full-text search across all history |
| `replay_history_entry` | Re-send a captured request |
| `get_history_request` | Full request details for a history entry |
| `get_history_response` | Full response details for a history entry |

### Example Prompts

> "Send a GET to api.example.com/users and tell me the response."  
> "Search my RepeaterX history for any 403s on /admin endpoints."  
> "Create a new tab called 'IDOR Test' with this request, then replay it with user IDs 1 through 10."  
> "List all my open tabs and their last status codes."

See **[docs/API.md](docs/API.md)** for the full REST + MCP reference.

---

## Contact

**Author:** kamalzala07@gmail.com  
**Repository:** https://github.com/zalakamal08/RepeaterX

---

## License

MIT License — see [LICENSE](LICENSE)
