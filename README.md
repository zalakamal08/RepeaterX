# RepeaterX

An AI-native Burp Suite extension that enhances Burp Repeater with AI agent integration, project persistence, and a local REST API.

[![Build RepeaterX](https://github.com/zalakamal08/RepeaterX/actions/workflows/build.yml/badge.svg)](https://github.com/zalakamal08/RepeaterX/actions/workflows/build.yml)

## Features

- **Repeater-like UI** — Familiar Burp Repeater interface with native Burp HTTP request/response editors
- **Tab Management** — Multiple tabs with history, notes, duplicate, rename, and close
- **Request History** — Full history per tab and globally, with search and filter
- **Project Persistence** — Save/load all tabs, requests, responses, and notes to JSON; auto-saves every 30s
- **AI REST API** — Local HTTP API on `127.0.0.1:7331` for AI agents and automation frameworks
- **Context Menu** — "Send to RepeaterX" from Proxy, Target, Logger, and all Burp tools
- **Diff Viewer** — Compare requests and responses side by side with color highlighting
- **Global Search** — Search across all requests, responses, notes, and history
- **Export** — Export tabs and full history as JSON or TXT

## Installation

1. Download `RepeaterX-1.0.0.jar` from [Releases](https://github.com/zalakamal08/RepeaterX/releases)
2. Open Burp Suite → **Extensions** → **Installed** → **Add**
3. Select the JAR file and click **Next**
4. The **RepeaterX** tab will appear in the Burp Suite toolbar

## Building from Source

Requirements: Java 17+, Gradle 8.5

```bash
git clone https://github.com/zalakamal08/RepeaterX.git
cd RepeaterX
gradle shadowJar
# Output: build/libs/RepeaterX-1.0.0.jar
```

The CI pipeline builds the JAR automatically on every push — download from the [Actions](https://github.com/zalakamal08/RepeaterX/actions) tab.

## AI Agent API

The extension starts a local REST API server at `http://127.0.0.1:7331`.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status` | API health and stats |
| GET | `/tabs` | List all open tabs |
| POST | `/tabs/create` | Create a new tab |
| POST | `/tabs/delete` | Delete a tab by ID |
| POST | `/tabs/duplicate` | Duplicate a tab |
| GET | `/history` | Get request history (supports `?q=`, `?status=`, `?method=`, `?tab=`, `?limit=`, `?offset=`) |
| GET | `/request/{id}` | Get full request by history entry ID |
| GET | `/response/{id}` | Get full response by history entry ID |
| POST | `/send` | Send an HTTP request |
| POST | `/replay` | Replay a history entry |
| GET | `/search?q={query}` | Search history |

### Example Usage

```bash
# Check API status
curl http://127.0.0.1:7331/status

# List tabs
curl http://127.0.0.1:7331/tabs

# Create a new tab
curl -X POST http://127.0.0.1:7331/tabs/create \
  -H "Content-Type: application/json" \
  -d '{"name": "IDOR Test", "rawRequest": ""}'

# Send a request
curl -X POST http://127.0.0.1:7331/send \
  -H "Content-Type: application/json" \
  -d '{
    "host": "example.com",
    "port": 443,
    "https": true,
    "method": "GET",
    "url": "https://example.com/api/user/1",
    "rawRequest": "GET /api/user/1 HTTP/1.1\r\nHost: example.com\r\nAuthorization: Bearer TOKEN\r\n\r\n"
  }'

# Search history
curl "http://127.0.0.1:7331/history?q=admin&limit=20"

# Replay a history entry
curl -X POST http://127.0.0.1:7331/replay \
  -H "Content-Type: application/json" \
  -d '{"historyId": "uuid-of-history-entry"}'
```

### Response Structures

**Request object:**
```json
{
  "id": "uuid",
  "method": "POST",
  "url": "https://target.com/api/user",
  "host": "target.com",
  "port": 443,
  "https": true,
  "headers": [["Content-Type", "application/json"]],
  "body": "{\"id\": 1}",
  "rawRequest": "POST /api/user HTTP/1.1\r\n...",
  "timestamp": 1718000000000
}
```

**Response object:**
```json
{
  "id": "uuid",
  "statusCode": 200,
  "statusMessage": "OK",
  "headers": [["Content-Type", "application/json"]],
  "body": "{\"user\": \"admin\"}",
  "rawResponse": "HTTP/1.1 200 OK\r\n...",
  "responseTime": 152,
  "responseSize": 10240,
  "timestamp": 1718000000000
}
```

## Project Persistence

Projects auto-save every 30 seconds to `~/.repeaterx/project.json`. All tabs, requests, responses, history, and notes are preserved across Burp restarts.

Manual **Save Project** / **Load Project** buttons are in the toolbar.

## Architecture

```
src/main/java/com/repeaterx/
├── RepeaterXExtension.java      # Burp extension entry point
├── model/
│   ├── RequestData.java         # HTTP request model
│   ├── ResponseData.java        # HTTP response model
│   ├── HistoryEntry.java        # Single history entry
│   ├── TabData.java             # Tab state
│   └── ProjectData.java        # Full project state
├── ui/
│   ├── RepeaterXPanel.java      # Main panel & tab container
│   ├── RepeaterTab.java         # Individual tab UI
│   ├── DiffPanel.java           # Request/response diff viewer
│   └── SearchPanel.java         # Global search
├── api/
│   └── ApiServer.java           # REST API server (port 7331)
├── core/
│   ├── HistoryManager.java      # History storage & search
│   ├── ProjectManager.java      # Project save/load/auto-save
│   └── RequestSender.java       # HTTP request sender via Montoya API
└── burp/
    └── ContextMenuHandler.java  # "Send to RepeaterX" context menu
```

## License

MIT License — see [LICENSE](LICENSE)
