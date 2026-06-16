#!/usr/bin/env python3
"""
RepeaterX MCP Server

Bridges the Model Context Protocol to the RepeaterX Burp Suite extension REST API.
AI agents (Claude, etc.) can use this server to control Burp Repeater directly.

Usage:
    python server.py [--host 0.0.0.0] [--port 7331]

Configure in Claude Desktop (claude_desktop_config.json):
    {
      "mcpServers": {
        "repeaterx": {
          "command": "python",
          "args": ["/path/to/mcp-server/server.py"],
          "env": {
            "REPEATERX_HOST": "127.0.0.1",
            "REPEATERX_PORT": "7331"
          }
        }
      }
    }
"""

import asyncio
import json
import os
import sys
import argparse
from typing import Any

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp import types

# ── Config ────────────────────────────────────────────────────────────────────

def get_base_url() -> str:
    host = os.environ.get("REPEATERX_HOST", "127.0.0.1")
    port = os.environ.get("REPEATERX_PORT", "7331")
    return f"http://{host}:{port}"

# ── HTTP client ───────────────────────────────────────────────────────────────

async def api_get(path: str, params: dict | None = None) -> Any:
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(get_base_url() + path, params=params)
        resp.raise_for_status()
        return resp.json()

async def api_post(path: str, body: dict) -> Any:
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            get_base_url() + path,
            json=body,
            headers={"Content-Type": "application/json"},
        )
        resp.raise_for_status()
        return resp.json()

# ── MCP Server ────────────────────────────────────────────────────────────────

server = Server("repeaterx")

@server.list_tools()
async def list_tools() -> list[types.Tool]:
    return [
        types.Tool(
            name="repeaterx_status",
            description="Check whether the RepeaterX API server is running and get basic stats (history size, host, port).",
            inputSchema={"type": "object", "properties": {}, "required": []},
        ),
        types.Tool(
            name="repeaterx_list_tabs",
            description="List all open tabs in RepeaterX, including their names, history counts, notes, and last response status.",
            inputSchema={"type": "object", "properties": {}, "required": []},
        ),
        types.Tool(
            name="repeaterx_create_tab",
            description="Create a new RepeaterX tab, optionally pre-loaded with a raw HTTP request.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Tab label (e.g. 'IDOR Test'). Defaults to 'New Tab'.",
                    },
                    "raw_request": {
                        "type": "string",
                        "description": "Full raw HTTP request to pre-load (e.g. 'GET /api/user HTTP/1.1\\r\\nHost: target.com\\r\\n\\r\\n'). Optional.",
                    },
                },
                "required": [],
            },
        ),
        types.Tool(
            name="repeaterx_delete_tab",
            description="Close and remove a RepeaterX tab by its ID.",
            inputSchema={
                "type": "object",
                "properties": {
                    "tab_id": {
                        "type": "string",
                        "description": "UUID of the tab to delete.",
                    }
                },
                "required": ["tab_id"],
            },
        ),
        types.Tool(
            name="repeaterx_duplicate_tab",
            description="Duplicate an existing tab (copies request, notes, and metadata — not history).",
            inputSchema={
                "type": "object",
                "properties": {
                    "tab_id": {
                        "type": "string",
                        "description": "UUID of the tab to duplicate.",
                    }
                },
                "required": ["tab_id"],
            },
        ),
        types.Tool(
            name="repeaterx_send_request",
            description=(
                "Send an HTTP request through Burp's engine (respects Burp proxy, TLS settings, "
                "certificate trust, and upstream proxies). Returns status code, response time, size, "
                "and the full response body."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "host": {
                        "type": "string",
                        "description": "Target hostname (e.g. 'api.example.com').",
                    },
                    "port": {
                        "type": "integer",
                        "description": "Target port. Defaults to 443.",
                        "default": 443,
                    },
                    "https": {
                        "type": "boolean",
                        "description": "Whether to use TLS. Defaults to true.",
                        "default": True,
                    },
                    "method": {
                        "type": "string",
                        "description": "HTTP method (GET, POST, PUT, PATCH, DELETE, etc.).",
                        "default": "GET",
                    },
                    "raw_request": {
                        "type": "string",
                        "description": (
                            "Full raw HTTP request including request line, headers, blank line, and body. "
                            "Use \\r\\n as line separator. Example: "
                            "'GET /api/user/1 HTTP/1.1\\r\\nHost: target.com\\r\\nAuthorization: Bearer TOKEN\\r\\n\\r\\n'"
                        ),
                    },
                },
                "required": ["host", "raw_request"],
            },
        ),
        types.Tool(
            name="repeaterx_get_history",
            description=(
                "Retrieve request/response history. Supports filtering by search query, "
                "HTTP status code, HTTP method, or tab ID. Supports pagination."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Full-text search term (matches URL, method, host, response body).",
                    },
                    "status": {
                        "type": "integer",
                        "description": "Filter by HTTP response status code (e.g. 403).",
                    },
                    "method": {
                        "type": "string",
                        "description": "Filter by HTTP method (e.g. 'POST').",
                    },
                    "tab_id": {
                        "type": "string",
                        "description": "Filter by tab UUID.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum entries to return. Default 50.",
                        "default": 50,
                    },
                    "offset": {
                        "type": "integer",
                        "description": "Pagination offset. Default 0.",
                        "default": 0,
                    },
                },
                "required": [],
            },
        ),
        types.Tool(
            name="repeaterx_get_request",
            description="Get the full request details (headers, body, raw bytes) for a history entry by its ID.",
            inputSchema={
                "type": "object",
                "properties": {
                    "entry_id": {
                        "type": "string",
                        "description": "UUID of the history entry.",
                    }
                },
                "required": ["entry_id"],
            },
        ),
        types.Tool(
            name="repeaterx_get_response",
            description="Get the full response details (status, headers, body, raw bytes) for a history entry by its ID.",
            inputSchema={
                "type": "object",
                "properties": {
                    "entry_id": {
                        "type": "string",
                        "description": "UUID of the history entry.",
                    }
                },
                "required": ["entry_id"],
            },
        ),
        types.Tool(
            name="repeaterx_replay_request",
            description=(
                "Replay a previously captured request from history. "
                "Useful for re-testing with different Burp session context."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "entry_id": {
                        "type": "string",
                        "description": "UUID of the history entry to replay.",
                    }
                },
                "required": ["entry_id"],
            },
        ),
        types.Tool(
            name="repeaterx_search",
            description="Full-text search across all history entries (URL, method, host, response body).",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Search term.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum results. Default 20.",
                        "default": 20,
                    },
                },
                "required": ["query"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent]:
    try:
        result = await _dispatch(name, arguments)
        return [types.TextContent(type="text", text=json.dumps(result, indent=2))]
    except httpx.ConnectError:
        return [types.TextContent(
            type="text",
            text=json.dumps({
                "error": "Cannot connect to RepeaterX API",
                "hint": f"Is Burp Suite running with RepeaterX loaded? Check that the extension is active and the API server is on {get_base_url()}",
            })
        )]
    except httpx.HTTPStatusError as e:
        return [types.TextContent(
            type="text",
            text=json.dumps({"error": f"API returned {e.response.status_code}", "detail": e.response.text})
        )]
    except Exception as e:
        return [types.TextContent(
            type="text",
            text=json.dumps({"error": str(e)})
        )]


async def _dispatch(name: str, args: dict) -> Any:
    if name == "repeaterx_status":
        return await api_get("/status")

    elif name == "repeaterx_list_tabs":
        return await api_get("/tabs")

    elif name == "repeaterx_create_tab":
        return await api_post("/tabs/create", {
            "name": args.get("name", "New Tab"),
            "rawRequest": args.get("raw_request", ""),
        })

    elif name == "repeaterx_delete_tab":
        return await api_post("/tabs/delete", {"id": args["tab_id"]})

    elif name == "repeaterx_duplicate_tab":
        return await api_post("/tabs/duplicate", {"id": args["tab_id"]})

    elif name == "repeaterx_send_request":
        return await api_post("/send", {
            "host": args["host"],
            "port": args.get("port", 443),
            "https": args.get("https", True),
            "method": args.get("method", "GET"),
            "rawRequest": args["raw_request"],
        })

    elif name == "repeaterx_get_history":
        params: dict[str, Any] = {
            "limit": args.get("limit", 50),
            "offset": args.get("offset", 0),
        }
        if "query" in args:   params["q"] = args["query"]
        if "status" in args:  params["status"] = args["status"]
        if "method" in args:  params["method"] = args["method"]
        if "tab_id" in args:  params["tab"] = args["tab_id"]
        return await api_get("/history", params)

    elif name == "repeaterx_get_request":
        return await api_get(f"/request/{args['entry_id']}")

    elif name == "repeaterx_get_response":
        return await api_get(f"/response/{args['entry_id']}")

    elif name == "repeaterx_replay_request":
        return await api_post("/replay", {"historyId": args["entry_id"]})

    elif name == "repeaterx_search":
        return await api_get("/search", {
            "q": args["query"],
            "limit": args.get("limit", 20),
        })

    else:
        raise ValueError(f"Unknown tool: {name}")


# ── Entry point ───────────────────────────────────────────────────────────────

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
