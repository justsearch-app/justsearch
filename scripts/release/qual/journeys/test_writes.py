"""No VM, host services or shared data; exercise snapshot and counter boundaries."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from runner import Infrastructure
from writes import difference

spec = importlib.util.spec_from_file_location('journey_vm_run', Path(__file__).with_name('vm-run.py'))
wrapper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wrapper)
spec = importlib.util.spec_from_file_location('growth_windows', Path(__file__).with_name('vm-windows.py'))
windows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(windows)


class GrowthLaneTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='qual-lane-growth-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for key, value in [('ROOT', self.root), ('STATE', self.root / 'running.json')]:
            self.mock(wrapper.vm, key, value)
        for method in ('ensure_free', 'wait_free', 'wait_host_memory', 'wait_ssh'):
            self.mock(wrapper.vm, method)
        self.mock(wrapper, 'wait_for', return_value=True)
        self.mock(windows, 'wait_for', return_value=True)
        self.mock(wrapper, 'pins', return_value={})
        self.mock(windows, 'pins', return_value={})
        owner = patch.dict(os.environ, {'QUAL_OWNER': 'growth-test'})
        owner.start(); self.addCleanup(owner.stop)
        self.snapshot = self.root / 'tools'; self.snapshot.mkdir()
        self.image(self.snapshot / 'disk.qcow2')
        (self.snapshot / 'vars.fd').write_bytes(b'firmware')
        (self.snapshot / 'checkpoint.json').write_text('{}')
        (self.snapshot / 'manifest.json').write_text(json.dumps({'pins': {}, 'kind': 'tools'}))
        (self.snapshot / 'quiet-verify.json').write_text('{"verified":true}')
        for name in ('ubuntu', 'windows'):
            folder = self.root / name; folder.mkdir()
            self.image(folder / 'base.qcow2')
            (folder / 'base.json').write_text('{}')
            (folder / 'base-vars.fd').write_bytes(b'firmware')
            self.image(folder / 'run.qcow2', folder / 'base.qcow2')
            (folder / 'run-vars.fd').write_bytes(b'old firmware')

    def mock(self, obj, key, *args, **kwargs):
        mocked = patch.object(obj, key, *args, **kwargs)
        value = mocked.start(); self.addCleanup(mocked.stop)
        return value

    def image(self, path, backing=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        args = ['qemu-img', 'create', '-q', '-f', 'qcow2']
        args += ['-F', 'qcow2', '-b', str(backing)] if backing else ['-o', 'size=1M']
        subprocess.run([*args, str(path)], check=True, capture_output=True)

    def args(self, module):
        return SimpleNamespace(session='vm' if module is windows else 'wayland', mode='fresh',
            journeys=None, predecessor=Path('predecessor'), mirror=Path('mirror'),
            memory_mib=12288, memory_reason=None, evidence=self.root / 'out')

    def test_snapshot_admission_preserves_existing_run_disk_on_both_lanes(self):
        for module, name in ((wrapper, 'ubuntu'), (windows, 'windows')):
            disk = self.root / name / 'run.qcow2'
            before = disk.stat().st_ino, disk.read_bytes()
            with self.subTest(lane=name), patch.object(wrapper.vm, 'STORAGE_LIMIT', 0), \
                 patch.object(wrapper.vm, 'launch') as launch, self.assertRaises(wrapper.vm.StorageBudget):
                module.start_snapshot(self.snapshot)
            self.assertEqual((disk.stat().st_ino, disk.read_bytes()), before)
            launch.assert_not_called()

    def test_refusal_has_dedicated_failed_infrastructure_row_on_both_lanes(self):
        for module, name in ((wrapper, 'ubuntu'), (windows, 'windows')):
            out = self.root / ('refusal-' + name)
            args = self.args(module)
            with self.subTest(lane=name), patch.object(wrapper.vm, 'STORAGE_LIMIT', 0), \
                 patch.object(wrapper.vm, 'launch') as launch:
                self.assertEqual(module.run_overlay(args, out, 'candidate', ['J1'], self.snapshot, seal=True), 1)
            launch.assert_not_called()
            report = json.loads((out / 'summary.json').read_text())
            row = next(r for r in report['journeys'] if r['id'] == 'storage-budget')
            self.assertEqual(row['checks'][0]['status'], 'failed-infrastructure')
            self.assertFalse(report['passed'])

    def test_windows_preparation_refusal_keeps_disk_and_reports_storage_budget(self):
        disk = self.root / 'windows/run.qcow2'
        before = disk.stat().st_ino, disk.read_bytes()
        out = self.root / 'prepare-refused'
        with patch.object(wrapper.vm, 'STORAGE_LIMIT', 0), patch.object(wrapper.vm, 'launch') as launch:
            self.assertEqual(windows.prepare_setup(self.args(windows), out), 1)
        self.assertEqual((disk.stat().st_ino, disk.read_bytes()), before)
        report = json.loads((out / 'summary.json').read_text())
        self.assertEqual(report['journeys'][0]['id'], 'storage-budget')
        launch.assert_not_called()

    def test_external_sealing_destination_refused_before_launch_or_creation(self):
        with tempfile.TemporaryDirectory() as external:
            for module in (wrapper, windows):
                out = Path(external) / module.__name__
                with self.subTest(lane=module.__name__), patch.object(wrapper.vm, 'launch') as launch, \
                     self.assertRaisesRegex(RuntimeError, 'outside qualification root'):
                    module.run_overlay(self.args(module), out, 'candidate', ['J1'], seal=True)
                self.assertFalse(out.exists()); launch.assert_not_called()
            out = Path(external) / 'prepare'
            with self.assertRaisesRegex(RuntimeError, 'outside qualification root'):
                windows.prepare_setup(self.args(windows), out)
            self.assertFalse(out.exists())

    def test_sealing_past_threshold_counts_new_layer_only_and_failure_does_not_count(self):
        for module, name in ((wrapper, 'ubuntu'), (windows, 'windows')):
            for fail in (False, True):
                with self.subTest(lane=name, fail=fail):
                    out = self.root / (name + str(fail)); out.mkdir()
                    (out / 'summary.json').write_text('{"writeVolume":{"writtenBytes":123}}')
                    (out / 'checkpoint.json').write_text('{}')
                    (out / 'quiet-verify.json').write_text('{"verified":true}')
                    disk = self.root / name / 'run.qcow2'
                    if not disk.exists():
                        self.image(disk, self.root / name / 'base.qcow2')
                        disk.with_name('run-vars.fd').write_bytes(b'firmware')
                    inode = disk.stat().st_ino
                    allocation = disk.stat().st_blocks * 512
                    with patch.object(wrapper.vm, 'STORAGE_LIMIT', 0):
                        if fail:
                            with patch.object(module, 'pins', side_effect=OSError('injected seal failure')), \
                                 self.assertRaisesRegex(OSError, 'seal failure'):
                                module.seal_snapshot(out, {'disk': str(disk)}, inode, self.args(module), 'candidate')
                        else:
                            module.seal_snapshot(out, {'disk': str(disk)}, inode, self.args(module), 'candidate')
                        with self.assertRaises(wrapper.vm.StorageBudget):
                            module.start_snapshot(self.snapshot)
                    report = json.loads((out / 'summary.json').read_text())
                    self.assertEqual(report['writeVolume']['writtenBytes'], 123)
                    if fail:
                        self.assertNotIn('storage', report)
                        self.assertFalse((out / 'snapshot/manifest.json').exists())
                    else:
                        self.assertEqual(report['storage']['snapshotBytes'], allocation)
                        self.assertEqual((out / 'snapshot/disk.qcow2').stat().st_ino, inode)
                        destination = out / 'combined'; destination.mkdir()
                        source = dict(report, journeys=[])
                        module.combine(self.args(module), destination, {'first': source, 'second': {'journeys': []}})
                        combined = json.loads((destination / 'summary.json').read_text())
                        self.assertEqual(combined['storage']['snapshotBytes'], allocation)

    def test_first_or_later_storage_refusal_stops_full_run_and_combination_on_both_lanes(self):
        for module, name in ((wrapper, 'ubuntu'), (windows, 'windows')):
            for stop_at in (1, 2):
                with self.subTest(lane=name, stop_at=stop_at):
                    args = self.args(module)
                    args.evidence = self.root / (name + '-stage-' + str(stop_at))
                    calls = []
                    real_overlay = module.run_overlay
                    def stage(args, out, phase, journeys, snapshot=None, seal=False):
                        calls.append(out.name)
                        if len(calls) == stop_at:
                            with patch.object(wrapper.vm, 'STORAGE_LIMIT', 0):
                                return real_overlay(args, out, phase, journeys, snapshot, seal)
                        out.mkdir()
                        (out / 'summary.json').write_text('{"journeys":[]}')
                        dest = out / 'snapshot'; dest.mkdir()
                        self.image(dest / 'disk.qcow2', self.snapshot / 'disk.qcow2')
                        (dest / 'manifest.json').write_text('{"kind":"application","pins":{}}')
                        (dest / 'quiet-verify.json').write_text('{"verified":true}')
                        (dest / 'checkpoint.json').write_text('{}')
                        return 0
                    with patch.object(module, 'run_overlay', side_effect=stage), \
                         patch.object(wrapper.vm, 'launch') as launch:
                        if module is wrapper:
                            with patch.object(module, 'parse_args', return_value=args):
                                result = module.main()
                        else:
                            argv = ['vm-windows.py', '--assets', 'assets', '--package', 'app.exe',
                                '--registry', 'registry', '--driver', 'driver', '--predecessor', 'previous',
                                '--mirror', 'mirror', '--setup-snapshot', str(self.snapshot),
                                '--evidence', str(args.evidence)]
                            with patch.object(sys, 'argv', argv):
                                result = module.main()
                    self.assertEqual(len(calls), stop_at)
                    self.assertNotEqual(result, 0); launch.assert_not_called()
                    report = json.loads((args.evidence / 'summary.json').read_text())
                    self.assertFalse(report['passed'])
                    self.assertTrue(any(r['id'] == 'storage-budget' for r in report['journeys']))

    def test_windows_preparation_reboot_and_seal_past_threshold_count_layer_once(self):
        vm = wrapper.vm
        out = self.root / 'prepared'
        allocations = []
        real_admit = vm.admit_disk
        admission = self.mock(vm, 'admit_disk', wraps=real_admit)
        def launch(name, disk, variables, **kwargs):
            allocations.append(disk.stat().st_ino)
            data = {'os': name, 'pid': 999999999, 'start_ticks': '1',
                    'disk': str(disk), 'owner': 'growth-test'}
            vm.save(vm.STATE, data)
            return data
        def stop():
            vm.STATE.unlink()
            return True
        def quiet(mode, folder):
            if mode == 'Apply':
                vm.STORAGE_LIMIT = 0  # Allocation during the admitted first boot.
            (folder / ('quiet-' + mode.lower() + '.json')).write_text('{"verified":true}')
        def copied(source, destination, **kwargs):
            destination.write_text('{"webview2":"156.0","msedgedriver":"156.0"}')
        self.mock(vm, 'launch', side_effect=launch)
        self.mock(vm, 'stop', side_effect=stop)
        self.mock(windows, 'stage_inputs')
        self.mock(windows, 'guest_ps', return_value='')
        self.mock(windows, 'copy_out', side_effect=copied)
        self.mock(windows, 'quiet_guest', side_effect=quiet)
        self.mock(windows, 'prepare_viewer')
        self.mock(windows, 'verify_crash_access')
        budget = self.mock(windows, 'WriteBudget')
        budget.return_value.finish.side_effect = lambda: {'overlayInitialBytes': 0}
        budget.return_value.error = None
        args = self.args(windows)
        args.mirror = None
        (self.snapshot / 'checkpoint.json').unlink()  # Tools preparation has no app checkpoint.
        with patch.object(vm, 'STORAGE_LIMIT', 150 * 1024 ** 3):
            self.assertEqual(windows.prepare_setup(args, out, self.snapshot), 0,
                             (out / 'summary.json').read_text())
            self.assertEqual(len(allocations), 2)
            self.assertEqual(allocations[0], allocations[1])
            admission.assert_called_once()
            report = json.loads((out / 'summary.json').read_text())
            self.assertEqual(len(report['writeVolume']), 2)
            self.assertEqual(report['storage']['snapshotBytes'], (out / 'disk.qcow2').stat().st_blocks * 512)
            with self.assertRaises(vm.StorageBudget):
                windows.start_snapshot(out)


class WriteTests(unittest.TestCase):
    def test_counts_guest_write_bytes_without_double_counting_partitions(self):
        before = {'devices': {'sda': 512}, 'writtenBytes': 512, 'swapWrittenBytes': 0}
        after = {'devices': {'sda': 2560}, 'writtenBytes': 2560, 'swapWrittenBytes': 4096}
        result = difference(before, after)
        self.assertEqual(result['writtenBytes'], 2048)
        self.assertEqual(result['swapWrittenBytes'], 4096)
        with self.assertRaisesRegex(RuntimeError, 'reset'):
            difference(after, before)
        with self.assertRaisesRegex(RuntimeError, 'changed'):
            difference(before, dict(after, devices={'sdb': 2560}))

    def test_snapshot_seal_moves_existing_disk_without_copying_it(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            out = root / 'evidence'; out.mkdir()
            disk = root / 'run.qcow2'; disk.write_bytes(b'expensive setup')
            (root / 'run-vars.fd').write_bytes(b'firmware')
            (out / 'checkpoint.json').write_text('{}')
            (out / 'summary.json').write_text('{}')
            inode = disk.stat().st_ino
            with patch.object(wrapper.vm, 'ROOT', root), patch.object(wrapper.vm, 'ensure_free'), \
                 patch.object(wrapper, 'pins', return_value={'package': 'exact-sha'}):
                result = wrapper.seal_snapshot(out, {'disk': str(disk)}, inode,
                                                SimpleNamespace(memory_mib=6144), 'candidate')
            self.assertEqual((result / 'disk.qcow2').stat().st_ino, inode)
            self.assertFalse(disk.exists())
            self.assertEqual(json.loads((result / 'manifest.json').read_text())['pins']['package'], 'exact-sha')

    def test_stale_disk_identity_cannot_seal_someone_elses_run(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            disk = root / 'run.qcow2'; disk.write_bytes(b'other run')
            with patch.object(wrapper.vm, 'ROOT', root), patch.object(wrapper.vm, 'ensure_free'):
                with self.assertRaisesRegex(Infrastructure, 'changed'):
                    wrapper.seal_snapshot(root, {'disk': str(disk)}, -1, None, 'candidate')
            self.assertEqual(disk.read_bytes(), b'other run')

    def test_wrong_snapshot_pins_refuse_before_boot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            snapshot = root / 'snapshot'; snapshot.mkdir()
            (snapshot / 'manifest.json').write_text(json.dumps({'pins': {'sha': 'wrong'}}))
            args = SimpleNamespace(session='x11')
            with patch.object(wrapper, 'pins', return_value={'sha': 'expected'}), \
                 patch.object(wrapper.vm, 'start') as start, patch.object(wrapper.vm, 'wait_free') as wait:
                self.assertEqual(wrapper.run_overlay(args, root / 'out', 'candidate', ['J7'], snapshot), 1)
            start.assert_not_called(); wait.assert_not_called()
            self.assertIn('pins do not match', (root / 'out/wrapper-error.txt').read_text())

    def test_only_selected_journeys_are_forwarded_to_runner(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'run').mkdir(); (root / 'run/summary.json').write_text('{}')
            args = SimpleNamespace(session='wayland', assets=root, package='candidate.deb', registry=root / 'server.json',
                                   predecessor=None, mirror=None)
            with patch.object(wrapper.subprocess, 'run', return_value=Mock(returncode=0)) as run:
                wrapper.execute(args, root, 'candidate', ['J7'], root / 'snapshot')
            command = run.call_args.args[0]
            self.assertEqual(command[command.index('--journeys') + 1:], ['J7'])
            self.assertIn('--resume', command)

    def test_snapshot_continuation_is_not_fresh_qualification_in_rerun_mode(self):
        with tempfile.TemporaryDirectory() as temp:
            report = {'journeys': [{'id': 'J7', 'checks': [{'name': 'shortcut', 'status': 'passed', 'evidence': ['focus.json']}]}],
                      'writeVolume': {'writtenBytes': 1024}}
            args = SimpleNamespace(session='wayland', mode='rerun', journeys=['J7'])
            wrapper.combine(args, Path(temp), {'rerun': report})
            saved = json.loads((Path(temp) / 'summary.json').read_text())
            self.assertFalse(saved['execution']['releaseQualification'])
            self.assertEqual(saved['journeys'][0]['checks'][0]['evidence'], ['rerun/run/focus.json'])
            self.assertEqual(saved['writeVolume']['writtenBytes'], 1024)


if __name__ == '__main__':
    unittest.main()
