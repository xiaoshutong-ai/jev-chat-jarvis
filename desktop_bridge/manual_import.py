"""Explicit one-off Windows clipboard → JEV relay import.

This module never reads WeChat or the clipboard itself. The UI (when launched)
reads the clipboard only on an explicit button press and displays its full
text for user review before a separate import confirmation. No files or logs
contain the selected text; in-memory relay records are scoped to a synthetic,
user-declared session rather than an authenticated WeChat contact ID.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time

MANUAL_ACCOUNT_PREFIX = "manual-"
TEST_ACCOUNT_REF = "fixture-account"
MANUAL_SESSION_PREFIX = "manual-user-"
REPLAY_WINDOW_MS = 30_000
MAX_MANUAL_LABEL = 52
MAX_MANUAL_TEXT = 4_000


def valid_manual_account(account_ref: str) -> bool:
    return (
        isinstance(account_ref, str)
        and (account_ref == TEST_ACCOUNT_REF
             or (account_ref.startswith(MANUAL_ACCOUNT_PREFIX)
                 and 8 <= len(account_ref) <= 120
                 and all(c.isascii() and (c.isalnum() or c in "-_")
                         for c in account_ref)))
    )


def normalize_manual_label(label: str) -> str:
    if not isinstance(label, str):
        raise ValueError("explicit conversation label is required")
    value = label.strip()
    if not value or len(value) > MAX_MANUAL_LABEL:
        raise ValueError("conversation label must contain 1..52 characters")
    if any(ord(c) < 32 or ord(c) == 127 or c in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" for c in value):
        raise ValueError("control and bidi override characters are forbidden")
    return value


def manual_selected_event(
    text: str, account_ref: str, label: str, direction: str,
    now_ms: int | None = None,
) -> dict:
    """Create a *user-claimed* event, never an authenticated WeChat message."""
    if not valid_manual_account(account_ref):
        raise ValueError("manual source requires a synthetic/manual account reference")
    label = normalize_manual_label(label)
    if direction not in ("me", "other"):
        raise ValueError("sender direction must be explicitly selected")
    if not isinstance(text, str):
        raise ValueError("text must be selected and reviewed by the user")
    selected = text.strip()
    if not selected or len(selected) > MAX_MANUAL_TEXT:
        raise ValueError("selected text must contain 1..4000 characters")
    when = int(time.time() * 1000) if now_ms is None else now_ms
    if type(when) is not int or when <= 0:
        raise ValueError("invalid event timestamp")
    digest = hashlib.sha256(
        (account_ref + "\0" + label).encode("utf-8")
    ).hexdigest()[:24]
    return {
        "account_ref": account_ref,
        "session_id": MANUAL_SESSION_PREFIX + digest,
        "message_id": "manual-" + secrets.token_hex(16),
        "display_name": "手动选择：" + label + "（联系人未核验）",
        "direction": direction,
        "text": selected,
        "time_ms": when,
    }


class ManualImportController:
    """Memory-only importer with a short anti-double-click window."""

    def __init__(self, store):
        if not valid_manual_account(store.account_ref):
            raise ValueError("manual source requires a synthetic/manual account reference")
        self.store = store
        self._hmac_key = secrets.token_bytes(32)
        self._recent: dict[bytes, int] = {}
        self._lock = threading.Lock()

    def import_selected(
        self, text: str, label: str, direction: str, now_ms: int | None = None
    ) -> tuple[bool, str]:
        event = manual_selected_event(
            text, self.store.account_ref, label, direction, now_ms
        )
        digest = hmac.digest(
            self._hmac_key,
            (event["session_id"] + "\0" + direction + "\0" + event["text"]).encode("utf-8"),
            "sha256",
        )
        when = event["time_ms"]
        with self._lock:
            if digest in self._recent and 0 <= when - self._recent[digest] < REPLAY_WINDOW_MS:
                return False, event["session_id"]
            if self.store.append([event]) != 1:
                raise ValueError("manual message rejected by relay")
            self._recent[digest] = when
            self._recent = {
                key: at for key, at in self._recent.items()
                if 0 <= when - at < REPLAY_WINDOW_MS
            }
        return True, event["session_id"]


def run_manual_gui(store, server, bearer_token: str) -> None:
    """Tkinter runs only when --manual-gui is explicitly selected.

    No clipboard change subscriptions, background clipboard access, API key
    extraction, or clipboard write operations.
    """
    import tkinter as tk
    from tkinter import ttk

    importer = ManualImportController(store)
    root = tk.Tk()
    root.title("JEV · 手动消息导入（未核验联系人）")
    root.geometry("530x420")
    root.minsize(480, 360)
    main = ttk.Frame(root, padding=16)
    main.pack(fill="both", expand=True)

    ttk.Label(main, text="复制聊天文字 → 预览 → 指定会话及方向 → 确认导入").pack(anchor="w")
    ttk.Label(main, text="只读取本次手动复制；Windows 不调用模型或发送微信消息。").pack(anchor="w")
    ttk.Label(main, text="Android 开启分析后可能使用已配置模型；手机只会展示候选。").pack(anchor="w")
    ttk.Label(main, text=f"本机端口：{server.server_address[1]}  |  配对标识：{store.account_ref}").pack(anchor="w")
    ttk.Label(main, text="会话标签（由你填写，不能作为微信账号身份证明）：").pack(anchor="w", pady=(12, 0))
    label = ttk.Entry(main, width=45)
    label.pack(fill="x")
    ttk.Label(main, text="消息方向（必须手动选择）：").pack(anchor="w", pady=(12, 0))
    direction = tk.StringVar(value="")
    choices = ttk.Frame(main)
    choices.pack(anchor="w")
    ttk.Radiobutton(choices, text="我发送的", variable=direction, value="me").pack(side="left", padx=(0, 16))
    ttk.Radiobutton(choices, text="对方发送的", variable=direction, value="other").pack(side="left")
    ttk.Label(main, text="剪贴板文字（仅在点击“读取剪贴板”时读取，可编辑）：").pack(anchor="w", pady=(12, 0))
    preview = tk.Text(main, height=7, wrap="word")
    preview.pack(fill="both", expand=True)
    status = tk.StringVar(value="尚未读取；请先在聊天窗口主动复制文字。")

    def read_clipboard():
        try:
            raw = root.clipboard_get()
            if not isinstance(raw, str) or not raw.strip() or len(raw.strip()) > MAX_MANUAL_TEXT:
                raise ValueError("剪贴板文字为空或超过 4000 字符")
            preview.delete("1.0", "end")
            preview.insert("1.0", raw)
            status.set("文字已载入预览；请核对会话标签、方向，然后确认导入。")
        except (tk.TclError, ValueError):
            status.set("无法读取有效文本，请重新复制文字。")

    def import_reviewed():
        try:
            selected = preview.get("1.0", "end-1c")
            inserted, _ = importer.import_selected(selected, label.get(), direction.get())
            if inserted:
                status.set("已导入 JEV 本地队列。需要 Android 端显式允许分析；不会自动发送。")
                preview.delete("1.0", "end")
            else:
                status.set("同一会话、方向及内容在 30 秒内已导入，不重复计费。")
        except ValueError:
            status.set("导入未执行：请核对预览文字、会话标签及发送方向。")

    buttons = ttk.Frame(main)
    buttons.pack(anchor="w", pady=(10, 0))
    ttk.Button(buttons, text="1. 读取剪贴板", command=read_clipboard).pack(side="left", padx=(0, 10))
    ttk.Button(buttons, text="2. 确认导入 JEV", command=import_reviewed).pack(side="left")

    def copy_pairing_token():
        # On an explicit button tap only. Never put credentials in logs or files.
        root.clipboard_clear()
        root.clipboard_append(bearer_token)
        root.update()
        status.set("配对 Token 已复制；只能提供给已授权的 JEV Android 设置。")

    ttk.Button(main, text="复制本机配对 Token（敏感）", command=copy_pairing_token).pack(
        anchor="w", pady=(8, 0)
    )
    ttk.Label(main, textvariable=status, wraplength=480).pack(anchor="w", pady=(9, 0))
    try:
        root.mainloop()
    finally:
        server.shutdown()
        server.server_close()
