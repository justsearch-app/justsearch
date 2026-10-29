"""No VM, host services or shared data; exercise snapshot and counter boundaries."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from runner import Infrastructure
from writes import difference

spec = importlib.util.spec_from_file_location('journey_vm_run', Path(__file__).with_name('vm-run.py'))
wrapper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wrapper)


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
