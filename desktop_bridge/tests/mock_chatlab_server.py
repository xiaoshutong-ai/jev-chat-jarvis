#!/usr/bin/env python3
"""Synthetic ChatLab Pull server; test fixtures only, never touches WeChat.

Runs on 127.0.0.1:5031 (or specified local port). All names/messages are
fictional. Used to test the optional WeFlowReader adapter end-to-end without
installing WeFlow, extracting keys or reading personal conversations.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import hmac
import json
import os
import time
from urllib.parse import unquote, urlsplit, parse_qs


def make_handler(token: str):
    started = int(time.time())
    messages = {
        "sim-alpha": ("Synthetic Alpha", "sim-id-alpha", "synthetic hello alpha"),
        "sim-beta": ("Synthetic Beta", "sim-id-beta", "synthetic hello beta"),
    }

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                self.send_error(401)
                return
            split = urlsplit(self.path)
            if not split.path.startswith("/api/v1/sessions/") or not split.path.endswith("/messages"):
                self.send_error(404)
                return
            chat = unquote(split.path[len("/api/v1/sessions/"):-len("/messages")])
            if chat not in messages:
                self.send_error(404)
                return
            qs = parse_qs(split.query)
            try:
                since = int(qs["since"][0])
                offset = int(qs["offset"][0])
                limit = int(qs["limit"][0])
                end = int(qs["end"][0]) if "end" in qs else started
            except (ValueError, IndexError, KeyError):
                self.send_error(400)
                return
            if since < 0 or offset < 0 or not 1 <= limit <= 100 or end < since:
                self.send_error(400)
                return
            name, msg_id, msg_text = messages[chat]
            available = (
                [{
                    "platformMessageId": msg_id,
                    "sender": "synthetic-friend",
                    "timestamp": started,
                    "type": 0,
                    "content": msg_text,
                }]
                if since <= started <= end else []
            )
            page = available[offset:offset + limit]
            payload = {
                "chatlab": {"version": "0.0.2", "generator": "JEV_SYNTHETIC_ONLY"},
                "meta": {"name": name, "type": "private"},
                "messages": page,
                "sync": {
                    "hasMore": offset + len(page) < len(available),
                    "nextSince": since,
                    "nextOffset": offset + len(page),
                    "watermark": started,
                },
            }
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
    return Handler


def main():
    parser = argparse.ArgumentParser(description="Synthetic-only WeFlow ChatLab local test source")
    parser.add_argument("--port", type=int, default=5031)
    parser.add_argument("--token-env", default="WEFLOW_API_TOKEN")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("invalid loopback port")
    token = os.environ.get(args.token_env, "")
    if len(token) < 32:
        parser.error("synthetic source requires a random 32+ character token")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(token))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
