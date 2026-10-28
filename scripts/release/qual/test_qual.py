#!/usr/bin/env python3
"""Isolated standard-library tests; no guest or shared VM data is touched."""
import contextlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, os.environ.get('QUAL_HARNESS_DIR', str(Path(__file__).resolve().parent)))
import vm
import screen


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='qual-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for module, key, value in [(vm, 'ROOT', self.root), (vm, 'STATE', self.root / 'running.json'),
                                   (screen, 'ROOT', self.root)]:
            mock = patch.object(module, key, value)
            mock.start()
            self.addCleanup(mock.stop)
        mock = patch.dict(os.environ, {'QUAL_OWNER': 'test-owner'})
        mock.start()
        self.addCleanup(mock.stop)
        for name in vm.OS:
            folder = self.root / name
            folder.mkdir()
            (folder / 'base.json').write_text('{}')
            (folder / 'base-vars.fd').write_bytes(b'BASE VARIABLES')
            (folder / 'run-vars.fd').write_bytes(b'LIVE VARIABLES')
            (folder / 'run.qcow2').write_bytes(b'LIVE DISK')
        self.data = {'os': 'ubuntu', 'owner': 'someone-else', 'pid': 2147483647,
                     'start_ticks': '1234', 'disk': str(self.root / 'ubuntu/run.qcow2')}

    def record(self, **overrides):
        self.data.update(overrides)
        vm.STATE.write_text(json.dumps(self.data))
        return self.data

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}

    @contextlib.contextmanager
    def no_live(self):
        with patch.object(vm, 'live_reasons', return_value=[]):
            yield

    def test_stale_start_preserves_disk_and_variables(self):
        self.record()
        before = self.snapshot()
        with patch.object(vm, 'identity', return_value=None), patch.object(vm, 'run'), patch.object(vm, 'launch'):
            with self.assertRaisesRegex(RuntimeError, 'running.json|running|owner'):
                vm.start('ubuntu')
        self.assertEqual(before, self.snapshot())

    def test_missing_owner_before_any_write(self):
        before = self.snapshot()
        with patch.dict(os.environ, {'QUAL_OWNER': ''}):
            with self.assertRaisesRegex(RuntimeError, 'QUAL_OWNER'):
                vm.start('ubuntu')
        self.assertEqual(before, self.snapshot())

    def test_invisible_current_retains_owner(self):
        self.record()
        with patch.object(vm, 'identity', return_value=None):
            self.assertEqual(vm.current(), self.data)

    def test_all_ports_block_both_os_before_any_write(self):
        for spec in vm.OS.values():
            for port in (spec['ssh'], spec['qmp']):
                for guest in vm.OS:
                    with self.subTest(port=port, guest=guest):
                        before = self.snapshot()
                        with patch.object(vm, 'port_open', side_effect=lambda p: p == port), patch.object(vm.subprocess, 'run', return_value=Mock(returncode=0)):
                            with self.assertRaisesRegex(RuntimeError, str(port)):
                                vm.start(guest)
                        self.assertEqual(before, self.snapshot())

    def test_unknown_port_is_not_free(self):
        before = self.snapshot()
        with patch.object(vm.socket, 'create_connection', side_effect=PermissionError('sandbox')):
            with self.assertRaisesRegex(RuntimeError, 'Cannot establish'):
                vm.start('ubuntu')
        self.assertEqual(before, self.snapshot())

    def test_connect_probe(self):
        with socket.socket() as server:
            server.bind(('127.0.0.1', 0))
            server.listen()
            port = server.getsockname()[1]
            self.assertTrue(vm.port_open(port))
        self.assertFalse(vm.port_open(port))

    def test_open_disk_blocks_start(self):
        before = self.snapshot()
        with patch.object(vm, 'port_open', return_value=False), patch.object(vm.subprocess, 'run', return_value=Mock(returncode=1, stderr='write lock held')):
            with self.assertRaisesRegex(RuntimeError, 'may be open'):
                vm.start('windows')
        self.assertEqual(before, self.snapshot())

    def test_inherited_lock_blocks_start_with_no_visible_process_or_state(self):
        with vm.locked() as lock:
            child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'], pass_fds=(lock.fileno(),))
        try:
            before = self.snapshot()
            with patch.object(vm, 'identity', return_value=None), patch.object(vm, 'port_open', return_value=False), patch.object(vm.subprocess, 'run', return_value=Mock(returncode=0)):
                with self.assertRaisesRegex(RuntimeError, 'lock held'):
                    vm.start('windows')
            self.assertEqual(before, self.snapshot())
        finally:
            child.terminate()
            child.wait(timeout=5)

    def test_stale_only_cleared_explicitly(self):
        self.record()
        with self.no_live():
            with self.assertRaisesRegex(RuntimeError, 'clear-stale'):
                vm.ensure_free()
            self.assertTrue(vm.STATE.exists())
            vm.clear_stale()
        self.assertFalse(vm.STATE.exists())
        self.assertEqual((self.root / 'ubuntu/run.qcow2').read_bytes(), b'LIVE DISK')

    def test_stop_does_not_clear_own_stale_record(self):
        self.record(owner='test-owner')
        with self.no_live():
            with self.assertRaisesRegex(RuntimeError, 'clear-stale explicitly'):
                vm.stop()
        self.assertTrue(vm.STATE.exists())

    def test_owned_installer_poweroff_is_closed_without_forcing(self):
        self.record(owner='test-owner')
        qmp = Mock()
        qmp.command.return_value = {'status': 'shutdown'}
        with patch.object(vm, 'QMP') as factory, patch.object(vm, 'live_reasons', side_effect=[['lease'], [], [], []]):
            factory.return_value.__enter__.return_value = qmp
            self.assertTrue(vm.stop())
        self.assertEqual([call.args[0] for call in qmp.command.call_args_list], ['query-status', 'quit'])
        self.assertFalse(vm.STATE.exists())

    def test_broker_validates_key_and_text_before_input(self):
        qmp = Mock()
        for action, values in [('key', {'combo': 1}), ('type', {'text': 'x' * 4097})]:
            with self.assertRaises(ValueError):
                screen.perform(qmp, action, values)
        qmp.command.assert_not_called()

    def test_clear_stale_rechecks_every_signal(self):
        self.record()
        with patch.object(vm, 'live_reasons', return_value=['SSH answers']):
            with self.assertRaisesRegex(RuntimeError, 'Refusing clear-stale'):
                vm.clear_stale()
        self.assertTrue(vm.STATE.exists())

    def test_clear_stale_refuses_held_lock(self):
        self.record()
        with vm.locked():
            with self.assertRaisesRegex(RuntimeError, 'lock held'):
                vm.clear_stale()
        self.assertTrue(vm.STATE.exists())

    def test_corrupt_record_is_preserved(self):
        vm.STATE.write_text('{')
        with self.no_live():
            with self.assertRaisesRegex(RuntimeError, 'Cannot read'):
                vm.ensure_free()
        self.assertEqual(vm.STATE.read_text(), '{')

    def test_status_invisible_reports_owner(self):
        self.record()
        with patch.object(sys, 'argv', ['vm.py', 'status']), patch.object(vm, 'identity', return_value=None), patch.object(vm, 'live_reasons', return_value=['QMP answers']), contextlib.redirect_stdout(io.StringIO()) as output:
            vm.main()
        self.assertEqual(json.loads(output.getvalue())['running']['status'], 'running (owner someone-else, process not visible)')

    def test_status_stale_does_not_clear(self):
        self.record()
        with patch.object(sys, 'argv', ['vm.py', 'status']), patch.object(vm, 'identity', return_value=None), self.no_live(), contextlib.redirect_stdout(io.StringIO()) as output:
            vm.main()
        self.assertIn('stale record', output.getvalue())
        self.assertTrue(vm.STATE.exists())

    def test_stop_ssh_copy_qmp_refuse_wrong_owner(self):
        self.record()
        for operation in (vm.stop, lambda: vm.ssh('ubuntu', 'touch /wrong'),
                          lambda: vm.copy_in('ubuntu', '/wrong', '/wrong'), lambda: vm.QMP('ubuntu')):
            with self.subTest(operation=operation), patch.object(vm.subprocess, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'owner someone-else'):
                    operation()
                run.assert_not_called()

    def test_reset_refuses_wrong_owner_preserves_files(self):
        self.record()
        before = self.snapshot()
        with patch.object(sys, 'argv', ['vm.py', 'reset']):
            with self.assertRaisesRegex(RuntimeError, 'owner someone-else'):
                vm.main()
        after = self.snapshot()
        after.pop('control.lock', None)
        self.assertEqual(before, after)

    def test_every_screen_command_refuses_wrong_owner_including_broker(self):
        self.record()
        commands = [['shot', 'ubuntu', str(self.root / 'never.png')], ['click', 'ubuntu', '1', '1'],
                    ['move', 'ubuntu', '1', '1'], ['key', 'ubuntu', 'ret'], ['type', 'ubuntu', 'oops'], ['broker', 'ubuntu']]
        before = self.snapshot()
        for args in commands:
            with patch.object(sys, 'argv', ['screen.py', *args]), patch.object(screen, 'QMP') as qmp:
                with self.assertRaisesRegex(RuntimeError, 'owner someone-else'):
                    screen.main()
                qmp.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_wait_free_returns_and_timeout_preserves_state(self):
        with self.no_live():
            vm.wait_free(0)
            self.record()
            with self.assertRaisesRegex(RuntimeError, 'wait-free timed out'):
                vm.wait_free(0)
        self.assertTrue(vm.STATE.exists())

    def test_wait_free_follows_release(self):
        with patch.object(vm, 'ensure_free', side_effect=[RuntimeError('busy'), None]), patch.object(vm.time, 'sleep'):
            vm.wait_free(5)

    def test_broker_rejects_foreign_owner_and_non_screen_action(self):
        data = self.record(owner='test-owner')
        folder = screen.queue_path(data)
        folder.mkdir(parents=True)
        for i, job in enumerate([{'owner':'other', 'action':'shot', 'values':{}},
                                 {'owner':'test-owner', 'action':'ssh', 'values':{}}]):
            (folder / (f'{i:032x}' + '.request')).write_text(json.dumps(job))
        with patch.object(vm, 'current', side_effect=[data, data, data, None]), patch.object(vm, 'live_reasons', return_value=['live']), patch.object(screen, 'require_owner', return_value=data), patch.object(screen, 'QMP') as qmp, patch.object(screen.time, 'sleep'):
            screen.broker('ubuntu')
        for reply in folder.glob('*.reply'):
            self.assertIn('error', json.loads(reply.read_text()))
        self.assertEqual(len(list(folder.glob('*.reply'))), 2)
        # The second request may connect to QMP but cannot send arbitrary commands.
        qmp.return_value.__enter__.return_value.command.assert_not_called()

    def test_broker_disallows_output_path_in_request(self):
        with self.assertRaisesRegex(ValueError, 'exact arguments'):
            screen.perform(None, 'shot', {'out': '/etc/hosts'})

    def test_type_validates_before_input(self):
        qmp = Mock()
        with self.assertRaises(ValueError):
            screen.type_text(qmp, 'valid first then \u2603')
        qmp.command.assert_not_called()

    def test_ppm_first_pixel_whitespace_preserved(self):
        self.assertEqual(screen.ppm(b'P6\n1 1\n255\n\n\t '), (1, 1, b'\n\t '))

    def test_corpus_rejects_symlink(self):
        folder = self.root / 'corpus'
        folder.mkdir()
        (folder / 'outside').symlink_to('/etc/hosts')
        with self.assertRaisesRegex(ValueError, 'regular files'):
            vm.archive_folder(folder, self.root / 'corpus.tar.gz')

    def test_prepare_validates_before_start(self):
        with patch.object(vm, 'start') as start:
            with self.assertRaises(ValueError):
                vm.prepare_owner('ubuntu')
            start.assert_not_called()

    def test_mirror_refuses_wrong_owner(self):
        self.record()
        with self.assertRaisesRegex(RuntimeError, 'owner someone-else'):
            vm.mirror('ubuntu', '/tmp/no')


if __name__ == '__main__':
    unittest.main()
