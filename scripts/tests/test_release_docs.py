"""Offline contracts for the release documentation, not APK certification.

The Dev12 acceptance facts live in the release record; the public README and install
guide describe the current channel state. Both are asserted here so a channel or
version edit cannot silently drop either one.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
STABLE_RELEASE = 'https://github.com/blackkcold/CinemaTV/releases/tag/v0.9.0'
DEV_RELEASE = 'https://github.com/blackkcold/CinemaTV/releases/tag/v0.9.0-dev.4'


class ReleaseDocsTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_public_versions_and_source_separation(self):
        for path in ('README.md', 'docs/INSTALL.md', 'docs/UPDATE_CHANNELS.md',
                     'docs/RELEASE_CHECKLIST.md'):
            with self.subTest(document=path):
                text = self.read(path)
                self.assertIn('main', text)
                self.assertIn('0.9.0', text)
                for stale in ('channel-dev冻结', 'channel-dev未启用本次发布',
                              '开发通道等待后续候选', 'contains only production-signed stable',
                              'channel-dev publication is frozen', 'only stable is published'):
                    self.assertNotIn(stale, text)

    def test_public_entry_documents_both_channels(self):
        readme = self.read('README.md')
        self.assertIn(STABLE_RELEASE, readme)
        self.assertIn(DEV_RELEASE, readme)
        self.assertIn('dev-compose', readme)
        self.assertIn('archive/tv-web-5c24360', readme)
        self.assertIn('不进入 Stable 源码范围', readme)
        install = self.read('docs/INSTALL.md')
        self.assertIn(DEV_RELEASE, install)
        self.assertIn('code 16', install)
        self.assertIn('code 15', install)
        self.assertIn('本 Stable 源码与 `main` 都不包含它', install)

    def test_account_experiment_is_not_advertised_in_stable_docs(self):
        for path in ('README.md', 'docs/INSTALL.md'):
            with self.subTest(document=path):
                text = self.read(path)
                for advertised in ('开发者选项 · Dev', '开启豆瓣账号实验', '关闭并清理豆瓣账号'):
                    self.assertNotIn(advertised, text)

    def test_upgrade_preference_and_downgrade_limits_remain_explicit(self):
        install = self.read('docs/INSTALL.md')
        for value in ('Stable 未明确保存渠道选择时', '当前 Dev code 17 已高于 Stable code 16',
                      '反向从 Dev 降回 Stable 需要等待更高的 Stable code', '卸载',
                      '升级前明确渠道偏好', 'code 17'):
            self.assertIn(value, install)
        channels = self.read('docs/UPDATE_CHANNELS.md')
        self.assertNotIn('Updates retain Room data and preferences.', channels)
        self.assertIn('do not\n  guarantee complete Room or preference migration', channels)
        self.assertIn('pre-upgrade explicit preference migration was\nnot tested', channels)
        self.assertIn('wait for a higher-code stable APK, without uninstalling', channels)
        self.assertIn('2026-12-02 09:33:33 UTC', channels)
        self.assertNotIn('2026-11-12 14:40:12 UTC', channels)
        self.assertIn('channel-dev has not been published', channels)
        self.assertIn('reserved, not an active feed', channels)
        self.assertIn('one increasing versionCode ledger across both channels', channels)
        self.assertIn('Dev 暂需手动下载，channel-dev 尚未公开', install)

    def test_dev_identity_and_acceptance_are_scoped(self):
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
