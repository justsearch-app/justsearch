#!/usr/bin/env python3
"""Isolated standard-library tests; no guest or shared VM data is touched."""
import contextlib
import fcntl
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


class DiskGrowthTests(unittest.TestCase):
    """Real qcow2 graphs, confined to this test's disposable qualification root."""
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='qual-growth-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for key, value in [('ROOT', self.root), ('STATE', self.root / 'running.json')]:
            mocked = patch.object(vm, key, value)
            mocked.start(); self.addCleanup(mocked.stop)
        # Isolate liveness probes from the owner's ports; test their refusal
        # separately below, and use the real flock in every prune invocation.
        live = patch.object(vm, 'live_reasons', return_value=[])
        self.live = live.start(); self.addCleanup(live.stop)
        self.base = self.image('ubuntu/base.qcow2')

    def image(self, name, backing=None):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        args = ['qemu-img', 'create', '-q', '-f', 'qcow2']
        args += ['-F', 'qcow2', '-b', str(backing)] if backing else ['-o', 'size=1M']
        subprocess.run([*args, str(path)], check=True, capture_output=True)
        return path

    def prune(self, *targets, apply=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return vm.prune_disks([Path(t) for t in targets], 'retired release / successful retry', apply)

    def readable(self, path):
        subprocess.run(['qemu-img', 'info', '--output=json', '--backing-chain', str(path)],
                       check=True, capture_output=True)

    def test_independent_retirement_preserves_non_disks_and_requires_apply(self):
        disk = self.image('evidence/retired/snapshot/disk.qcow2', self.base)
        log = disk.parent / 'run.log'; log.write_bytes(b'unchanged evidence\x00')
        plan = self.prune('evidence/retired')
        self.assertEqual([x['path'] for x in plan['planned']], [str(disk)])
        self.assertTrue(disk.exists())
        with patch.object(sys, 'argv', ['vm.py', 'prune-disks', '--reason', 'retired']), \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            vm.main()
        applied = self.prune('evidence/retired', apply=True)
        self.assertEqual(applied['deleted'], plan['planned'])
        self.assertFalse(disk.exists()); self.assertEqual(log.read_bytes(), b'unchanged evidence\x00')
        self.readable(self.base)

    def test_kept_children_runs_and_base_records_protect_all_ancestors(self):
        parent = self.image('evidence/w4b/failed-state/disk.qcow2', self.base)
        child = self.image('evidence/w8/snapshot/disk.qcow2', parent)
        run = self.image('windows/run.qcow2', child)
        plan = self.prune('evidence/w4b', 'evidence/w8', apply=True)
        self.assertEqual(plan['deleted'], [])
        self.assertIn('still referenced', ' '.join(plan['protected'][str(parent)]))
        self.readable(run)
        # A disk named by base.json is protected even without a current child.
        extra = self.image('custom/base/disk.qcow2', self.base)
        (self.root / 'ubuntu/base.json').write_text(json.dumps({'disk': str(extra)}))
        with self.assertRaisesRegex(RuntimeError, 'base'):
            self.prune('custom/base', apply=True)
        self.assertTrue(extra.exists())

    def test_failed_retry_snapshot_referenced_or_independent(self):
        failed = self.image('evidence/failed/snapshot/disk.qcow2', self.base)
        retry = self.image('evidence/retry/snapshot/disk.qcow2', failed)
        self.assertEqual(self.prune('evidence/failed')['planned'], [])
        retry.unlink()
        retry = self.image('evidence/retry/snapshot/disk.qcow2', self.base)
        self.assertEqual(self.prune('evidence/failed', apply=True)['deleted'][0]['path'], str(failed))
        self.readable(retry)

    def test_children_first_partial_receipt_and_idempotent_retry(self):
        parent = self.image('evidence/old/parent.qcow2', self.base)
        child = self.image('evidence/old/child.qcow2', parent)
        planned = self.prune('evidence/old')['planned']
        self.assertEqual([x['path'] for x in planned], [str(child), str(parent)])
        unlink = Path.unlink
        def fail_parent(path, *args, **kwargs):
            if path == parent:
                raise PermissionError('injected unlink failure')
            return unlink(path, *args, **kwargs)
        with patch.object(Path, 'unlink', fail_parent), self.assertRaisesRegex(PermissionError, 'injected'):
            self.prune('evidence/old', apply=True)
        receipt = next((self.root / 'evidence').glob('old.prune-*.json'))
        original = receipt.read_bytes()
        partial = json.loads(original)
        self.assertEqual(partial['planned'], planned)
        self.assertEqual(partial['deleted'], planned[:1])
        self.readable(parent)
        self.assertEqual(self.prune('evidence/old', apply=True)['deleted'], planned[1:])
        self.assertEqual(receipt.read_bytes(), original)
        self.assertEqual(self.prune('evidence/old', apply=True)['deleted'], [])
        self.readable(self.base)

    def test_corrupt_or_unreadable_image_anywhere_aborts_every_deletion(self):
        disk = self.image('evidence/old/disk.qcow2', self.base)
        bad = self.root / 'elsewhere.qcow2'; bad.write_bytes(b'corrupt')
        with self.assertRaisesRegex(RuntimeError, 'elsewhere.qcow2'):
            self.prune('evidence/old', apply=True)
        self.assertTrue(disk.exists()); bad.unlink()
        real_run = vm.run
        def unreadable(*args, **kwargs):
            if args[-1] == self.base:
                raise PermissionError('injected unreadable member')
            return real_run(*args, **kwargs)
        with patch.object(vm, 'run', side_effect=unreadable), self.assertRaisesRegex(RuntimeError, 'base.qcow2'):
            self.prune('evidence/old', apply=True)
        self.assertTrue(disk.exists())

    def test_missing_backing_keeps_head_and_selected_members_before_gap(self):
        gap = self.image('gap.qcow2')
        parent = self.image('evidence/old/parent.qcow2', gap)
        head = self.image('ubuntu/run.qcow2', parent)
        independent = self.image('evidence/old/independent.qcow2', self.base)
        gap.unlink()
        plan = self.prune('evidence/old', apply=True)
        self.assertEqual([x['path'] for x in plan['deleted']], [str(independent)])
        self.assertEqual(plan['missingBacking'], {str(parent): str(gap)})
        self.assertIn('missing backing', ' '.join(plan['protected'][str(head)]))
        self.assertTrue(parent.exists()); self.assertTrue(head.exists())

    def test_stale_run_with_missing_backing_is_reported_without_blocking_plan(self):
        stale = self.image('stale.qcow2')
        head = self.image('ubuntu/run.qcow2', stale)
        disk = self.image('evidence/old/disk.qcow2', self.base)
        stale.unlink()
        plan = self.prune('evidence/old')
        self.assertEqual(plan['missingBacking'], {str(head): str(stale)})
        self.assertIn(str(head), plan['protected'])
        self.assertEqual([p['path'] for p in plan['planned']], [str(disk)])
        self.assertTrue(disk.exists())

    def test_selection_boundaries_symlinks_and_base_refused(self):
        for target in ('.', 'ubuntu', '../outside'):
            with self.subTest(target=target), self.assertRaises(RuntimeError):
                self.prune(target, apply=True)
        disk = self.image('evidence/old/disk.qcow2', self.base)
        (self.root / 'alias').symlink_to(disk.parent, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'Symlinked'):
            self.prune('alias', apply=True)
        self.assertTrue(self.base.exists()); self.assertTrue(disk.exists())

    def test_relative_backing_resolves_but_external_and_symlink_backing_refuse(self):
        parent = self.image('evidence/old/parent.qcow2', self.base)
        child = self.image('evidence/kept/child.qcow2', '../old/parent.qcow2')
        self.assertEqual(self.prune('evidence/old')['planned'], [])
        self.readable(child)
        child.unlink()
        (self.root / 'alias.qcow2').symlink_to(self.base)
        child = self.image('evidence/kept/child.qcow2', '../../alias.qcow2')
        with self.assertRaisesRegex(RuntimeError, 'Symlinked'):
            self.prune('evidence/old', apply=True)
        child.unlink(); (self.root / 'alias.qcow2').unlink()
        with tempfile.TemporaryDirectory() as external:
            outside = Path(external) / 'base.qcow2'
            subprocess.run(['qemu-img', 'create', '-q', '-f', 'qcow2', str(outside), '1M'], check=True)
            self.image('evidence/kept/child.qcow2', outside)
            with self.assertRaisesRegex(RuntimeError, 'outside qualification root'):
                self.prune('evidence/old', apply=True)
        self.assertTrue(parent.exists())

    def test_identity_change_refuses_before_any_unlink(self):
        first = self.image('evidence/old/a.qcow2', self.base)
        second = self.image('evidence/old/b.qcow2', self.base)
        original = vm.prune_plan
        def replaced(*args):
            plan = original(*args)
            second.rename(second.with_suffix('.saved'))
            self.image('evidence/old/b.qcow2', self.base)
            return plan
        with patch.object(vm, 'prune_plan', side_effect=replaced), self.assertRaisesRegex(RuntimeError, 'changed'):
            self.prune('evidence/old', apply=True)
        self.assertTrue(first.exists()); self.readable(second)

    def test_receipt_cannot_be_written_refuses_before_unlink(self):
        disk = self.image('evidence/old/disk.qcow2', self.base)
        real_open = Path.open
        def refuse(path, mode='r', *args, **kwargs):
            if mode == 'x':
                raise PermissionError('unwritable receipt')
            return real_open(path, mode, *args, **kwargs)
        with patch.object(Path, 'open', refuse), self.assertRaisesRegex(PermissionError, 'receipt'):
            self.prune('evidence/old', apply=True)
        self.assertTrue(disk.exists())

    def test_lock_or_running_vm_refuses(self):
        disk = self.image('evidence/old/disk.qcow2', self.base)
        with (self.root / 'vm.lock').open('a') as lease:
            fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
            for apply in (False, True):
                with self.subTest(apply=apply), self.assertRaisesRegex(RuntimeError, 'lock held'):
                    self.prune('evidence/old', apply=apply)
        self.live.return_value = ['test VM running']
        with self.assertRaisesRegex(RuntimeError, 'may be running'):
            self.prune('evidence/old', apply=True)
        self.assertTrue(disk.exists())

    def test_allocated_tree_counts_sparse_and_hardlinks_once_like_du(self):
        sparse = self.root / 'sparse'
        with sparse.open('wb') as out:
            out.truncate(10 * 1024 ** 3)
            out.write(b'a' * 4096)
        os.link(sparse, self.root / 'hardlink')
        (self.root / 'ignored-symlink').symlink_to('/tmp')
        measured = vm.allocated_bytes()
        du = int(subprocess.check_output(['du', '-s', '-B1', str(self.root)]).split()[0])
        self.assertEqual(measured, du)
        self.assertLess(measured, 1024 ** 2)

    def test_admission_refuses_under_lock_before_replacing_existing_disk(self):
        disk = self.image('ubuntu/run.qcow2', self.base)
        before = disk.stat().st_ino, disk.read_bytes()
        (self.root / 'ubuntu/base.json').write_text('{}')
        def check():
            with (self.root / 'vm.lock').open('r') as lock:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return 10
        with patch.dict(os.environ, {'QUAL_OWNER': 'test-growth'}), \
             patch.object(vm, 'wait_host_memory'), patch.object(vm, 'allocated_bytes', side_effect=check), \
             patch.object(vm, 'STORAGE_LIMIT', 10), patch.object(vm, 'launch') as launch, \
             self.assertRaises(vm.StorageBudget):
            vm.start('ubuntu')
        launch.assert_not_called()
        self.assertEqual((disk.stat().st_ino, disk.read_bytes()), before)


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
        # Ownership/launch fixtures must not wait on another session's host RAM.
        # test_memory.py covers the memory guard with synthetic procfs readings.
        memory = patch.object(vm, 'wait_host_memory')
        memory.start()
        self.addCleanup(memory.stop)
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

    def test_owner_commands_work_during_cli_ssh(self):
        data = self.record(owner='test-owner')
        folder = screen.queue_path(data)
        folder.mkdir(parents=True)
        request = folder / ('a' * 32 + '.request')
        request.write_text(json.dumps({'owner': 'test-owner', 'action': 'key', 'values': {'combo': 'ret'}}))

        def during_ssh(*args, **kwargs):
            with patch.object(sys, 'argv', ['screen.py', 'key', 'ubuntu', 'ret']), patch.object(screen, 'QMP'):
                screen.main()
            with patch.object(sys, 'argv', ['vm.py', 'copy-in', 'ubuntu', 'source', 'dest']), patch.object(vm, 'copy_in') as copy:
                vm.main()
                copy.assert_called_once()
            with patch.object(vm, 'current', side_effect=[data, data, None]), patch.object(vm, 'live_reasons', return_value=['live']), patch.object(screen, 'require_owner', return_value=data), patch.object(screen, 'QMP'), patch.object(screen.time, 'sleep'):
                screen.broker('ubuntu')
            reply = json.loads(request.with_suffix('.reply').read_text())
            self.assertEqual(reply, {'result': 'ok'})
            return Mock(returncode=0)

        with patch.object(sys, 'argv', ['vm.py', 'ssh', 'ubuntu', 'sleep 30']), patch.object(vm, 'ssh', side_effect=during_ssh):
            with self.assertRaises(SystemExit) as exit:
                vm.main()
        self.assertEqual(exit.exception.code, 0)

    def test_failed_qemu_cleans_its_record_and_reports_current_error(self):
        proc = Mock(pid=123, returncode=1)
        proc.poll.return_value = 1
        log = self.root / 'ubuntu-qemu.log'
        log.write_text('OLD unrelated error\n')
        def spawn(*args, **kwargs):
            with log.open('a') as output:
                output.write('gtk initialization failed\n')
            return proc
        with self.no_live(), patch.object(vm, 'wait_host_memory'), patch.object(vm.os, 'sched_getaffinity', return_value=set(range(8))), patch.object(vm, 'identity', return_value='123'), patch.object(vm.socket, 'socket'), patch.object(vm.subprocess, 'Popen', side_effect=spawn):
            with vm.locked() as lock:
                with self.assertRaisesRegex(RuntimeError, '^QEMU exited 1: gtk initialization failed$'):
                    vm.launch('ubuntu', self.root / 'ubuntu/run.qcow2', self.root / 'ubuntu/run-vars.fd', visible=True, lease=lock.fileno())
        self.assertFalse(vm.STATE.exists())
        self.assertEqual((self.root / 'ubuntu/run.qcow2').read_bytes(), b'LIVE DISK')

    def test_failed_start_removes_only_new_run_files(self):
        untouched = self.root / 'ubuntu/base-vars.fd'
        with self.no_live(), patch.object(vm, 'config'), patch.object(vm, 'run'), patch.object(vm, 'launch', side_effect=RuntimeError('bad image')):
            with self.assertRaisesRegex(RuntimeError, 'bad image'):
                vm.start('ubuntu')
        self.assertFalse((self.root / 'ubuntu/run.qcow2').exists())
        self.assertFalse((self.root / 'ubuntu/run-vars.fd').exists())
        self.assertEqual(untouched.read_bytes(), b'BASE VARIABLES')
        self.assertEqual((self.root / 'windows/run.qcow2').read_bytes(), b'LIVE DISK')

    def test_prepare_preserves_visible_launch_error(self):
        package = self.root / 'package.deb'
        package.touch()
        def stopped():
            if not vm.STATE.exists():
                return True
            if self.data.get('visible'):
                raise RuntimeError('Stale running.json')
            vm.STATE.unlink()
            return True
        def failed_launch(*args, **kwargs):
            self.record(owner='test-owner', visible=True)
            raise RuntimeError('gtk initialization failed')
        with patch.object(vm, 'start', side_effect=lambda *a: self.record(owner='test-owner')), patch.object(vm, 'wait_ssh'), patch.object(vm, 'copy_in'), patch.object(vm, 'guest_root'), patch.object(vm, 'ssh'), patch.object(vm, 'stop', side_effect=stopped), patch.object(vm, 'launch', side_effect=failed_launch):
            with self.assertRaisesRegex(RuntimeError, 'gtk initialization failed'):
                vm.prepare_owner('ubuntu', package)

    def test_missing_owner_names_recorded_owner(self):
        self.record()
        with patch.dict(os.environ, {'QUAL_OWNER': ''}):
            for operation in (lambda: vm.require_owner('ubuntu'), lambda: vm.QMP('ubuntu')):
                with self.assertRaisesRegex(RuntimeError, 'QUAL_OWNER is required.*owner someone-else'):
                    operation()

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
            for port in (spec['ssh'], spec['legacy_qmp']):
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


class MirrorCleanupTest(unittest.TestCase):
    def test_three_cycles_restore_hosts_bytes_and_refresh_cert_links(self):
        import importlib.util
        import tarfile
        spec = importlib.util.spec_from_file_location('mirror_guest', vm.HERE / 'mirror-guest.py')
        guest = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guest)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            guest.ROOT, guest.INPUT = root / 'state', root / 'input'
            guest.CA, guest.HOSTS = root / 'ca.crt', root / 'hosts'
            for original in (b'127.0.0.1 localhost\n', b'127.0.0.1 localhost'):
                guest.HOSTS.write_bytes(original)
                for cycle in range(3):
                    guest.INPUT.mkdir()
                    for name in ('ca.crt', 'server.crt', 'server.key'):
                        (guest.INPUT / name).write_text('test')
                    with tarfile.open(guest.INPUT / 'files.tar.gz', 'w:gz'):
                        pass
                    with patch.object(guest.subprocess, 'run') as run, patch.object(guest.subprocess, 'Popen', return_value=Mock(pid=123, poll=Mock(return_value=None))), patch.object(guest, 'identity', return_value=None), patch.object(guest.socket, 'create_connection'):
                        guest.setup(False)
                        self.assertIn(guest.MARKER.encode(), guest.HOSTS.read_bytes())
                        guest.remove()
                    self.assertEqual(guest.HOSTS.read_bytes(), original)
                    self.assertFalse(guest.CA.exists())
                    self.assertEqual(run.call_args.args[0], ['update-ca-certificates', '--fresh'])


if __name__ == '__main__':
    unittest.main()
