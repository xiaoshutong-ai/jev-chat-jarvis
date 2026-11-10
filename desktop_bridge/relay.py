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
            gap = cursor < earliest - 1
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
    """Incremental read-only ChatLab Pull adapter; never reads WeChat directly.

    Only user-allowlisted local sessions are queried. Cursor state is volatile,
    and is advanced *only after* the relay queue accepts the entire batch.
    A source restart deliberately needs renewed history/consent for backfill.
    """

    def __init__(
        self, url: str, token: str, account_ref: str, chat_ids: list[str],
        self_sender_id: str, lookback_seconds: int = 60
    ):
        parsed = urlsplit(url)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost")
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in ("", "/") or not parsed.port):
            raise ValueError("WeFlow source must be a local loopback origin")
        if not token:
            raise ValueError("WeFlow token must be set for real source")
        if (not chat_ids or len(chat_ids) > 10 or len(set(chat_ids)) != len(chat_ids)
                or any(not isinstance(c, str) or not c.strip() or len(c) > 200 for c in chat_ids)):
            raise ValueError("explicit unique allowlisted conversation IDs required")
        if (not self_sender_id or len(self_sender_id) > 200
                or not 1 <= lookback_seconds <= 3600):
            raise ValueError("explicit own sender ID and bounded lookback required")
        self.base_url = url.rstrip("/")
        self.token = token
        self.account_ref = account_ref
        self.chat_ids = tuple(chat_ids)
        self.self_sender_id = self_sender_id
        start_since = int(time.time()) - lookback_seconds
        self._cursors: dict[str, tuple[int, int]] = {
            chat: (start_since, 0) for chat in chat_ids
        }
        self._next_chat = 0
        self._pending: tuple[list[dict], dict[str, tuple[int, int]], int] | None = None
        self._opener = build_opener(NoRedirect)

    def _get(self, path: str) -> dict:
        req = Request(self.base_url + path, headers={"Authorization": "Bearer " + self.token})
        with self._opener.open(req, timeout=4) as response:
            if response.status != 200:
                raise ValueError("WeFlow read failed")
            raw = response.read(MAX_BODY + 1)
        if len(raw) > MAX_BODY:
            raise ValueError("WeFlow response too large")
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ValueError("invalid WeFlow response")
        return result

    @staticmethod
    def _parse_cursor(data: dict, since: int, offset: int) -> tuple[int, int]:
        sync = data.get("sync")
        if not isinstance(sync, dict) or type(sync.get("hasMore")) is not bool:
            raise ValueError("missing ChatLab sync metadata")
        next_since, next_offset = sync.get("nextSince"), sync.get("nextOffset")
        if (type(next_since) is not int or type(next_offset) is not int
                or next_since < since or next_offset < 0):
            raise ValueError("invalid ChatLab next cursor")
        if sync["hasMore"] and next_since == since and next_offset <= offset:
            raise ValueError("non-advancing ChatLab pagination")
        return next_since, next_offset

    def _convert_page(self, data: dict, chat: str, since: int, offset: int,
                      page_limit: int) -> tuple[list[dict], tuple[int, int]]:
        meta, messages = data.get("meta"), data.get("messages")
        if not isinstance(meta, dict) or not isinstance(messages, list):
            raise ValueError("missing ChatLab conversation or messages")
        if len(messages) > page_limit:
            raise ValueError("WeFlow exceeded requested page limit")
        name = meta.get("name") or chat
        if not isinstance(name, str) or len(name) > 120:
            raise ValueError("invalid conversation display name")
        converted = []
        for item in messages:
            if not isinstance(item, dict):
                raise ValueError("invalid ChatLab message")
            if item.get("type") != 0:
                continue  # Only plain text is currently in the JEV contract.
            msg_id = item.get("platformMessageId")
            sender = item.get("sender")
            content = item.get("content")
            timestamp = item.get("timestamp")
            if (not isinstance(msg_id, str) or not msg_id.strip()
                    or len(msg_id) > 200 or not isinstance(sender, str) or not sender
                    or not isinstance(content, str) or not content.strip()
                    or type(timestamp) is not int or timestamp <= 0):
                raise ValueError("plain text source lacks stable message metadata")
            text = content
            if len(text) > MAX_TEXT:
                text = text[:MAX_TEXT - 6] + "[已截断]"
            record = {
                "account_ref": self.account_ref,
                "session_id": chat,
                "message_id": msg_id,
                "display_name": name,
                "direction": "me" if sender == self.self_sender_id else "other",
                "text": text,
                "time_ms": timestamp * 1000,
            }
            if not valid_item(record, self.account_ref):
                raise ValueError("invalid source record")
            converted.append(record)
        return converted, self._parse_cursor(data, since, offset)

    def poll_once(self) -> list[dict]:
        if self._pending is not None:
            return self._pending[0]  # Exact replay until queue ACK.
        batch: list[dict] = []
        updates: dict[str, tuple[int, int]] = {}
        # Keep every HTTP relay response comfortably below the Android 256KiB
        # response limit, including multiple chats with long text messages.
        budget = 160_000
        count = len(self.chat_ids)
        page_limit = min(25, max(5, MAX_POLL_ITEMS // count))
        next_chat = self._next_chat
        for step in range(count):
            index = (self._next_chat + step) % count
            chat = self.chat_ids[index]
            since, offset = self._cursors[chat]
            path = (
                "/api/v1/sessions/" + quote(chat, safe="") + "/messages"
                + f"?since={since}&offset={offset}&limit={page_limit}"
            )
            data = self._get(path)
            records, next_cursor = self._convert_page(data, chat, since, offset, page_limit)
            incoming_bytes = sum(len(json.dumps(record, ensure_ascii=False).encode("utf-8")) + 4
                                 for record in records)
            if len(batch) + len(records) > MAX_POLL_ITEMS or incoming_bytes > budget:
                if not updates:
                    raise ValueError("single ChatLab page exceeds relay budget")
                break  # Keep this chat's cursor untouched; poll it next.
            batch.extend(records)
            updates[chat] = next_cursor
            budget -= incoming_bytes
            next_chat = (index + 1) % count
        self._pending = (batch, updates, next_chat)
        return batch

    def acknowledge(self, records: list[dict]) -> None:
        if self._pending is None or records is not self._pending[0]:
            raise ValueError("only the pending ChatLab page may be acknowledged")
        _, updates, next_chat = self._pending
        self._cursors.update(updates)
        self._next_chat = next_chat
        self._pending = None


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
    parser.add_argument("--self-sender-id", help="your own WeFlow sender wxid (explicit)")
    parser.add_argument("--lookback-seconds", type=int, default=60,
                        help="bounded opt-in lookback on source startup (1..3600)")
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
        source = WeFlowReader(
            args.weflow_url, os.environ.get(args.weflow_token_env, ""),
            args.account_ref, args.chat_id, args.self_sender_id,
            args.lookback_seconds
        )

    if source:
        def work():
            while True:
                try:
                    events = source.poll_once()
                    # Never advance the WeFlow cursor before the relay queue
                    # accepted this exact batch, including empty/media-only pages.
                    inserted = store.append(events)
                    if inserted != len(events):
                        raise ValueError("relay did not accept the ChatLab page")
                    source.acknowledge(events)
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
