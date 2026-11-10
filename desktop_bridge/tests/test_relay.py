"""Strict fixture-only tests. Never contacts WeChat or the optional WeFlow API."""
import http.client
from http.server import ThreadingHTTPServer
import json
import pathlib
import sys
import threading
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from relay import RelayStore, WeFlowReader, make_handler, valid_item


def item(message_id="1", account_ref="fixture-account", session_id="demo-a"):
    return {
        "account_ref": account_ref,
        "session_id": session_id,
        "message_id": message_id,
        "display_name": "Demo",
        "direction": "other",
        "text": "Hello",
        "time_ms": int(time.time() * 1000),
    }


class RelayStoreTest(unittest.TestCase):
    def test_rejects_cross_account_batch_atomically(self):
        store = RelayStore("fixture-account")
        self.assertEqual(0, store.append([item(), item("2", account_ref="other")]))
        self.assertEqual([], store.poll(0, "")["items"])

    def test_accepts_and_paginates(self):
        store = RelayStore("fixture-account")
        self.assertEqual(3, store.append([item("1"), item("2"), item("3")]))
        first = store.poll(0, store.generation, 2)
        self.assertEqual(["1", "2"], [x["message_id"] for x in first["items"]])
        self.assertTrue(first["has_more"])
        second = store.poll(first["cursor"], store.generation, 2)
        self.assertEqual(["3"], [x["message_id"] for x in second["items"]])
        self.assertFalse(second["has_more"])

    def test_generation_reset_and_gap(self):
        store = RelayStore("fixture-account", max_items=2)
        store.append([item("1"), item("2"), item("3"), item("4")])
        self.assertEqual(["3", "4"], [x["message_id"] for x in store.poll(0, "old")["items"]])
        self.assertTrue(store.poll(1, store.generation)["gap"])

    def test_rejects_forged_or_future_messages(self):
        store = RelayStore("fixture-account")
        self.assertEqual(0, store.append([dict(item(), time_ms=int(time.time() * 1000) + 300000)]))
        self.assertEqual(0, store.append([dict(item(), text="")]))
        self.assertEqual(0, store.append([dict(item(), direction="unknown")]))
        self.assertEqual(0, store.append([dict(item(), account_ref="spoofed")]))
        self.assertFalse(valid_item(dict(item(), time_ms=True), "fixture-account"))

    def test_missing_generation_does_not_accept_outside_cursor(self):
        store = RelayStore("fixture-account")
        store.append([item("1")])
        self.assertEqual(["1"], [x["message_id"] for x in store.poll(999, "mismatch")["items"]])
        with self.assertRaises(ValueError):
            store.poll(-1, store.generation)

    def test_weflow_chatlab_paging_preserves_all_four_conversations(self):
        chats = ["chat-" + str(i) for i in range(4)]
        reader = WeFlowReader("http://127.0.0.1:5031", "local-token",
                              "fixture-account", chats, "self-wxid")
        now = int(time.time())
        requests = []
        def fake_get(path):
            from urllib.parse import parse_qs, unquote, urlsplit
            url = urlsplit(path)
            chat = unquote(url.path.split("/sessions/", 1)[1].split("/messages", 1)[0])
            args = parse_qs(url.query)
            since = int(args["since"][0])
            offset = int(args["offset"][0])
            limit = int(args["limit"][0])
            requests.append((chat, since, offset, limit))
            remaining = 40 - offset
            amount = min(limit, remaining)
            return {
                "meta": {"name": chat},
                "messages": [{
                    "platformMessageId": chat + "-" + str(i),
                    "type": 0, "sender": "other-wxid",
                    "content": "synthetic text " + str(i),
                    "timestamp": now,
                } for i in range(offset, offset + amount)],
                "sync": {
                    "hasMore": offset + amount < 40,
                    "nextSince": since,
                    "nextOffset": offset + amount,
                    "watermark": now,
                },
            }
        reader._get = fake_get
        first = reader.poll_once()
        self.assertEqual(100, len(first))
        # Before queue ACK the same page is repeated and no upstream read runs.
        self.assertIs(first, reader.poll_once())
        self.assertEqual(4, len(requests))
        reader.acknowledge(first)
        second = reader.poll_once()
        self.assertEqual(60, len(second))
        reader.acknowledge(second)
        self.assertEqual(160, len({r["message_id"] for r in first + second}))
        self.assertTrue(all(r["direction"] == "other" for r in first + second))
        self.assertTrue(all(r["account_ref"] == "fixture-account" for r in first + second))

    def test_weflow_ack_cannot_skip_unenqueued_messages(self):
        reader = WeFlowReader("http://localhost:5031", "local-token",
                              "fixture-account", ["demo-a"], "self-wxid")
        now = int(time.time())
        calls = []
        def fake_get(path):
            calls.append(path)
            since = reader._cursors["demo-a"][0]
            return {
                "meta": {"name": "Synthetic"},
                "messages": [{
                    "platformMessageId": "msg-1", "type": 0,
                    "sender": "self-wxid", "content": "my own text",
                    "timestamp": now,
                }],
                "sync": {"hasMore": False, "nextSince": since,
                         "nextOffset": 1, "watermark": now},
            }
        reader._get = fake_get
        page = reader.poll_once()
        self.assertEqual("me", page[0]["direction"])
        self.assertIs(page, reader.poll_once())
        self.assertEqual(1, len(calls))
        with self.assertRaises(ValueError):
            reader.acknowledge([dict(page[0])])
        self.assertEqual(1, len(calls))
        reader.acknowledge(page)
        reader.poll_once()
        self.assertEqual(2, len(calls))

    def test_weflow_bad_sync_or_missing_message_id_fails_closed(self):
        reader = WeFlowReader("http://localhost:5031", "local-token",
                              "fixture-account", ["demo-a"], "self-wxid")
        now = int(time.time())
        def fake_get(path):
            return {
                "meta": {"name": "Synthetic"},
                "messages": [{"type": 0, "sender": "other",
                              "timestamp": now, "content": "hello"}],
                "sync": {"hasMore": False, "nextSince": now,
                         "nextOffset": 0, "watermark": now}
            }
        reader._get = fake_get
        with self.assertRaises(ValueError):
            reader.poll_once()
        self.assertIsNone(reader._pending)

        def invalid_cursor(path):
            since = reader._cursors["demo-a"][0]
            return {"meta": {"name": "Synthetic"}, "messages": [],
                    "sync": {"hasMore": True, "nextSince": since,
                             "nextOffset": 0, "watermark": now}}
        reader._get = invalid_cursor
        with self.assertRaises(ValueError):
            reader.poll_once()

    def test_weflow_input_requires_loopback_allowlist_and_own_sender_id(self):
        for base in ("https://example.com", "http://192.168.1.2:5031",
                     "http://user:pass@127.0.0.1:5031",
                     "http://127.0.0.1:5031/other?token=bad"):
            with self.assertRaises(ValueError):
                WeFlowReader(base, "token", "fixture-account", ["demo-a"], "self")
        for chats, self_id in [([], "self"), (["a", "a"], "self"), (["a"], "")]:
            with self.assertRaises(ValueError):
                WeFlowReader("http://127.0.0.1:5031", "token",
                             "fixture-account", chats, self_id)

    def test_late_client_must_receive_gap_when_queue_truncated(self):
        store = RelayStore("fixture-account", max_items=2)
        self.assertEqual(4, store.append([item(str(i)) for i in range(4)]))
        response = store.poll(0, "")
        self.assertTrue(response["gap"])
        self.assertEqual(["2", "3"], [r["message_id"] for r in response["items"]])


class RelayHttpTest(unittest.TestCase):
    def test_unauthorized_rejected_and_valid_cursor_returned(self):
        store = RelayStore("fixture-account")
        store.append([item("1")])
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(store, "x" * 40))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            conn.request("GET", "/v1/poll")
            res = conn.getresponse()
            self.assertEqual(401, res.status)
            res.read()
            conn.request("GET", "/v1/poll?limit=1", headers={"Authorization": "Bearer " + "x" * 40})
            res = conn.getresponse()
            data = json.loads(res.read())
            self.assertEqual(200, res.status)
            self.assertEqual("fixture-account", data["account_ref"])
            self.assertEqual(["1"], [i["message_id"] for i in data["items"]])
            self.assertEqual("no-store", res.getheader("Cache-Control"))
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
