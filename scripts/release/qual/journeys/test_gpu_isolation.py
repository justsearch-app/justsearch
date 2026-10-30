"""W4c isolation: owner's saved state and desktop are unreachable by the lane."""
import importlib.util
import json
import os
import socket
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import types
import unittest
from unittest.mock import patch

import gpu
import runner

spec = importlib.util.spec_from_file_location('gpu_host', runner.HERE / 'gpu-host.py')
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class IsolationTests(unittest.TestCase):
    def test_runner_accepts_only_gpu_wrapper_proof_files_in_existing_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'attempt-1'
            out.mkdir()
            for name in ('sandbox-command.json', 'sandbox.json'):
                (out / name).write_text('{}')
            args = types.SimpleNamespace(evidence=out, vm=None, lane='gpu-linux')
            run = runner.Run(args, {})
            self.assertEqual(run.out, out)
            self.assertEqual(json.loads((out / 'sandbox.json').read_text()), {})
            # A previous attempt or an unrelated file must never be reused.
            (out / 'summary.json').write_text('{}')
            with self.assertRaises(FileExistsError):
                runner.Run(args, {})
            (out / 'summary.json').unlink()
            args.lane = 'hosted-linux'
            with self.assertRaises(FileExistsError):
                runner.Run(args, {})
            args.lane = 'gpu-linux'
            (out / 'sandbox.json').unlink()
            (out / 'sandbox.json').symlink_to(out / 'sandbox-command.json')
            with self.assertRaises(FileExistsError):
                runner.Run(args, {})

    def test_private_optimized_store_is_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = gpu.private_environment(os.environ, Path(tmp), 'unit')
            self.assertEqual(env['JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR'], tmp + '/cache/ort-optimized')

    def test_metadata_snapshot_never_reads_file_contents_or_follows_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'cache').mkdir()
            (root / 'cache/file').write_text('private')
            (root / 'link').symlink_to('/proc/self/environ')
            with patch.object(Path, 'read_bytes', side_effect=AssertionError('contents read')):
                before = host.owner_tree(root)
                self.assertEqual(before, host.owner_tree(root))
                (root / 'cache/added').touch()
                self.assertNotEqual(before, host.owner_tree(root))
            self.assertEqual(next(r for r in before if r['path'] == 'cache/file')['size'], 7)

    def test_diagnostic_environment_probe_never_decides_desktop_isolation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / '42').mkdir()
            (root / '42/environ').touch()
            (root / '42/stat').write_text('42 (test) S 1 ' + '0 ' * 17 + '99')
            with patch.object(Path, 'read_bytes', side_effect=PermissionError('denied')):
                self.assertEqual(host.processes('unit', proc=root), [])
                self.assertEqual(host.processes('unit', proc=root, owned={(42, '99')}), [])

    def test_mount_device_and_diskstats_use_partition_sectors_not_other_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            stats = Path(tmp) / 'diskstats'
            stats.write_text('259 0 nvme0n1 0 0 0 0 0 0 999 0 0 0 0\n'
                             '259 2 nvme0n1p2 0 0 0 0 0 0 7 0 0 0 0\n')
            self.assertEqual(gpu.device_writes('259:2', stats), 7 * 512)
            stats.write_text(stats.read_text().replace('0 7 0', '0 12 0'))
            self.assertEqual(gpu.device_writes('259:2', stats) - 7 * 512, 5 * 512)
            with self.assertRaises(runner.Infrastructure):
                gpu.device_writes('8:99', stats)
            with patch.object(host.subprocess, 'check_output', return_value='259:2\n') as command, patch.object(gpu, 'device_writes'):
                self.assertEqual(host.mount_device(Path(tmp)), '259:2')
                self.assertIn('MAJ:MIN', command.call_args.args[0])

    def test_structural_desktop_proof_requires_only_lane_socket_and_bus(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / '.X11-unix').mkdir()
            (root / '.X11-unix/X99').touch()
            runtime = root / 'runtime'
            runtime.mkdir()
            with socket.socket(socket.AF_UNIX) as bus:
                bus.bind(str(root / 'bus'))
                env = {'DISPLAY': ':99', 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + str(root / 'bus')}
                self.assertTrue(host.desktop_proof(env, tmp=root, runtime=runtime)['ownerSocketsAbsent'])
                (runtime / 'wayland-0').touch()
                self.assertFalse(host.desktop_proof(env, tmp=root, runtime=runtime)['ownerSocketsAbsent'])
                (runtime / 'wayland-0').unlink()
                (root / '.X11-unix/X0').touch()
                self.assertFalse(host.desktop_proof(env, tmp=root, runtime=runtime)['ownerSocketsAbsent'])

    def test_activated_state_is_kept_for_development_recovery_then_removed(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(host, 'PRIVATE_ROOTS', Path(tmp)):
            with host.private_root() as root:
                (root / 'activated-checkpoint.json').write_text('{}')
                (root / 'keep-state').touch()
            self.assertTrue(root.exists())
            with host.private_root(root) as recovered:
                self.assertEqual(recovered, root)
                self.assertFalse((root / 'keep-state').exists())
            self.assertFalse(root.exists())
            with self.assertRaises(runner.Infrastructure):
                with host.private_root(Path('/tmp/unrelated')):
                    pass

    def test_resume_j3_never_repeats_download_or_activation(self):
        from unittest.mock import Mock
        run = Mock()
        run.args.resume = Path('/private/activated-checkpoint.json')
        with patch.object(gpu, 'gpu_installed_checks', return_value='remaining-checks') as remaining:
            self.assertEqual(gpu.gpu_install(run), 'remaining-checks')
            remaining.assert_called_once_with(run)
            run.wd.click.assert_not_called()
            run.api.assert_not_called()

    def test_desktop_proof_is_saved_before_runner_starts(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            host.save(root / 'sandbox.json', {'candidateReadOnly': True})
            env = dict(os.environ, QUAL_GPU_ROOT=tmp, QUAL_GPU_EVIDENCE=tmp)
            def invoke(command, **kwargs):
                self.assertTrue(json.loads((root / 'sandbox.json').read_text())['desktop']['ownerSocketsAbsent'])
                return Mock(returncode=0)
            with patch.dict(os.environ, env), patch.object(host, 'desktop_proof', return_value={'ownerSocketsAbsent': True}), patch.object(host.subprocess, 'run', side_effect=invoke), patch.object(host.subprocess, 'Popen', return_value=Mock(poll=Mock(return_value=0))):
                self.assertEqual(host.session([]), 1)  # No summary in this fixture.

    def test_manifest_engine_permission_error_is_infrastructure(self):
        run = runner.Run.__new__(runner.Run)
        run.data = '/private/data'
        run.run_id = 'unit'
        class Target:
            def json(self, source, args):
                # Exercise the actual subprocess probe, rather than matching its text.
                prefix = '''
from pathlib import Path
import os
Path.read_text=lambda self: '{"pid":42}'
Path.read_bytes=lambda self: (_ for _ in ()).throw(PermissionError('denied'))
os.kill=lambda *a: None
'''
                result = subprocess.run([sys.executable, '-c', prefix + source, json.dumps(args)],
                                        capture_output=True, text=True, check=True)
                return json.loads(result.stdout)
        run.target = Target()
        with self.assertRaisesRegex(runner.Infrastructure, 'PermissionError'):
            run.api('/api/status')

    def test_owned_probe_skips_unproven_permission_error_and_fails_for_known_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / '42/environ'
            path.parent.mkdir()
            path.touch()
            path.with_name('stat').write_text('42 (test) S 1 ' + '0 ' * 17 + '99')
            target = runner.Target()
            target.env['QUAL_JOURNEY_RUN'] = 'unit'
            def execute(source, value, **kwargs):
                prefix = f'''\nfrom pathlib import Path
Path.glob=lambda self,pattern:[Path({str(path)!r})]
Path.read_bytes=lambda self: (_ for _ in ()).throw(PermissionError('denied'))
'''
                return json.loads(target.run([sys.executable, '-c', prefix + source, json.dumps(value)],
                                             desktop=False).stdout)
            with patch.object(target, 'json', side_effect=execute):
                self.assertEqual(target.owned(), [])
                target.app_processes[(42, '99')] = {'pid': 42, 'ticks': '99'}
                with self.assertRaisesRegex(runner.Infrastructure, 'Cannot read owned process 42'):
                    target.owned()

    def test_bubblewrap_hides_owner_home_tmp_runtime_and_foreign_processes(self):
        with tempfile.TemporaryDirectory(prefix='w4c-sandbox-unit-') as tmp:
            root = Path(tmp)
            (root / 'empty-home').mkdir()
            (root / 'work/package').mkdir(parents=True)
            (root / 'evidence').mkdir()
            (root / 'runtime').mkdir()
            source = runner.HERE.parents[3]
            program = '''
import json,os,pwd
from pathlib import Path
home=Path(pwd.getpwuid(os.getuid()).pw_dir)
assert not (home/'.justsearch').exists()
assert not (home/'.local/share/io.justsearch.shell').exists()
assert not Path('/tmp/.X11-unix').exists()
assert not Path('/run/user',str(os.getuid()),'bus').exists()
assert os.statvfs(os.environ['PACKAGE']).f_flag & os.ST_RDONLY
assert len(list(Path('/proc').glob('[0-9]*'))) < 20
print(json.dumps({'pidNamespace':os.readlink('/proc/self/ns/pid')}))
'''
            # Exercise the supervisor's own assertion with HOME different from
            # Java's passwd-derived user.home, before launching any lane tool.
            program += textwrap.dedent(f'''
                import importlib.util,sys
                from unittest.mock import Mock,patch
                sys.path.insert(0,{str(runner.HERE)!r})
                spec=importlib.util.spec_from_file_location('host',{str(runner.HERE / 'gpu-host.py')!r})
                host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
                with patch.object(host.subprocess,'Popen',return_value=Mock(poll=Mock(return_value=0),wait=Mock(return_value=0))), patch.object(host.runner.Target,'stop'):
                    assert host.sandbox_session([])==0
            ''')
            (root / 'bindings.json').write_text(json.dumps([str(source)]))
            (root / 'home').mkdir()
            command = host.sandbox_command(root, root / 'evidence',
                [source, root / 'work/package'], [sys.executable, '-c', program])
            result = subprocess.run(command, env=dict(os.environ, PACKAGE=str(root / 'work/package'),
                HOME=str(root / 'home'), QUAL_GPU_ROOT=str(root), QUAL_GPU_EVIDENCE=str(root / 'evidence'), QUAL_GPU_SESSION='unit'),
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotEqual(json.loads(result.stdout)['pidNamespace'], os.readlink('/proc/self/ns/pid'))

    def test_home_assertion_rejects_an_unlisted_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            bound = home / 'dev/lane'
            bound.mkdir(parents=True)
            (bound / 'fixture').touch()
            self.assertTrue(host.assert_home_bindings(home, [bound]))
            (home / 'unexpected').touch()
            with self.assertRaisesRegex(runner.Infrastructure, 'unexpected'):
                host.assert_home_bindings(home, [bound])

    def test_external_guard_does_not_claim_reused_root_pid(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / '42/stat'
            path.parent.mkdir()
            path.write_text('42 (foreign) S 1 ' + '0 ' * 17 + '99')
            with patch.object(Path, 'glob', return_value=[path]):
                self.assertEqual(host.descendants((42, 'old'), {}), [])
                self.assertEqual([r['pid'] for r in host.descendants((42, '99'), {})], [42])


if __name__ == '__main__':
    unittest.main()
