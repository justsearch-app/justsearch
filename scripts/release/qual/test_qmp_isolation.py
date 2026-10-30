"""Host-only QMP transport using isolated host fixtures."""
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import Mock, patch

import vm


class QMPIsolationTests(unittest.TestCase):
    def test_qemu_launch_exposes_qmp_only_in_private_host_directory(self):
        for guest in vm.OS:
            with self.subTest(guest=guest), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                proc = Mock(pid=os.getpid()); proc.poll.return_value = None
                with patch.object(vm, 'ROOT', root), patch.object(vm, 'STATE', root / 'running.json'), \
                     patch.object(vm, 'ensure_free'), patch.object(vm, 'wait_host_memory'), \
                     patch.object(vm, 'owner', return_value='fixture'), patch.object(vm, 'QMP'), \
                     patch.object(vm.socket, 'socket'), \
                     patch.object(vm.subprocess, 'Popen', return_value=proc) as launch:
                    vm.launch(guest, root / 'disk', root / 'vars', lease=123)
                argv = launch.call_args.args[0]
                endpoint = argv[argv.index('-qmp') + 1]
                self.assertTrue(endpoint.startswith('unix:'), endpoint)
                path = Path(endpoint.removeprefix('unix:').split(',')[0])
                self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
                self.assertEqual(path.parent.stat().st_uid, os.getuid())
                self.assertNotIn('restrict=on', argv[argv.index('-nic') + 1])
                self.assertIn('hostfwd=tcp:127.0.0.1:', argv[argv.index('-nic') + 1])

    def test_each_live_unix_socket_blocks_a_new_launch_without_a_record(self):
        for guest in vm.OS:
            with self.subTest(guest=guest), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); (root / 'qmp').mkdir(mode=0o700)
                path = root / 'qmp' / (guest + '.sock')
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                    listener.bind(str(path)); listener.listen()
                    with patch.object(vm, 'ROOT', root), patch.object(vm, 'STATE', root / 'running.json'), \
                         patch.object(vm, 'port_open', return_value=False), \
                         patch.object(vm.Path, 'iterdir', return_value=[]):
                        with self.assertRaisesRegex(RuntimeError, str(path)):
                            vm.ensure_free()

    def test_qmp_client_never_connects_to_guest_reachable_tcp(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            transport = Mock()
            transport.makefile.return_value.readline.side_effect = [b'{"QMP": {}}\n', b'{"return": {}}\n']
            with patch.object(vm, 'ROOT', root), patch.object(vm, 'require_owner'), \
                 patch.object(vm.socket, 'socket', return_value=transport) as factory, \
                 patch.object(vm.socket, 'create_connection', side_effect=AssertionError('QMP used guest-reachable TCP')):
                with vm.QMP('windows'):
                    pass
            factory.assert_called_once_with(socket.AF_UNIX, socket.SOCK_STREAM)
            path = Path(transport.connect.call_args.args[0])
            self.assertEqual(path, root / 'qmp' / 'windows.sock')
