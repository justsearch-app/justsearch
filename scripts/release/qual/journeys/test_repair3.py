"""Session-local acquisition readiness and required retrieval evidence."""
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import linux
from test_repair2 import immediate
from test_writes import wrapper


class Repair3Tests(unittest.TestCase):
    def test_fresh_download_still_requires_acquisition_readiness(self):
        run = Mock()
        run.facts = {}
        run.wd.execute.return_value = []
        run.api.side_effect = lambda path: {
            '/api/ai/install/plan-preview': {'downloadProfile': 'CPU'},
            '/api/ai/install/manifest': {},
            '/api/ai/install/status': {'installedFully': True, 'readyCapabilities': []},
            '/api/ai/runtime/status': {'onnxFeatures': [
                {'id': name, 'modelActive': True} for name in ('embed', 'splade')]},
        }[path]
        with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'activate_downloaded'):
            with self.assertRaisesRegex(AssertionError, 'Installed CPU search capability is not ready'):
                linux.install_ai(run)
        run.evidence.assert_any_call('J3-activation-memory', [run.target.json.return_value] * 2)

    def test_dense_retrieval_without_executed_cross_encoder_is_rejected(self):
        run = Mock()
        for cross in ([], [{'id': 'cross-encoder', 'status': 'skipped'}]):
            with self.subTest(cross=cross):
                run.api.return_value = {'results': [linux.OPEN_FILE], 'searchTrace': {
                    'effectiveMode': 'VECTOR', 'stages': [
                        {'id': 'dense-retrieval', 'status': 'executed'}, *cross]}}
                with patch.object(linux, 'wait_for', immediate):
                    with self.assertRaisesRegex(AssertionError, 'Semantic fixture retrieval'):
                        linux.semantic_search(run)

    def test_guest_crashes_are_retained_and_product_crash_is_never_hidden(self):
        for executable, product in (('/usr/lib/ubuntu-release-upgrader/check-new-release-gtk', False),
                                    ('/opt/just-search/runtime/bin/java', True),
                                    ('/opt/just-search/JustSearch', True)):
            with self.subTest(executable=executable), tempfile.TemporaryDirectory() as temp:
                content = ('ProblemType: Crash\nExecutablePath: ' + executable + '\nSignal: 5\n').encode()
                def ssh(_, command, **kwargs):
                    if command.startswith('find '):
                        return Mock(stdout='test.crash\n')
                    with tarfile.open(fileobj=kwargs['stdout'], mode='w') as archive:
                        member = tarfile.TarInfo('test.crash'); member.size = len(content)
                        archive.addfile(member, io.BytesIO(content))
                with patch.object(wrapper.vm, 'ssh', ssh), patch.object(wrapper.vm, 'config', return_value={'password': 'guest'}):
                    if product:
                        with self.assertRaisesRegex(AssertionError, 'Product crash in guest'):
                            wrapper.crash_evidence(Path(temp))
                    else:
                        wrapper.crash_evidence(Path(temp))
                self.assertEqual(json.loads((Path(temp) / 'guest-crashes.json').read_text())[0]['fields']['Signal'], '5')
                with tarfile.open(Path(temp) / 'guest-crashes.tar') as archive:
                    self.assertEqual(archive.extractfile('test.crash').read(), content)

    def test_notifications_disabled_before_compositor_preparation(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Mock()
            target.run.return_value.stdout = 'true\n'
            with patch.object(wrapper, 'Target', return_value=target), patch.object(wrapper, 'Native') as native, \
                 patch.object(wrapper, 'crash_evidence', create=True), patch.object(wrapper.vm, 'guest_root') as root, \
                 patch.object(wrapper.vm, 'QMP'), patch.object(wrapper, 'wait_for', return_value=True), \
                 patch('screen.key'):
                wrapper.prepare_desktop(SimpleNamespace(session='wayland'), Path(temp))
                target.run.assert_any_call(['gsettings', 'set', 'com.ubuntu.update-notifier', 'show-apport-crashes', 'false'])
                root.assert_called_once_with('ubuntu', 'systemctl restart gdm3')
                self.assertEqual(target.prepare.call_count, 2)
                native.return_value.focused_window.assert_called_once_with()


if __name__ == '__main__':
    unittest.main()
