"""Focused source-level copy checks; no phone, provider or chat access."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/jev/probe'

def read(name):
    return (SRC / name).read_text(encoding='utf-8-sig')

class MobileCopyContractTest(unittest.TestCase):
    def test_home_explains_workflow_without_developer_names(self):
        text = read('MainActivity.kt')
        for label in ('复制消息后，点悬浮球获取回复建议', '补充最近对话', '模型与回复风格'):
            self.assertIn(label, text, label)
        for internal in ('InputConnection.commitText', 'com.tencent.mm）通知', '微信 A / B 共用', '关联上下文当前关闭'):
            self.assertNotIn(internal, text, internal)

    def test_permission_disclosure_preserves_scope_cloud_and_user_send(self):
        text = read('MainActivity.kt')
        for clause in ('系统会允许此服务接收通知', '只处理微信通知', '不会读取微信聊天页面', '模型服务商', '不会自动发送', '截图'):
            self.assertIn(clause, text, clause)
        self.assertNotIn('只有你点击悬浮球发起分析时，才会尝试读取当前可见内容', text)

    def test_settings_removes_protocol_jargon_not_model_configuration(self):
        text = read('SettingsActivity.kt')
        for label in ('我的回复风格（可选）', '服务地址', '授权密钥（API Key）', '分析服务和回复服务', '可能收费'):
            self.assertIn(label, text, label)
        for detail in ('强制关闭 thinking', 'reasoning_effort=low', '同 host', '完整 URL POST'):
            self.assertNotIn(detail, text, detail)

    def test_import_has_plain_time_privacy_and_switch_warning(self):
        text = read('ManualContextActivity.kt')
        for clause in ('最多使用最近 20 条', '60 分钟后', '切换聊天对象', '模型服务商'):
            self.assertIn(clause, text, clause)
        for detail in ('进程内存', '训练接口', '仅当前运行'):
            self.assertNotIn(detail, text, detail)

    def test_overlay_does_not_claim_certainty_or_bill_for_cache(self):
        text = read('overlay/OverlayController.kt')
        self.assertIn('对方可能的意思', text)
        self.assertIn('重新生成（可能收费）', text)
        self.assertNotIn('对方真实意图', text)
        self.assertNotIn('把握 ${(it.confidence', text)
        self.assertNotIn('推荐 · ${pct}%', text)
        self.assertNotIn('未用知识库', text)

    def test_notes_explain_local_storage_and_model_submission(self):
        text = read('KnowledgeActivity.kt')
        self.assertIn('联系人与备注', text)
        self.assertIn('相关备注会交给你选择的模型服务商', text)
        self.assertNotIn('数据只留在本机', text)
        self.assertNotIn('命中规则：', text)
        self.assertIn('删除', text)
        self.assertIn('不可恢复', text)

    def test_retired_desktop_not_advertised_to_new_users(self):
        text = read('SettingsActivity.kt')
        self.assertIn('val showLegacyDesktopSettings = prefs.desktopBridgeEnabled ||', text)
        self.assertIn('prefs.desktopBridgeEndpoint.isNotBlank()', text)
        self.assertIn('prefs.desktopBridgeAccountRef.isNotBlank()', text)
        self.assertIn('if (showLegacyDesktopSettings) root.addView(desktopCard)', text)
        self.assertNotIn('root.addView(section("电脑聊天上下文 · 测试版"))', text)
        self.assertIn('prefs.desktopBridgeToken = desktopToken', text)

    def test_custom_service_https_rule_is_explicit(self):
        text = read('SettingsActivity.kt')
        self.assertIn('自定义服务地址必须以 https:// 开头', text)
        self.assertIn('本机调试', text)
        self.assertNotIn('通常以 https:// 开头', text)

    def test_legacy_desktop_token_only_remains_manageable(self):
        text = read('SettingsActivity.kt')
        self.assertIn('prefs.desktopBridgeToken.isNotBlank()', text)

    def test_accessibility_disclosure_does_not_repeat_capture_instruction(self):
        text = read('MainActivity.kt')
        self.assertNotIn('截图识字也需你自己选择', text)
        self.assertIn('截图识字需你主动选择', text)

if __name__ == '__main__':
    unittest.main(verbosity=2)
