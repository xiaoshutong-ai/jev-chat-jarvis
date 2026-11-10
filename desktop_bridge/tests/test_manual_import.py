"""Focused tests: explicit clipboard import does not read WeChat or files."""
import pathlib
import http.client
import json
import os
import socket
import subprocess
import sys
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from relay import RelayStore, valid_item
from manual_import import (
    MANUAL_SESSION_PREFIX,
    ManualImportController,
    manual_selected_event,
    normalize_manual_label,
    valid_manual_account,
)


class ManualImportTest(unittest.TestCase):
    def test_source_identity_remains_explicitly_unverified(self):
        now = int(time.time() * 1000)
        event = manual_selected_event(
            " Hello from selected text \n", "manual-pilot", "同学甲", "other", now
        )
        self.assertEqual("Hello from selected text", event["text"])
        self.assertEqual("other", event["direction"])
        self.assertTrue(event["session_id"].startswith(MANUAL_SESSION_PREFIX))
        self.assertIn("手动选择", event["display_name"])
        self.assertIn("联系人未核验", event["display_name"])
        self.assertTrue(event["message_id"].startswith("manual-"))
        self.assertTrue(valid_item(event, "manual-pilot"))
        outgoing = manual_selected_event("我的消息", "manual-pilot", "同学甲", "me", now)
        self.assertEqual("me", outgoing["direction"])
        self.assertEqual(event["session_id"], outgoing["session_id"])
        self.assertNotEqual(event["message_id"], outgoing["message_id"])

    def test_session_id_is_stable_per_account_and_user_label(self):
        event1 = manual_selected_event("one", "manual-a", "老师", "other")
        event2 = manual_selected_event("two", "manual-a", "老师", "other")
        event3 = manual_selected_event("one", "manual-b", "老师", "other")
        event4 = manual_selected_event("one", "manual-a", "学生", "other")
        self.assertEqual(event1["session_id"], event2["session_id"])
        self.assertNotEqual(event1["session_id"], event3["session_id"])
        self.assertNotEqual(event1["session_id"], event4["session_id"])

    def test_invalid_or_spoofed_source_rejected(self):
        for value in ("", "wxid-real-account", "manual-", "manual-中文", "manual space", "manual-a" * 50):
            self.assertFalse(valid_manual_account(value))
            with self.assertRaises(ValueError):
                manual_selected_event("text", value, "会话", "other")
        self.assertTrue(valid_manual_account("fixture-account"))
        self.assertTrue(valid_manual_account("manual-user01"))

    def test_unknown_direction_control_label_and_invalid_text_fail_closed(self):
        for label in ("", "x" * 53, "张三\n李四", "会话\u202e反转", "abc\u2066def"):
            with self.assertRaises(ValueError):
                normalize_manual_label(label)
        for direction in ("", "incoming", "unknown", None):
            with self.assertRaises(ValueError):
                manual_selected_event("text", "manual-pilot", "同学甲", direction)
        for text in ("", "   ", "x" * 4001):
            with self.assertRaises(ValueError):
                manual_selected_event(text, "manual-pilot", "同学甲", "other")

    def test_double_click_suppressed_without_discouraging_explicit_new_messages(self):
        now = int(time.time() * 1000)
        store = RelayStore("fixture-account")
        importer = ManualImportController(store)
        did_add, sid = importer.import_selected("选取消息", "同学甲", "other", now)
        self.assertTrue(did_add)
        duplicate, sid_again = importer.import_selected("选取消息", "同学甲", "other", now + 800)
        self.assertFalse(duplicate)
        self.assertEqual(sid, sid_again)
        self.assertEqual(1, len(store.poll(0, "")["items"]))
        # Same words in a different manually selected conversation or direction
        # do not collapse into the wrong context.
        self.assertTrue(importer.import_selected("选取消息", "同学乙", "other", now + 1200)[0])
        self.assertTrue(importer.import_selected("选取消息", "同学甲", "me", now + 1200)[0])
        self.assertEqual(3, len(store.poll(0, "")["items"]))
        # An explicit re-import after the debounce window is allowed.
        self.assertTrue(importer.import_selected("选取消息", "同学甲", "other",
                                                 now + 30_500)[0])
        self.assertEqual(4, len(store.poll(0, "")["items"]))

    def test_manual_import_keeps_only_volatile_queue_events(self):
        store = RelayStore("manual-pilot")
        importer = ManualImportController(store)
        imported, _ = importer.import_selected(" user explicitly pasted this ", "对方", "other")
        self.assertTrue(imported)
        events = store.poll(0, "")["items"]
        self.assertEqual(1, len(events))
        self.assertEqual("manual-pilot", events[0]["account_ref"])
        self.assertEqual("user explicitly pasted this", events[0]["text"])


class ManualCliIntegrationTest(unittest.TestCase):
    def test_missing_direction_is_rejected_before_stdin_is_read(self):
        relay = pathlib.Path(__file__).resolve().parents[1] / "relay.py"
        result = subprocess.run(
            [sys.executable, str(relay), "--account-ref", "manual-pilot",
             "--manual-stdin", "--manual-session-label", "同学甲"],
            input="SYNTHETIC_OTHER_MESSAGE\n",
            text=True, capture_output=True, timeout=6,
            env={**os.environ, "JEV_RELAY_TOKEN": "z" * 40}
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("requires explicit", result.stderr)
        self.assertNotIn("SYNTHETIC_OTHER_MESSAGE", result.stderr)

    def test_explicit_inbound_stdin_is_authenticated_localhost_only(self):
        relay = pathlib.Path(__file__).resolve().parents[1] / "relay.py"
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        token = "L" * 40
        child = subprocess.Popen(
            [sys.executable, str(relay), "--account-ref", "manual-pilot",
             "--manual-stdin", "--manual-session-label", "合成同学",
             "--manual-direction", "other", "--port", str(port)],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE, env={**os.environ, "JEV_RELAY_TOKEN": token},
        )
        try:
            assert child.stdin is not None
            child.stdin.write("SYNTHETIC_OTHER_MESSAGE\n".encode())
            child.stdin.close()
            result = None
            for _ in range(50):
                if child.poll() is not None:
                    self.fail("relay exited before first poll")
                try:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                    conn.request("GET", "/v1/poll?cursor=0&limit=2",
                                 headers={"Authorization": "Bearer " + token})
                    response = conn.getresponse()
                    result = json.loads(response.read())
                    self.assertEqual(200, response.status)
                    conn.close()
                    break
                except OSError:
                    time.sleep(0.05)
            self.assertIsNotNone(result, "relay never opened loopback port")
            self.assertEqual(1, len(result["items"]))
            event = result["items"][0]
            self.assertEqual("other", event["direction"])
            self.assertTrue(event["session_id"].startswith(MANUAL_SESSION_PREFIX))
            self.assertIn("联系人未核验", event["display_name"])
            self.assertEqual("SYNTHETIC_OTHER_MESSAGE", event["text"])
        finally:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
            if child.stderr:
                child.stderr.close()


if __name__ == "__main__":
    unittest.main()
