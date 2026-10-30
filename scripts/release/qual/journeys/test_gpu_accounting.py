"""S3/S22: scope accounting where available; device upper-bound backstop otherwise."""
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import gpu
import runner
from test_gpu_isolation import host


class AccountingTests(unittest.TestCase):
    def test_hierarchical_scope_total_excludes_siblings_without_double_counting(self):
        with tempfile.TemporaryDirectory() as tmp:
            tree = Path(tmp)
            scope = tree / 'agents.slice/lane.scope'
            child = scope / 'child'
            sibling = scope.parent / 'other.scope'
            for path in (scope, child, sibling):
                path.mkdir(parents=True, exist_ok=True)
            # Linux's parent io.stat already includes child cgroups.
            (scope / 'io.stat').write_text('259:2 rbytes=99 wbytes=4096 wios=1\n8:0 wbytes=8192 rbytes=0\n')
            (child / 'io.stat').write_text('259:2 wbytes=2048\n')
            (sibling / 'io.stat').write_text('259:2 wbytes=12000000000\n')
            start = gpu.cgroup_writes(scope)
            self.assertEqual(start, 12288)
            (child / 'io.stat').write_text('259:2 wbytes=6144\n')
            (scope / 'io.stat').write_text('259:2 wbytes=8192\n8:0 wbytes=8192\n')
            (sibling / 'io.stat').write_text('259:2 wbytes=24000000000\n')
            self.assertEqual(gpu.cgroup_writes(scope) - start, 4096)

    def test_missing_or_malformed_accounting_refuses_instead_of_reporting_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            scope = Path(tmp)
            with self.assertRaisesRegex(runner.Infrastructure, 'I/O controller'):
                gpu.cgroup_writes(scope)
            for contents in ('259:2 wbytes=oops\n', '259:2 rbytes=1\n', '259:2 wbytes=-1\n'):
                (scope / 'io.stat').write_text(contents)
                with self.subTest(contents=contents), self.assertRaises(runner.Infrastructure):
                    gpu.cgroup_writes(scope)
            (scope / 'io.stat').write_text('')
            self.assertEqual(gpu.cgroup_writes(scope), 0)

    def test_reuses_existing_scope_including_nested_process_cgroup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proc = root / 'self-cgroup'
            proc.write_text('0::/user.slice/agents.slice/lane.scope/subgroup\n')
            self.assertEqual(gpu.cgroup_scope(proc, root), root / 'user.slice/agents.slice/lane.scope')
            for path in ('/user.slice/app.slice/session.scope', '/user.slice/agents.slice'):
                proc.write_text('0::' + path + '\n')
                self.assertIsNone(gpu.cgroup_scope(proc, root))
            with patch.object(gpu, 'cgroup_scope', return_value=root), \
                    patch.object(gpu, 'cgroup_writes', return_value=0), patch.object(host.os, 'execvp') as execute:
                self.assertEqual(host.accounting_scope(), root)
                execute.assert_not_called()

    def test_unscoped_command_enters_transient_agents_scope(self):
        with patch.object(gpu, 'cgroup_scope', return_value=None), \
                patch.object(host.os, 'execvp', side_effect=SystemExit) as execute:
            with self.assertRaises(SystemExit):
                host.accounting_scope()
            command = execute.call_args.args[1]
            self.assertEqual(command[:5], ['systemd-run', '--user', '--scope', '--slice=agents.slice', '--property=IOAccounting=yes'])
            self.assertIn(str(runner.HERE / 'gpu-host.py'), command)

    def test_journey_observations_use_same_scope_as_host_guard(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, QUAL_GPU_CGROUP=tmp, QUAL_GPU_BLOCK_DEVICE='259:2'):
            (Path(tmp) / 'io.stat').write_text('259:2 wbytes=3456\n')
            with patch.object(gpu, 'device_writes', return_value=12_000_000_000):
                self.assertEqual(gpu.Run.__new__(gpu.Run).write_counters()['writtenBytes'], 3456)

    def test_live_guard_uses_scope_or_fallback_device_for_backstop(self):
        for use_scope in (True, False):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                scope = root / 'lane.scope'
                scope.mkdir()
                (scope / 'io.stat').write_text('259:2 wbytes=100000000\n')
                out = root / 'attempt-1'
                def environment(*args, **kwargs):
                    (root / 'bin').mkdir()
                    return {'PATH': '/usr/bin'}
                args = types.SimpleNamespace(development_resume_root=None,
                    write_start={'scope': str(scope) if use_scope else None, 'device': '259:2', 'startBytes': 0},
                    driver='/usr/bin/true', development_pack_mirror=None, source=root,
                    assets=root, package='fixture.deb', soak_corpus=root, tool_resource=[],
                    journeys=['J1'], development_mirror_port=8769, write_budget_bytes=600000000)
                child = Mock(pid=os.getpid(), poll=Mock(side_effect=[None, 0, 0]), wait=Mock(return_value=0))
                with patch.object(host, 'private_root') as private, \
                        patch.object(gpu, 'private_environment', side_effect=environment), \
                        patch.object(gpu, 'resource_sample', return_value={'available': True}), \
                        patch.object(gpu, 'device_writes', return_value=0 if use_scope else 100000000), \
                        patch.object(host.shutil, 'which', return_value='/usr/bin/true'), \
                        patch.object(host.subprocess, 'run'), \
                        patch.object(host.subprocess, 'check_output', side_effect=['.git', '1']), \
                        patch.object(host.subprocess, 'Popen', return_value=child), \
                        patch.object(host, 'sandbox_command', return_value=['fixture']), \
                        patch.object(host, 'descendants', return_value=[]), \
                        patch.object(runner.Target, 'stop'), patch.object(host.time, 'sleep'):
                    private.return_value.__enter__.return_value = root
                    host.run_attempt(args, [], out, 1, root / 'soak-started')
                self.assertTrue((out / 'write-budget-stop.json').exists(), 'Scope budget must stop the live lane')
                self.assertEqual(json.loads((out / 'write-budget-stop.json').read_text())['observedBytes'], 100000000)

    def test_interruption_final_accounting_uses_scope_and_device_is_information_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'evidence'
            scope = root / 'lane.scope'
            scope.mkdir()
            stats = scope / 'io.stat'
            stats.write_text('259:2 wbytes=100\n')
            def interrupted(*args):
                stats.write_text('259:2 wbytes=500\n')
                raise KeyboardInterrupt('SIGTERM fixture')
            argv = ['gpu-host.py', '--evidence', str(out), '--assets', tmp, '--package', 'candidate.deb',
                    '--source', tmp, '--soak-corpus', tmp, '--journeys', 'J1', '--write-budget-bytes', '2500000000']
            with patch.object(sys, 'argv', argv), patch.object(host, 'accounting_scope', return_value=scope), \
                    patch.object(host, 'owner_metadata', return_value=[]), patch.object(host, 'mount_device', return_value='259:2'), \
                    patch.object(gpu, 'device_writes', side_effect=[1000, 1000 + 12_000_000_000]), \
                    patch.object(host, 'run_attempt', side_effect=interrupted):
                self.assertEqual(host.main(), 1)
            writes = json.loads((out / 'write-volume.json').read_text())
            self.assertEqual(writes['writtenBytes'], 400)
            self.assertEqual(writes['scope'], str(scope))
            report = json.loads((out / 'summary.json').read_text())
            self.assertEqual(report['scopeWrites']['writtenBytes'], 400)
            self.assertTrue(report['deviceWriteUpperBound']['informationalOnly'])
            self.assertEqual(report['deviceWriteUpperBound']['writtenBytes'], 12_000_000_000)

    def test_missing_io_stat_falls_back_and_runs_candidate_with_final_accounting(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'evidence'
            scope = root / 'lane.scope'
            scope.mkdir()
            argv = ['gpu-host.py', '--evidence', str(out), '--assets', tmp, '--package', 'candidate.deb',
                    '--source', tmp, '--soak-corpus', tmp]
            report = {'journeys': [], 'fullSelection': False}
            with patch.object(sys, 'argv', argv), patch.object(host, 'accounting_scope', return_value=scope), \
                    patch.object(host, 'owner_metadata', return_value=[]), \
                    patch.object(host, 'mount_device', return_value='259:2'), \
                    patch.object(gpu, 'device_writes', side_effect=[1000, 5000, 5000]), \
                    patch.object(host, 'run_attempt', return_value=(report, False)) as attempt:
                host.main()
            attempt.assert_called_once()
            writes = json.loads((out / 'write-volume.json').read_text())
            self.assertEqual(writes['writtenBytes'], 4000)
            self.assertEqual(writes['budgetBytes'], 60_000_000_000)
            self.assertEqual(writes['measurementKind'], 'device-upper-bound')
            self.assertIn('unrelated host', writes['limit'])
            self.assertIsNone(writes['scope'])
            self.assertIn('io.stat', writes['fallbackReason'])
            self.assertEqual(report['deviceWriteUpperBound']['writtenBytes'], 4000)
            self.assertTrue(report['deviceWriteUpperBound']['informationalOnly'])
            self.assertNotIn('scopeWrites', report)

    def test_journey_counter_falls_back_to_private_root_device(self):
        with patch.dict(os.environ, QUAL_GPU_CGROUP='', QUAL_GPU_BLOCK_DEVICE='259:2'), \
                patch.object(gpu, 'device_writes', return_value=12000), \
                patch.object(gpu, 'cgroup_writes') as scope_counter:
            result = gpu.Run.__new__(gpu.Run).write_counters()
            self.assertEqual(result['writtenBytes'], 12000)
            self.assertIn('upper bound', result['source'])
            scope_counter.assert_not_called()



if __name__ == '__main__':
    unittest.main()
