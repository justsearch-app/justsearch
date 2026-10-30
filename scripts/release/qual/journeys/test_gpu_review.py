"""W4c review repairs: owner isolation, release diffs, ingestion and crashes.

S3/S22: exact package bytes, private state, no inherited developer runtime.
S14: "Soak J9: 60 min on the GPU with long documents, steady progress, no crash loop, memory under its guard; only when indexing/enrichment/inference changed".
"""
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
from test_gpu import progress_status
from test_gpu_isolation import host


class ReviewTests(unittest.TestCase):
    def test_bindings_reject_owner_state_ancestors_descendants_and_aliases(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / 'owner'
            home.mkdir()
            allowed = home / '.local/bin/tool'
            allowed.parent.mkdir(parents=True)
            allowed.touch()
            self.assertTrue(host.assert_home_bindings(home, [allowed]))
            for relative in ('.', '.local', '.local/share', '.config', '.cache',
                             '.justsearch', '.justsearch/cache',
                             '.local/share/io.justsearch.shell', '.config/io.justsearch.other',
                             '.cache/io.justsearch.shell/cache'):
                with self.subTest(path=relative), self.assertRaisesRegex(runner.Infrastructure, 'owner'):
                    host.assert_home_bindings(home, [allowed, home / relative])
            alias = Path(tmp) / 'alias'
            alias.symlink_to(home / '.justsearch', target_is_directory=True)
            with self.assertRaisesRegex(runner.Infrastructure, 'owner'):
                host.assert_home_bindings(home, [allowed, alias])

    def test_tools_restore_files_without_binding_their_parents(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / 'owner'
            root = home / 'lane'
            evidence = home / 'evidence/attempt-1'
            evidence.mkdir(parents=True)
            root.mkdir()
            tool = home / '.local/bin/scrot'
            tool.parent.mkdir(parents=True)
            tool.touch()
            driver = home / 'tauri-driver'
            driver.touch()
            def environment(*args, **kwargs):
                (root / 'bin').mkdir()
                (root / 'bin/scrot').symlink_to(tool)
                return {'PATH': str(root / 'bin')}
            captured = []
            def sandbox(root, evidence, readonly, command):
                captured.extend(readonly)
                raise runner.Infrastructure('fixture stopped before launch')
            args = types.SimpleNamespace(development_resume_root=None, write_start={'scope': '/fixture.scope', 'device': 'fixture', 'startBytes': 0}, driver=str(driver),
                development_pack_mirror=None, source=home / 'source', assets=home / 'assets',
                package='fixture.deb', soak_corpus=home / 'corpus', tool_resource=[], journeys=['J1'],
                development_mirror_port=8769, write_budget_bytes=2000000000)
            with patch.object(host, 'private_root') as private, \
                    patch.object(gpu, 'private_environment', side_effect=environment), \
                    patch.object(gpu, 'resource_sample', return_value={'available': True}), \
                    patch.object(host.shutil, 'which', side_effect=lambda name, **kw: name if '/' in name else str(tool)), \
                    patch.object(host.subprocess, 'run'), \
                    patch.object(host.subprocess, 'check_output', side_effect=['.git', '1']), \
                    patch.object(host, 'sandbox_command', side_effect=sandbox), \
                    patch.object(runner.Target, 'stop'):
                private.return_value.__enter__.return_value = root
                host.run_attempt(args, [], evidence, 1, home / 'marker')
            self.assertIn(tool, captured)
            self.assertIn(driver, captured)
            self.assertNotIn(home, captured, 'A tool directly in home must not expose home')
            self.assertNotIn(home / '.local', captured, 'bin tool must not expose .local')
            self.assertNotIn(tool.parent, captured)

    def test_sandbox_root_is_readonly_and_other_runtime_sockets_hidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            command = host.sandbox_command(root, root / 'evidence', [], ['/bin/true'])
            triples = [command[i:i+3] for i in range(len(command)-2)]
            self.assertIn(['--ro-bind', '/', '/'], triples)
            self.assertNotIn(['--dev-bind', '/', '/'], triples)
            self.assertTrue(any(row[-1] == '/run' and row[0] == '--ro-bind' for row in triples))

    def test_all_production_module_main_sources_trigger_soak(self):
        for module in sorted((runner.HERE.parents[3] / 'modules').iterdir()):
            if (module / 'src/main').exists():
                with self.subTest(module=module.name):
                    self.assertTrue(gpu.soak_relevant([f'modules/{module.name}/src/main/java/OnlyChange.java']))
        for path in ('modules/indexing/src/main/java/io/justsearch/IndexingCoordinator.java',
                     'modules/adapters-lucene/src/main/java/io/justsearch/SchemaFields.java',
                     'modules/reranker/src/main/java/io/justsearch/CrossEncoderReranker.java'):
            with self.subTest(path=path):
                self.assertTrue(gpu.soak_relevant([path]))
        self.assertFalse(gpu.soak_relevant(['modules/indexing/src/test/java/OnlyChange.java']))

    def test_tagged_source_uses_previous_release_and_refuses_without_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)
            def git(*args):
                return subprocess.check_output(['git', '-C', tmp, *args], text=True).strip()
            git('init', '-q')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'user.name', 'Fixture')
            (source / 'README').write_text('previous')
            git('add', '.')
            git('commit', '-qm', 'previous')
            git('tag', 'v1.0.0')
            changed = source / 'modules/indexing/src/main/java/IndexingCoordinator.java'
            changed.parent.mkdir(parents=True)
            changed.write_text('candidate')
            git('add', '.')
            git('commit', '-qm', 'candidate')
            git('tag', '-a', 'v1.1.0', '-m', 'candidate')
            git('tag', 'v1.1.1')  # All aliases pointing at candidate are excluded.
            decision = gpu.soak_decision(source)
            self.assertEqual(decision['lastRelease'], 'v1.0.0')
            self.assertTrue(decision['run'])
            git('tag', '-d', 'v1.0.0')
            with self.assertRaisesRegex(runner.Infrastructure, 'last-release'):
                gpu.soak_decision(source)
            with self.assertRaisesRegex(runner.Infrastructure, 'candidate itself'):
                gpu.soak_decision(source, 'v1.1.0')

    def test_idle_app_does_not_pass_the_soak_without_long_corpus_ingestion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'runtime').mkdir()
            (root / 'runtime/manifest.json').write_text(json.dumps({'pid': 123, 'instanceId': 'engine'}))
            (root / 'logs').mkdir()
            (root / 'logs/engine.log').touch()
            run = Mock(args=types.SimpleNamespace(soak_corpus=root), data=tmp, out=root, facts={}, run_id='idle')
            run.require.side_effect = lambda name: {'run': True} if name == 'soakDecision' else True
            def api(path, body=None):
                if path == '/api/status':
                    return {**progress_status(13, 0), 'memoryUsedBytes': 100, 'memoryMaxBytes': 200}
                return {'onnxFeatures': [{'id': name, 'modelActive': True, 'executionProvider': 'cuda',
                                         'gpuFallback': False} for name in ('embed', 'splade')]}
            run.api.side_effect = api
            clock = [0]
            def sleep(seconds):
                clock[0] += seconds
            with patch.dict(os.environ, QUAL_GPU_SOAK_MARKER=str(root / 'marker')), \
                    patch('corpus.verify_soak', return_value={'longDocuments': 6000}), \
                    patch.object(gpu.time, 'monotonic', side_effect=lambda: clock[0]), \
                    patch.object(gpu.time, 'sleep', side_effect=sleep), \
                    patch.object(gpu, 'worker_cuda', side_effect=lambda *a: {'maps': 'maps', 'computeMemoryMiB': 20, 'libraries': ['cuda']}):
                with self.assertRaisesRegex(runner.ProductFailure, r'long corpus.*13.*60'):
                    gpu.soak(run)
            self.assertLessEqual(clock[0], 630)

    def test_private_downloaded_runtime_crash_is_product_with_both_pid_namespaces(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'evidence'
            out.mkdir()
            (out / 'summary.json').write_text(json.dumps({'journeys': [], 'fullSelection': False}))
            exe = root / 'data/native-bin/llama-server/variants/cuda12/llama-server'
            exe.parent.mkdir(parents=True)
            exe.touch()
            entry = {'COREDUMP_EXE': str(exe), 'COREDUMP_PID': 50123, 'COREDUMP_SIGNAL': '11',
                     'COREDUMP_PROC_STATUS': 'Pid:\t50123\nPPid:\t50001\nNSpid:\t50123\t123\n',
                     'COREDUMP_PROC_STAT': '50123 (llama-server) S 50001 ' + '0 ' * 17 + '9988'}
            with patch.dict(os.environ, QUAL_GPU_ROOT=tmp), patch.object(host.subprocess, 'run',
                    return_value=Mock(returncode=0, stdout=json.dumps([entry]), stderr='')):
                host.collect_crashes(out, 'today')
            report = json.loads((out / 'summary.json').read_text())
            self.assertTrue(any(c['status'] == 'failed-product' for j in report['journeys'] for c in j['checks']), report)
            row = json.loads((out / 'gpu-crashes.json').read_text())[0]
            self.assertEqual(row['fields']['Pid'], '50123')
            self.assertEqual(row['crashIdentity'], {'hostPid': 50123, 'namespacePids': [50123, 123], 'ticks': '9988'})

    def test_sigterm_cleanup_ignores_process_vanishing_before_cmdline_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'evidence'
            out.mkdir()
            (root / 'bindings.json').write_text('[]')
            (root / 'stop-reason').write_text('Interrupted by SIGTERM')
            vanished = {'pid': 2**30, 'ticks': '1'}
            child = Mock(poll=Mock(return_value=None), wait=Mock(return_value=0))
            with patch.dict(os.environ, QUAL_GPU_ROOT=tmp, QUAL_GPU_EVIDENCE=str(out), QUAL_GPU_SESSION='fixture'), \
                    patch.object(host, 'assert_home_bindings', return_value=True), \
                    patch.object(host.os, 'statvfs', return_value=types.SimpleNamespace(f_flag=os.ST_RDONLY)), \
                    patch.object(host, 'processes', side_effect=[[vanished], [vanished], [], [], [], []]), \
                    patch.object(host.subprocess, 'Popen', return_value=child), \
                    patch.object(runner.Target, 'stop'):
                self.assertEqual(host.sandbox_session([]), 0)
            self.assertEqual(json.loads((out / 'sandbox.json').read_text())['processesRemaining'], [])

    def test_sigterm_entrypoint_runs_cleanup_and_accounting(self):
        # Execute the actual entrypoint with only main replaced by a waiting
        # fixture: on HEAD default SIGTERM bypasses its finally block.
        tree = ast.parse((runner.HERE / 'gpu-host.py').read_text())
        entrypoint = ast.unparse(tree.body[-1])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            program = f'''
import importlib.util, os, pathlib, sys, time, ctypes
sys.path.insert(0, {str(runner.HERE)!r})
spec=importlib.util.spec_from_file_location('host', {str(runner.HERE / 'gpu-host.py')!r})
host=importlib.util.module_from_spec(spec); spec.loader.exec_module(host)
globals().update({{k:v for k,v in vars(host).items() if k != '__name__'}})
def main():
    try:
        pathlib.Path({str(root / 'ready')!r}).touch()
        while True: time.sleep(.01)
    except BaseException:
        return 1
    finally:
        pathlib.Path({str(root / 'accounted')!r}).touch()
{entrypoint}
'''
            child = subprocess.Popen([sys.executable, '-c', program], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 10
                while not (root / 'ready').exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue((root / 'ready').exists())
                child.send_signal(signal.SIGTERM)
                _, err = child.communicate(timeout=10)
                self.assertTrue((root / 'accounted').exists(), f'SIGTERM skipped cleanup/accounting: {child.returncode}; {err!r}')
                self.assertEqual(child.returncode, 1)
            finally:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
