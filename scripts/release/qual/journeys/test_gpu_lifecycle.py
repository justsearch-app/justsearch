"""S3/S22 isolation: private bus works read-only; writers exit before removal."""
import ast
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import Mock, patch

import gpu
import runner
from test_gpu_isolation import host


class LifecycleTests(unittest.TestCase):
    def test_private_bus_activation_works_inside_readonly_sandbox(self):
        # Capture the bus invocation actually used by the namespace supervisor,
        # then run the verifier's activation probe through that real interface.
        with tempfile.TemporaryDirectory(prefix='w4c-bus-test-') as tmp:
            root = Path(tmp)
            out = root / 'evidence'
            out.mkdir()
            (root / 'empty-home').mkdir()
            (root / 'bindings.json').write_text('[]')
            env = gpu.private_environment(os.environ, root, 'bus-regression')
            env.update(QUAL_GPU_ROOT=tmp, QUAL_GPU_EVIDENCE=str(out))
            (out / 'sandbox-command.json').write_text('[]')
            def start(command):
                # The runner accepts exactly the two pre-candidate proofs.
                # Extra evidence such as session.conf must be copied later.
                run = runner.Run(types.SimpleNamespace(evidence=out, vm=None, lane='gpu-linux'), {})
                self.assertEqual(run.out, out)
                return Mock(poll=Mock(return_value=0), wait=Mock(return_value=0))
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(host, 'assert_home_bindings', return_value=True), \
                    patch.object(host.os, 'statvfs', return_value=types.SimpleNamespace(f_flag=os.ST_RDONLY)), \
                    patch.object(host, 'processes', return_value=[]), \
                    patch.object(runner.Target, 'stop'), \
                    patch.object(host.subprocess, 'Popen', side_effect=start) as launch:
                self.assertEqual(host.sandbox_session([]), 0)
            command = launch.call_args.args[0]
            bus = command[command.index('dbus-run-session'):command.index('--') + 1]
            command = host.sandbox_command(root, out, [runner.HERE.parents[3]],
                [*bus, '/usr/bin/dbus-update-activation-environment', 'PATH'])
            # A shared before checkout can live entirely outside passwd home,
            # so no under-home destination creates this mount source for it.
            (root / 'empty-home').mkdir(exist_ok=True)
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_state_removal_retries_once_after_wait_and_reports_remaining_paths(self):
        for persistent in (False, True):
            with self.subTest(persistent=persistent), tempfile.TemporaryDirectory() as tmp:
                base = Path(tmp)
                out = base / 'evidence'
                remove = host.shutil.rmtree
                calls = []
                def removal(path):
                    self.assertTrue(wait.called, 'Deletion preceded the process exit barrier')
                    calls.append(path)
                    if persistent or len(calls) == 1:
                        raise OSError(39, 'Directory not empty', str(path))
                    remove(path)
                with patch.object(host, 'PRIVATE_ROOTS', base), \
                        patch.object(host, 'stop_lane_processes', return_value={'remainingProcesses': []}) as wait, \
                        patch.object(host.shutil, 'rmtree', side_effect=removal):
                    try:
                        with host.private_root(evidence=out) as state:
                            (state / 'remaining').touch()
                    except runner.Infrastructure as exc:
                        self.assertTrue(persistent)
                        self.assertIn(str(state / 'remaining'), str(exc))
                    else:
                        self.assertFalse(persistent)
                self.assertEqual(len(calls), 2)
                cleanup = json.loads((out / 'private-state-cleanup.json').read_text())
                self.assertEqual(bool(cleanup['remainingPaths']), persistent)
                self.assertEqual(state.exists(), persistent)

    def test_sigterm_stops_extraction_descendants_before_removing_state(self):
        self.stop_extraction(signal.SIGTERM)

    def test_sigint_stops_extraction_descendants_before_removing_state(self):
        self.stop_extraction(signal.SIGINT)

    def stop_extraction(self, signum):
        # Run the actual entrypoint/subreaper and private_root lifecycle. This
        # writer models tar: it survives its parent and ignores SIGTERM, so the
        # host must wait, escalate and reap before attempting rmtree.
        entrypoint = ast.unparse(ast.parse((runner.HERE / 'gpu-host.py').read_text()).body[-1])
        with tempfile.TemporaryDirectory(prefix='w4c-stop-test-') as tmp:
            root = Path(tmp)
            writer = '''
import os,pathlib,signal,sys,time
signal.signal(signal.SIGTERM,signal.SIG_IGN)
pathlib.Path(sys.argv[2]).write_text(str(os.getpid()))
while True:
    folder=pathlib.Path(sys.argv[1]); folder.mkdir(parents=True,exist_ok=True)
    (folder/'writing').write_text('still extracting')
    time.sleep(.005)
'''
            extractor = f'''
import pathlib,signal,subprocess,sys,time
def term(signum,frame):
    pathlib.Path(sys.argv[3]).write_text('SIGTERM')
    raise SystemExit(0)
signal.signal(signal.SIGTERM,term)
subprocess.Popen([sys.executable,'-c',{writer!r},*sys.argv[1:3]])
while True: time.sleep(.1)
'''
            program = f'''
import importlib.util,os,pathlib,sys,time,ctypes
sys.path.insert(0,{str(runner.HERE)!r})
spec=importlib.util.spec_from_file_location('host',{str(runner.HERE / 'gpu-host.py')!r})
host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
globals().update({{k:v for k,v in vars(host).items() if k!='__name__'}})
PRIVATE_ROOTS=pathlib.Path({tmp!r})
host.PRIVATE_ROOTS=PRIVATE_ROOTS
def main():
    try:
        with host.private_root() as state:
            pathlib.Path({str(root / 'state')!r}).write_text(str(state))
            subprocess.run([sys.executable,'-c',{extractor!r},str(state/'package'),{str(root / 'writer-pid')!r},{str(root / 'extractor-term')!r}],check=True)
    except BaseException as exc:
        pathlib.Path({str(root / 'error')!r}).write_text(str(exc))
        return 1
    finally:
        pathlib.Path({str(root / 'accounted')!r}).touch()
{entrypoint}
'''
            child = subprocess.Popen([sys.executable, '-c', program], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            writer_identity = None
            try:
                deadline = time.monotonic() + 10
                while not (root / 'writer-pid').exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue((root / 'writer-pid').exists())
                pid = int((root / 'writer-pid').read_text())
                ticks = Path('/proc', str(pid), 'stat').read_text().rsplit(') ', 1)[1].split()[19]
                writer_identity = {'pid': pid, 'ticks': ticks}
                child.send_signal(signum)
                try:
                    _, err = child.communicate(timeout=18)
                except subprocess.TimeoutExpired:
                    self.fail('Host waited for a live extraction descendant after removing private state')
                self.assertEqual(child.returncode, 1, err)
                self.assertTrue((root / 'accounted').exists())
                self.assertEqual((root / 'extractor-term').read_text(), 'SIGTERM',
                                 'The immediate extraction parent must get SIGTERM before exception unwinding')
                self.assertFalse(Path((root / 'state').read_text()).exists(), (root / 'error').read_text())
                self.assertFalse(Path('/proc', str(pid)).exists(), 'Extraction descendant was not reaped')
            finally:
                if writer_identity:
                    runner.Target().stop([writer_identity])
                if child.poll() is None:
                    child.kill()
                child.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
