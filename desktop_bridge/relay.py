#!/usr/bin/env python3
"""JEV desktop read-only conversation relay.

Default: synthetic fixture only. Optional: read an already-authorized local
WeFlow HTTP API for explicitly allowlisted chat IDs. This program never
reads WeChat processes/databases/keys, never operates the WeChat UI, never
sends chat messages. It binds 127.0.0.1 only and requires a bearer token.
A development phone can connect over an authorized ADB reverse tunnel.
"""
from __future__ import annotations

import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
from pathlib import Path
import secrets
import threading
import time
from urllib.parse import parse_qs, quote, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

MAX_ITEMS = 500
MAX_POLL_ITEMS = 100
MAX_TEXT = 4000
MAX_BODY = 256 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def valid_item(item: dict, account_ref: str) -> bool:
    return (
        isinstance(item, dict)
        and item.get("account_ref") == account_ref
        and all(isinstance(item.get(key), str) and 0 < len(item[key]) <= 200
                for key in ("session_id", "message_id"))
        and isinstance(item.get("display_name"), str)
        and len(item["display_name"]) <= 120
        and item.get("direction") in ("me", "other")
        and isinstance(item.get("text"), str)
        and 0 < len(item["text"]) <= MAX_TEXT
        and type(item.get("time_ms")) is int
        and 0 < item["time_ms"] <= int(time.time() * 1000) + 120000
    )


class RelayStore:
    def __init__(self, account_ref: str, max_items: int = MAX_ITEMS):
        if not account_ref or len(account_ref) > 200:
            raise ValueError("invalid account ref")
        self.account_ref = account_ref
        self.generation = secrets.token_hex(8)
        self._entries: deque[tuple[int, dict]] = deque(maxlen=max_items)
        self._next = 1
        self._lock = threading.Lock()

    def append(self, items: list[dict]) -> int:
        if not isinstance(items, list) or len(items) > MAX_POLL_ITEMS:
            return 0
        if any(not valid_item(it, self.account_ref) for it in items):
            return 0
        with self._lock:
            for item in items:
                self._entries.append((self._next, dict(item)))
                self._next += 1
        return len(items)

    def poll(self, cursor: int, generation: str, limit: int = MAX_POLL_ITEMS) -> dict:
        if cursor < 0 or limit < 1 or limit > MAX_POLL_ITEMS:
            raise ValueError("invalid cursor or limit")
        with self._lock:
            if generation != self.generation:
                cursor = 0
            earliest = self._entries[0][0] if self._entries else self._next
            gap = cursor > 0 and cursor < earliest - 1
            available = [(seq, value) for seq, value in self._entries if seq > cursor]
            selected = available[:limit]
            next_cursor = selected[-1][0] if selected else max(cursor, self._next - 1)
            return {
                "version": 1,
                "generation": self.generation,
                "account_ref": self.account_ref,
                "cursor": next_cursor,
                "gap": gap,
                "has_more": len(available) > len(selected),
                "items": [dict(value) for _, value in selected],
            }


class WeFlowReader:
    """Read-only polling of only explicitly allowed local conversations."""

    def __init__(self, url: str, token: str, account_ref: str, chat_ids: list[str]):
        parsed = urlsplit(url)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost")
                or parsed.username or parsed.password):
            raise ValueError("WeFlow source must be local loopback HTTP")
        if not chat_ids or len(chat_ids) > 10 or any(not c or len(c) > 200 for c in chat_ids):
            raise ValueError("explicit allowlisted conversation IDs required")
        self.base_url = url.rstrip("/")
        self.token = token
        self.account_ref = account_ref
        self.chat_ids = chat_ids
        self._seen: dict[str, set[str]] = {chat: set() for chat in chat_ids}
        self._opener = build_opener(NoRedirect)

    def _get(self, path: str) -> dict:
        req = Request(self.base_url + path, headers={"Authorization": "Bearer " + self.token})
        with self._opener.open(req, timeout=4) as response:
            if response.status != 200:
                raise ValueError("WeFlow read failed")
            data = response.read(MAX_BODY + 1)
        if len(data) > MAX_BODY:
            raise ValueError("upstream response too large")
        result = json.loads(data)
        if not isinstance(result, dict) or result.get("success") is not True:
            raise ValueError("invalid WeFlow response")
        return result

    def poll_once(self) -> list[dict]:
        all_items = []
        for chat in self.chat_ids:
            data = self._get("/api/v1/messages?talker=" + quote(chat, safe="") + "&limit=40")
            messages = data.get("messages", [])
            if not isinstance(messages, list):
                continue
            for item in messages:
                if not isinstance(item, dict) or item.get("localType") != 1:
                    continue
                msg_id = str(item.get("serverId") or item.get("localId") or "")
                content = item.get("content")
                create_time = item.get("createTime")
                if not msg_id or not isinstance(content, str) or not content.strip() or type(create_time) is not int:
                    continue
                if msg_id in self._seen[chat]:
                    continue
                self._seen[chat].add(msg_id)
                record = {
                    "account_ref": self.account_ref,
                    "session_id": chat,
                    "message_id": msg_id,
                    "display_name": chat,
                    "direction": "me" if item.get("isSend") in (1, True) else "other",
                    "text": content[:MAX_TEXT],
                    "time_ms": create_time * 1000,
                }
                if valid_item(record, self.account_ref):
                    all_items.append(record)
            if len(self._seen[chat]) > 4000:
                self._seen[chat].clear()
        return sorted(all_items, key=lambda it: (it["time_ms"], it["message_id"]))[:MAX_POLL_ITEMS]


def make_handler(store: RelayStore, token: str):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            return  # no raw paths, tokens, names or message content in logs

        def do_GET(self):
            if self.path == "/health":
                return self._send(200, {"status": "ok", "mode": "read-only", "version": 1})
            auth = self.headers.get("Authorization", "")
            if not hmac.compare_digest(auth, "Bearer " + token):
                return self._send(401, {"error": "unauthorized"})
            parsed = urlsplit(self.path)
            if parsed.path != "/v1/poll":
                return self._send(404, {"error": "not_found"})
            try:
                qs = parse_qs(parsed.query)
                cursor = int(qs.get("cursor", ["0"])[0])
                generation = qs.get("generation", [""])[0]
                limit = int(qs.get("limit", ["100"])[0])
                return self._send(200, store.poll(cursor, generation, limit))
            except (ValueError, IndexError):
                return self._send(400, {"error": "invalid_cursor"})

        def _send(self, status: int, result: dict):
            raw = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)
    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description="JEV read-only desktop event relay")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--account-ref", required=True)
    parser.add_argument("--token-env", default="JEV_RELAY_TOKEN")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--weflow-url")
    parser.add_argument("--weflow-token-env", default="WEFLOW_API_TOKEN")
    parser.add_argument("--allow-real-chats", action="store_true")
    parser.add_argument("--chat-id", action="append", default=[])
    args = parser.parse_args(argv)
    if args.port < 1 or args.port > 65535:
        parser.error("invalid port")
    token = os.environ.get(args.token_env, "")
    if len(token) < 32:
        parser.error("set a random >=32-character relay token via the environment")
    if bool(args.fixture) == bool(args.weflow_url):
        parser.error("select exactly one of --fixture or --weflow-url")
    if args.weflow_url and not args.allow_real_chats:
        parser.error("real chats require explicit --allow-real-chats")
    store = RelayStore(args.account_ref)
    source = None
    if args.fixture:
        payload = json.loads(args.fixture.read_text(encoding="utf-8"))
        count = store.append(payload)
        if count != len(payload):
            parser.error("fixture contains invalid or wrong-account data")
    else:
        source = WeFlowReader(args.weflow_url, os.environ.get(args.weflow_token_env, ""),
                              args.account_ref, args.chat_id)

    if source:
        def work():
            while True:
                try:
                    events = source.poll_once()
                    store.append(events)
                except Exception:
                    pass  # no secret or message logging
                time.sleep(5)
        threading.Thread(target=work, daemon=True).start()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(store, token))
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
