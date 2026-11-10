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
        store.append([item("1"), item("2"), item("3")])
        self.assertEqual(["2", "3"], [x["message_id"] for x in store.poll(0, "old")["items"]])
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

    def test_weflow_input_requires_loopback_and_allowlist(self):
        for base in ("https://example.com", "http://192.168.1.2:5031", "http://user:pass@127.0.0.1:5031"):
            with self.assertRaises(ValueError):
                WeFlowReader(base, "token", "fixture-account", ["demo-a"])
        with self.assertRaises(ValueError):
            WeFlowReader("http://127.0.0.1:5031", "token", "fixture-account", [])


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
