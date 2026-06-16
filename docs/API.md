# RepeaterX API Reference

RepeaterX exposes two complementary APIs on the same port (`0.0.0.0:7331` by default):

| Transport | Endpoints | Best for |
|---|---|---|
| **REST** | `/status`, `/tabs`, `/history`, `/send`, … | Scripts, curl, automation pipelines |
| **MCP SSE** | `/sse` + `/message` | AI agents (Claude, Cursor, VS Code Copilot) |

Both APIs start automatically with the extension. The host/port are configurable at runtime via the ⚙ toolbar button.

**Default base URL:** `http://localhost:7331`  
**Content-Type:** `application/json`  
**CORS:** `Access-Control-Allow-Origin: *`

---

## MCP Server (AI Agent Protocol)

The extension embeds an MCP SSE server following [PortSwigger's architecture](https://github.com/PortSwigger/mcp-server). No external process needed.

### Transport

```
GET  /sse       → long-lived SSE stream; immediately sends:
                  event: endpoint
                  data: /message?sessionId=<uuid>

POST /message?sessionId=<uuid>  → JSON-RPC 2.0 request
                                   HTTP 202 returned immediately
                                   JSON-RPC response arrives on SSE stream
```

### Claude Desktop (stdio bridge)

Claude Desktop requires stdio. Use [PortSwigger's proxy JAR](https://github.com/PortSwigger/mcp-server/raw/main/libs/mcp-proxy-all.jar):

```json
{
  "mcpServers": {
    "repeaterx": {
      "command": "java",
      "args": ["-jar", "/path/to/mcp-proxy-all.jar", "--sse-url", "http://127.0.0.1:7331"]
    }
  }
}
```

### MCP Tools

All tools return plain text (JSON-formatted string) in the MCP `content[].text` field.

| Tool name | Required params | Description |
|---|---|---|
| `get_status` | — | Server status, tab count, history size |
| `list_repeater_tabs` | — | All tabs with names, counts, notes |
| `create_repeater_tab` | — | Open a new tab; optional `tab_name`, `raw_request` |
| `delete_repeater_tab` | `tab_id` | Close a tab |
| `send_http_request` | `content`, `target_hostname` | Send raw HTTP through Burp |
| `get_request_history` | — | Paginated history; optional `query`, `status_code`, `method`, `tab_id`, `count`, `offset` |
| `search_history` | `query` | Full-text search across history |
| `replay_history_entry` | `entry_id` | Re-send a captured request |
| `get_history_request` | `entry_id` | Full request for a history entry |
| `get_history_response` | `entry_id` | Full response for a history entry |

### `send_http_request` parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `content` | string | Yes | — | Full raw HTTP request (`\r\n` line endings) |
| `target_hostname` | string | Yes | — | Target host, e.g. `api.example.com` |
| `target_port` | integer | No | `443` | Target port |
| `uses_https` | boolean | No | `true` | Use TLS |
| `method` | string | No | `GET` | HTTP method |

---

## REST API

### Table of Contents

1. [Status](#1-status)
2. [Tabs — List](#2-tabs--list)
3. [Tabs — Create](#3-tabs--create)
4. [Tabs — Delete](#4-tabs--delete)
5. [Tabs — Duplicate](#5-tabs--duplicate)
6. [History — List / Filter](#6-history--list--filter)
7. [Request — Get by ID](#7-request--get-by-id)
8. [Response — Get by ID](#8-response--get-by-id)
9. [Send Request](#9-send-request)
10. [Replay History Entry](#10-replay-history-entry)
11. [Search History](#11-search-history)
12. [Data Models](#12-data-models)
13. [Error Responses](#13-error-responses)

---

## 1. Status

Check whether the server is running and retrieve basic stats.

**Request**

```
GET /status
```

**Response** `200 OK`

```json
{
  "status": "running",
  "version": "1.0.0",
  "host": "0.0.0.0",
  "port": 7331,
  "historySize": 42
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | Always `"running"` when reachable |
| `version` | string | Extension version |
| `host` | string | Bound host interface |
| `port` | integer | Bound port |
| `historySize` | integer | Total number of history entries |

**Example**

```bash
curl http://localhost:7331/status
```

---

## 2. Tabs — List

Return a summary of all open tabs.

**Request**

```
GET /tabs
```

**Response** `200 OK`

```json
[
  {
    "id": "a1b2c3d4-...",
    "name": "GET /api/user",
    "historyCount": 5,
    "notes": "Check IDOR for user IDs 1–100",
    "updatedAt": 1718000000000,
    "lastStatus": 200
  }
]
```

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique tab UUID |
| `name` | string | Tab label (auto-updated to `METHOD /path` after first send) |
| `historyCount` | integer | Number of entries in this tab's history |
| `notes` | string | Current notes content (may be empty) |
| `updatedAt` | long | Unix timestamp (ms) of last modification |
| `lastStatus` | integer | HTTP status code of the last response (omitted if no response yet) |

**Example**

```bash
curl http://localhost:7331/tabs
```

---

## 3. Tabs — Create

Open a new tab, optionally pre-populated with a raw HTTP request.

**Request**

```
POST /tabs/create
Content-Type: application/json
```

```json
{
  "name": "IDOR Test",
  "rawRequest": "GET /api/user/1 HTTP/1.1\r\nHost: target.com\r\nAuthorization: Bearer TOKEN\r\n\r\n"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | No | Tab label. Defaults to `"New Tab"` |
| `rawRequest` | string | No | Full raw HTTP request to pre-load |

**Response** `201 Created`

```json
{
  "id": "a1b2c3d4-...",
  "name": "IDOR Test",
  "historyCount": 0,
  "notes": null,
  "updatedAt": 1718000000000
}
```

**Example**

```bash
curl -s -X POST http://localhost:7331/tabs/create \
  -H "Content-Type: application/json" \
  -d '{"name": "Auth bypass", "rawRequest": "GET /admin HTTP/1.1\r\nHost: target.com\r\n\r\n"}'
```

---

## 4. Tabs — Delete

Close a tab by its ID.

**Request**

```
POST /tabs/delete
Content-Type: application/json
```

```json
{
  "id": "a1b2c3d4-..."
}
```

**Response** `200 OK`

```json
{ "success": true }
```

**Response** `404 Not Found`

```json
{ "success": false }
```

**Example**

```bash
curl -s -X POST http://localhost:7331/tabs/delete \
  -H "Content-Type: application/json" \
  -d '{"id": "a1b2c3d4-..."}'
```

---

## 5. Tabs — Duplicate

Duplicate a tab (copies request, notes, and metadata — not history).

**Request**

```
POST /tabs/duplicate
Content-Type: application/json
```

```json
{
  "id": "a1b2c3d4-..."
}
```

**Response** `201 Created` — returns the original tab's `TabData`  
**Response** `404 Not Found` — tab ID not found

**Example**

```bash
curl -s -X POST http://localhost:7331/tabs/duplicate \
  -H "Content-Type: application/json" \
  -d '{"id": "a1b2c3d4-..."}'
```

---

## 6. History — List / Filter

Return paginated history entries. Supports several filter query parameters.

**Request**

```
GET /history
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `q` | string | Full-text search across URL, method, host, and response body |
| `status` | integer | Filter by HTTP response status code |
| `method` | string | Filter by HTTP method (e.g. `GET`, `POST`) |
| `tab` | string | Filter by tab UUID |
| `limit` | integer | Page size. Default `100` |
| `offset` | integer | Page offset. Default `0` |

**Response** `200 OK`

```json
{
  "total": 158,
  "offset": 0,
  "limit": 100,
  "entries": [
    {
      "id": "entry-uuid",
      "tabId": "tab-uuid",
      "timestamp": 1718000000000,
      "method": "POST",
      "url": "https://target.com/api/login",
      "status": 200,
      "responseTime": 143,
      "responseSize": 512
    }
  ]
}
```

**Examples**

```bash
# All history
curl http://localhost:7331/history

# Search for "admin"
curl "http://localhost:7331/history?q=admin"

# Only 403 responses
curl "http://localhost:7331/history?status=403"

# POST requests, newest 20
curl "http://localhost:7331/history?method=POST&limit=20"

# History for a specific tab
curl "http://localhost:7331/history?tab=a1b2c3d4-...&limit=50"

# Pagination
curl "http://localhost:7331/history?limit=25&offset=50"
```

---

## 7. Request — Get by ID

Retrieve the full request object for a history entry.

**Request**

```
GET /request/{historyEntryId}
```

**Response** `200 OK`

```json
{
  "id": "req-uuid",
  "method": "POST",
  "url": "https://target.com/api/user",
  "host": "target.com",
  "port": 443,
  "https": true,
  "headers": [
    ["Content-Type", "application/json"],
    ["Authorization", "Bearer TOKEN"]
  ],
  "body": "{\"username\": \"admin\"}",
  "rawRequest": "POST /api/user HTTP/1.1\r\nHost: target.com\r\n...",
  "timestamp": 1718000000000
}
```

**Response** `404 Not Found` — history entry or request not found

**Example**

```bash
curl http://localhost:7331/request/entry-uuid
```

---

## 8. Response — Get by ID

Retrieve the full response object for a history entry.

**Request**

```
GET /response/{historyEntryId}
```

**Response** `200 OK`

```json
{
  "id": "resp-uuid",
  "statusCode": 200,
  "statusMessage": "OK",
  "headers": [
    ["Content-Type", "application/json"],
    ["X-RateLimit-Remaining", "99"]
  ],
  "body": "{\"user\": {\"id\": 1, \"role\": \"admin\"}}",
  "rawResponse": "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n...",
  "responseTime": 152,
  "responseSize": 10240,
  "timestamp": 1718000000000
}
```

**Response** `404 Not Found`

**Example**

```bash
curl http://localhost:7331/response/entry-uuid
```

---

## 9. Send Request

Send an HTTP request directly through Burp's engine (respects Burp's proxy, TLS settings, and certificate trust).

**Request**

```
POST /send
Content-Type: application/json
```

```json
{
  "host": "target.com",
  "port": 443,
  "https": true,
  "method": "GET",
  "url": "https://target.com/api/user/1",
  "rawRequest": "GET /api/user/1 HTTP/1.1\r\nHost: target.com\r\nAuthorization: Bearer TOKEN\r\n\r\n"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `host` | string | Yes | Target hostname |
| `port` | integer | No | Target port. Default `443` |
| `https` | boolean | No | Use TLS. Default `false` |
| `method` | string | No | HTTP method. Default `GET` |
| `url` | string | No | Full URL (informational) |
| `rawRequest` | string | Yes | Full raw HTTP request bytes |

**Response** `200 OK`

```json
{
  "success": true,
  "requestId": "req-uuid",
  "status": 200,
  "responseTime": 143,
  "responseSize": 512,
  "response": {
    "id": "resp-uuid",
    "statusCode": 200,
    "statusMessage": "OK",
    "headers": [["Content-Type", "application/json"]],
    "body": "{\"result\": \"ok\"}",
    "rawResponse": "HTTP/1.1 200 OK\r\n...",
    "responseTime": 143,
    "responseSize": 512,
    "timestamp": 1718000000000
  }
}
```

**Response** `500` on send failure

```json
{
  "success": false,
  "error": "Connection refused: target.com:443"
}
```

**Example**

```bash
curl -s -X POST http://localhost:7331/send \
  -H "Content-Type: application/json" \
  -d '{
    "host": "httpbin.org",
    "port": 443,
    "https": true,
    "method": "GET",
    "url": "https://httpbin.org/get",
    "rawRequest": "GET /get HTTP/1.1\r\nHost: httpbin.org\r\n\r\n"
  }' | python3 -m json.tool
```

---

## 10. Replay History Entry

Re-send a previously captured request.

**Request**

```
POST /replay
Content-Type: application/json
```

```json
{
  "historyId": "entry-uuid"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `historyId` | string | Yes | UUID of the history entry to replay |

**Response** `200 OK`

```json
{
  "success": true,
  "status": 403,
  "responseTime": 89,
  "response": { ... }
}
```

**Response** `404 Not Found` — history entry not found

**Example**

```bash
# Get a history entry ID first
ENTRY_ID=$(curl -s "http://localhost:7331/history?limit=1" | python3 -c "import sys,json; print(json.load(sys.stdin)['entries'][0]['id'])")

# Replay it
curl -s -X POST http://localhost:7331/replay \
  -H "Content-Type: application/json" \
  -d "{\"historyId\": \"$ENTRY_ID\"}"
```

---

## 11. Search History

Full-text search across all history entries (URL, method, host, response body).

**Request**

```
GET /search?q={query}
```

**Query Parameters**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `q` | string | `""` | Search term |
| `limit` | integer | `50` | Maximum results to return |

**Response** `200 OK`

```json
{
  "query": "admin",
  "count": 7,
  "results": [
    {
      "id": "entry-uuid",
      "tabId": "tab-uuid",
      "timestamp": 1718000000000,
      "request": { ... },
      "response": { ... }
    }
  ]
}
```

**Example**

```bash
curl "http://localhost:7331/search?q=token&limit=10"
```

---

## 12. Data Models

### RequestData

```json
{
  "id": "uuid",
  "method": "POST",
  "url": "https://target.com/api/login",
  "host": "target.com",
  "port": 443,
  "https": true,
  "headers": [["Header-Name", "value"]],
  "body": "request body string",
  "rawRequest": "POST /api/login HTTP/1.1\r\nHost: target.com\r\n\r\nbody",
  "timestamp": 1718000000000
}
```

### ResponseData

```json
{
  "id": "uuid",
  "statusCode": 200,
  "statusMessage": "OK",
  "headers": [["Content-Type", "application/json"]],
  "body": "response body string",
  "rawResponse": "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\nbody",
  "responseTime": 152,
  "responseSize": 10240,
  "timestamp": 1718000000000
}
```

### HistoryEntry (summary)

```json
{
  "id": "entry-uuid",
  "tabId": "tab-uuid",
  "timestamp": 1718000000000,
  "method": "GET",
  "url": "https://target.com/api/user/1",
  "status": 200,
  "responseTime": 143,
  "responseSize": 512
}
```

---

## 13. Error Responses

All endpoints return a consistent error body on failure.

```json
{
  "error": "Human-readable error message"
}
```

| HTTP Status | Meaning |
|---|---|
| `400` | Bad request / missing required field |
| `404` | Resource (tab, history entry) not found |
| `405` | Method not allowed |
| `500` | Internal server error or send failure |

---

## Integration Examples

### Python — enumerate IDOR

```python
import requests

BASE = "http://localhost:7331"

def send_request(user_id):
    raw = f"GET /api/user/{user_id} HTTP/1.1\r\nHost: target.com\r\nAuthorization: Bearer TOKEN\r\n\r\n"
    resp = requests.post(f"{BASE}/send", json={
        "host": "target.com", "port": 443, "https": True,
        "method": "GET", "url": f"https://target.com/api/user/{user_id}",
        "rawRequest": raw
    })
    return resp.json()

for uid in range(1, 101):
    result = send_request(uid)
    if result.get("status") == 200:
        print(f"[+] user/{uid} → 200 ({result['responseSize']} bytes)")
```

### Bash — find all 403 responses

```bash
curl -s "http://localhost:7331/history?status=403&limit=100" \
  | python3 -m json.tool
```

### Node.js — list tabs and notes

```js
const res = await fetch("http://localhost:7331/tabs");
const tabs = await res.json();
tabs.forEach(t => console.log(`${t.name} — ${t.historyCount} requests — notes: ${t.notes || "(none)"}`));
```

---

---

## MCP Server

If you prefer to use an AI agent rather than raw HTTP calls, see the **[mcp-server/](../mcp-server/)** directory for the Python MCP server that wraps this API with typed MCP tools. It works out-of-the-box with Claude Desktop.

---

**Contact:** kamalzala07@gmail.com  
**Repository:** https://github.com/zalakamal08/RepeaterX
