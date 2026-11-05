"""Offline contracts for the published Dev12 documentation, not APK certification."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RELEASE = 'https://github.com/blackkcold/CinemaTV/releases/tag/v0.8.0-dev.2'


class Dev12ReleaseDocsTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_public_versions_and_source_separation(self):
        for path in ('README.md', 'docs/INSTALL.md', 'docs/UPDATE_CHANNELS.md',
                     'docs/RELEASE_CHECKLIST.md'):
            with self.subTest(document=path):
                text = self.read(path)
                self.assertIn(RELEASE, text)
                self.assertIn('main', text)
                self.assertIn('0.8.0', text)
                for stale in ('channel-dev冻结', 'channel-dev未启用本次发布',
                              '开发通道等待后续候选', 'contains only production-signed stable',
                              'channel-dev publication is frozen', 'only stable is published'):
                    self.assertNotIn(stale, text)

    def test_explicit_experiment_paths_and_no_implied_account_authorization(self):
        for path in ('README.md', 'docs/INSTALL.md'):
            with self.subTest(document=path):
                text = self.read(path)
                self.assertIn('TV：设置 → 连接与账户 → 开发者选项 · Dev', text)
                self.assertIn('手机：设置 → 开发者选项 · Dev', text)
                self.assertIn('默认关闭', text)
                self.assertIn('开启入口不等于授权账号访问', text)
                self.assertIn('不会自动登录、同步或开启局域网服务', text)
                self.assertIn('关闭并清理豆瓣账号', text)
                self.assertRegex(text, r'已导入片库保留|保留已导入片库')

    def test_upgrade_preference_and_downgrade_limits_remain_explicit(self):
        for path in ('README.md', 'docs/INSTALL.md'):
            with self.subTest(document=path):
                text = self.read(path)
                self.assertIn('Stable 未明确保存渠道选择时', text)
                self.assertIn('升级 Dev 后默认渠道变为 Dev', text)
                self.assertIn('更高 code 的 Stable', text)
                self.assertIn('卸载', text)
                self.assertIn('升级前明确', text)
        channels = self.read('docs/UPDATE_CHANNELS.md')
        self.assertNotIn('Updates retain Room data and preferences.', channels)
        self.assertIn('do not\n  guarantee complete Room or preference migration', channels)
        self.assertIn('pre-upgrade explicit preference migration was\nnot tested', channels)
        self.assertIn('wait for a higher-code stable APK, without uninstalling', channels)
        self.assertIn('2026-11-12 14:40:12 UTC', channels)
        self.assertNotIn('2026-11-12 17:25:47 UTC', channels)
        self.assertIn('channel-dev has not been published', channels)
        self.assertIn('reserved, not an active feed', channels)
        for path in ('README.md', 'docs/INSTALL.md'):
            self.assertIn('Dev 暂需手动下载，channel-dev 尚未公开', self.read(path))

    def test_final_dev_identity_and_acceptance_are_scoped(self):
        text = self.read('docs/RELEASE_CHECKLIST.md')
        for value in ('5a076b0b715750a4de43253726fa66c205aa9b32',
                      '553d37076ba0f9989ad5d24942f82638cf722478',
                      'c1efb18c945034e51c03ab940875cc98ea2fb743',
                      '83ffa9aef559dded9454adb9fdb5f9864a37341ea18bb8b4961660434dcd92f9',
                      'CinemaTV-0.8.0-dev.2-code12-dev.apk', '43,328,036 bytes'):
            self.assertIn(value, text)
        for limit in ('empty-account', 'pre-upgrade explicit preference migration was **not** tested',
                      'Real login', 'real-session cleanup', 'fresh dev12 install',
                      'API33 final-APK installation', 'production updater end-to-end',
                      'hardware and four-hour playback remain unverified'):
            self.assertIn(limit, text)
        self.assertIn('Dev11 remains unpublished', text)
        self.assertIn('This environment blocker is not evidence of an', text)
        self.assertIn('app or published-feed defect', text)
        self.assertIn('no Dev feed was requested', text)


if __name__ == '__main__':
    unittest.main()
