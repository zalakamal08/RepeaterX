# RepeaterX MCP Server

An MCP (Model Context Protocol) server that lets AI agents control Burp Suite's RepeaterX extension directly — send requests, read responses, manage tabs, and search history.

## Prerequisites

- Python 3.10+
- Burp Suite running with the **RepeaterX** extension loaded
- RepeaterX API server running (default: `http://0.0.0.0:7331`)

## Install

```bash
cd mcp-server
pip install -r requirements.txt
```

## Configure in Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or the equivalent path on your OS:

```json
{
  "mcpServers": {
    "repeaterx": {
      "command": "python",
      "args": ["/absolute/path/to/RepeaterX/mcp-server/server.py"],
      "env": {
        "REPEATERX_HOST": "127.0.0.1",
        "REPEATERX_PORT": "7331"
      }
    }
  }
}
```

Restart Claude Desktop — the RepeaterX tools will appear.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `REPEATERX_HOST` | `127.0.0.1` | RepeaterX API host |
| `REPEATERX_PORT` | `7331` | RepeaterX API port |

## Available Tools

| Tool | Description |
|---|---|
| `repeaterx_status` | Check API is running, get stats |
| `repeaterx_list_tabs` | List all open tabs |
| `repeaterx_create_tab` | Open a new tab (optionally pre-load a request) |
| `repeaterx_delete_tab` | Close a tab |
| `repeaterx_duplicate_tab` | Duplicate a tab |
| `repeaterx_send_request` | Send an HTTP request through Burp |
| `repeaterx_get_history` | List history with filters (status, method, search, tab) |
| `repeaterx_get_request` | Get full request for a history entry |
| `repeaterx_get_response` | Get full response for a history entry |
| `repeaterx_replay_request` | Replay a history entry |
| `repeaterx_search` | Full-text search across all history |

## Example Prompts

Once configured, you can say to Claude:

> "Open a new RepeaterX tab called 'IDOR Test' with this request: GET /api/user/1 HTTP/1.1 Host: target.com"

> "Send a POST to target.com/api/login with these credentials and tell me the response."

> "Search RepeaterX history for any 403 responses to /admin endpoints."

> "Replay the last 5 requests and compare response sizes."

> "List all my RepeaterX tabs and their last status codes."
