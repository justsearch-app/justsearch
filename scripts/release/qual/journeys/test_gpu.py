"""W4c: GPU evidence, interruption and soak counterexamples; no shared GPU used.

S3 (GPU host part): "Journeys J1-J9 run against the draft's exact bytes on hosted runners, the two clean VMs and the GPU host, and produce one result summary that separates passed, failed, skipped by decision (Windows GPU) and unavailable".
S12: "Linux with a supported NVIDIA GPU: Install AI fetches Linux GPU packs and chat answers with sources on the GPU; preview, install and readiness agree on what the plan includes (isIncludedByPlan native-target gate) (does not block the release)".
S14: "Soak J9: 60 min on the GPU with long documents, steady progress, no crash loop, memory under its guard; only when indexing/enrichment/inference changed".
S22: "Journeys run on clean installs without a developer JDK, models or inherited runtime paths; a missing download, skipped or unavailable check is reported as such, never as a pass".
"""
import json
from pathlib import Path
import tempfile
import types
import unittest
import importlib.util
import os
import subprocess
import sys
import zipfile
from unittest.mock import Mock, patch

try:
    import gpu
except ModuleNotFoundError:
    gpu = None
import runner


class GpuTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(gpu, 'gpu-linux lane implementation is absent')

    def test_definition_enables_only_gpu_journeys(self):
        definition = json.loads((runner.HERE / 'journeys.json').read_text())
        active = {j['id'] for j in definition['journeys'] if any('gpu-linux' in c['lanes'] for c in j['checks'])}
        self.assertEqual(active, {'J1', 'J2', 'J3', 'J4', 'J9'})
        self.assertIn('S12', definition['scenarios'])
        self.assertIn('S14', definition['scenarios'])
        self.assertNotIn('gpu-linux', definition['scope']['unavailable'])

    def test_cuda_libraries_must_resolve_inside_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / 'pack'
            pack.mkdir()
            library = pack / 'libcudnn.so.9'
            library.touch()
            raw = f'1000-2000 r-xp 0 00:00 1 {library}\n'
            self.assertEqual(gpu.cuda_libraries(raw, pack), [str(library)])
            for bad in (root / 'libcudnn.so.9', root / 'libnvrtc.so.12', root / 'libcublas.so.12',
                        root / 'libnvJitLink.so.12', root / 'libcusparse.so.12', root / 'libcufft.so.11',
                        root / 'libcurand.so.10', root / 'libcusolver.so.11', root / 'libcupti.so.12'):
                bad.touch()
                with self.assertRaisesRegex(runner.ProductFailure, 'outside installed pack'):
                    gpu.cuda_libraries(raw + f'2000-3000 r-xp 0 00:00 2 {bad}\n', pack)

    def test_symlink_and_deleted_library_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack = root / 'pack'
            pack.mkdir()
            outside = root / 'libcudart.so.12'
            outside.touch()
            (pack / outside.name).symlink_to(outside)
            with self.assertRaises(runner.ProductFailure):
                gpu.cuda_libraries(f'1-2 r-xp 0 00:00 1 {pack / outside.name}', pack)
            with self.assertRaises(runner.ProductFailure):
                gpu.cuda_libraries(f'1-2 r-xp 0 00:00 1 {outside} (deleted)', root)

    def test_driver_library_is_os_owned_but_empty_maps_are_not_gpu_proof(self):
        self.assertEqual(gpu.cuda_libraries('1-2 r-xp 0 00:00 1 /usr/lib/libcuda.so.1', '/pack'), [])
        with self.assertRaises(runner.ProductFailure):
            gpu.require_cuda_maps({'engine': [], 'llama': ['/pack/libcudart.so.12']})
        gpu.require_cuda_maps({'engine': ['/pack/libcudnn.so.9'], 'llama': ['/pack/libcudart.so.12']})

    def test_resource_start_and_interrupt_thresholds(self):
        own = {17}
        self.assertTrue(gpu.resources_available(14 * gpu.GIB, [(17, 9000), (18, 1024)], own, starting=True))
        self.assertFalse(gpu.resources_available(14 * gpu.GIB - 1, [], own, starting=True))
        self.assertTrue(gpu.resources_available(12 * gpu.GIB, [], own, starting=False))
        self.assertFalse(gpu.resources_available(12 * gpu.GIB - 1, [], own, starting=False))
        self.assertFalse(gpu.resources_available(20 * gpu.GIB, [(18, 1025)], own, starting=False))

    def test_nvidia_xml_includes_graphics_processes(self):
        xml = '<nvidia_smi_log><gpu><processes><process_info><pid>18</pid><type>G</type><used_memory>2048 MiB</used_memory></process_info></processes></gpu></nvidia_smi_log>'
        self.assertEqual(gpu.gpu_process_memory(xml), [(18, 2048)])
        with self.assertRaises(runner.Infrastructure):
            gpu.gpu_process_memory(xml.replace('2048 MiB', 'N/A'))

    def test_scrubbed_environment_keeps_no_inherited_runtime_or_desktop(self):
        source = {'HOME': '/owner', 'PATH': '/usr/bin:/jdk/bin', 'DISPLAY': ':0', 'WAYLAND_DISPLAY': 'wayland-0',
                  'DBUS_SESSION_BUS_ADDRESS': 'owner', 'LD_PRELOAD': 'bad', 'JAVA_TOOL_OPTIONS': '-Djava.library.path=/bad',
                  'JDK_JAVA_OPTIONS': 'bad', '_JAVA_OPTIONS': 'bad', 'JUSTSEARCH_SERVER_EXE': '/bad',
                  'JUSTSEARCH_DATA_DIR': '/owner/data', 'JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH': '/bad'}
        with tempfile.TemporaryDirectory() as tmp:
            env = gpu.private_environment(source, Path(tmp), 'marker')
            self.assertNotIn('WAYLAND_DISPLAY', env)
            self.assertNotIn('DBUS_SESSION_BUS_ADDRESS', env)
            self.assertNotIn('DISPLAY', env)
            for key in ('LD_PRELOAD', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JUSTSEARCH_SERVER_EXE', 'JUSTSEARCH_DATA_DIR', 'JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH'):
                self.assertNotIn(key, env)
            self.assertEqual(env['HOME'], str(Path(tmp) / 'home'))
            self.assertEqual(env['JUSTSEARCH_ORT_INTRA_OP_THREADS'], '8')
            self.assertNotIn('/jdk/bin', env['PATH'])
            self.assertEqual(subprocess.run(['git', '--version'], env=env, capture_output=True).returncode, 0)
            self.assertNotEqual(subprocess.run(['sh', '-c', 'command -v javac'], env=env, capture_output=True).returncode, 0)

    def test_relevance_names_indexing_enrichment_and_inference(self):
        for path in ('modules/worker-services/src/main/java/X.java', 'modules/app-inference/src/main/java/X.java',
                     'modules/ort-common/src/main/java/X.java', 'modules/configuration/src/main/resources/ai/model-registry.v2.json',
                     'modules/shell/src-tauri/src/lib.rs'):
            self.assertTrue(gpu.soak_relevant([path]), path)
        self.assertFalse(gpu.soak_relevant(['docs/how-to/a.md', 'scripts/release/qual/journeys/gpu.py']))
        self.assertFalse(gpu.soak_relevant(['scripts/ai/test_update_linux_gpu_registry.py', 'modules/worker-core/src/test/java/X.java']))

    def test_soak_no_gap_over_ten_minutes_and_memory_guard(self):
        progress = gpu.SoakProgress(0)
        progress.observe(0, 1, 100, 200, 'boot-one')
        progress.observe(600, 2, 100, 200, 'boot-one')
        with self.assertRaisesRegex(runner.ProductFailure, 'progress'):
            progress.observe(1201, 2, 100, 200, 'boot-one')
        for used, maximum in ((200, 200), (201, 200)):
            with self.assertRaisesRegex(runner.ProductFailure, 'memory guard'):
                gpu.SoakProgress(0).observe(0, 1, used, maximum, 'boot-one')
        with self.assertRaises(runner.Infrastructure):
            gpu.SoakProgress(0).observe(0, 1, 0, 0, 'boot-one')

    def test_soak_engine_restart_is_not_a_pass(self):
        progress = gpu.SoakProgress(0)
        progress.observe(0, 1, 100, 200, 'boot-one')
        with self.assertRaisesRegex(runner.ProductFailure, 'Engine identity'):
            progress.observe(30, 2, 100, 200, 'boot-two')

    def test_soak_rejects_short_run(self):
        progress = gpu.SoakProgress(0)
        progress.observe(0, 1, 100, 200, 'boot')
        with self.assertRaises(runner.Infrastructure):
            progress.complete(3599)
        for now in range(600, 3601, 600):
            progress.observe(now, now, 100, 200, 'boot')
        self.assertEqual(progress.complete(3600)['seconds'], 3600)

    def test_skipped_soak_uses_shared_classification_and_is_never_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'out'), vm=None, lane='gpu-linux')
            run = runner.Run(args, {'journeys': [], 'scenarios': {}})
            row = {'id': 'J9', 'checks': []}
            run.results.append(row)
            def skip():
                raise runner.SkippedByDecision('No relevant paths changed since v0.3.0')
            run.check(row, 'soak', skip)
            self.assertEqual(row['checks'][0]['status'], 'skipped-by-decision')
            self.assertFalse(run.save()['passed'])

    def test_plan_install_readiness_agree_and_no_windows_native_pack(self):
        preview = {'downloadProfile': 'GPU_FULL', 'components': [
            {'id': x, 'state': 'to-download'} for x in ('chat', 'cuda-runtime-linux', 'embedding')]}
        gpu.require_gpu_plan(preview)
        for package in ('chat', 'cuda-runtime-linux'):
            broken = json.loads(json.dumps(preview))
            broken['components'] = [c for c in broken['components'] if c['id'] != package]
            with self.assertRaises(runner.ProductFailure):
                gpu.require_gpu_plan(broken)
        with self.assertRaises(runner.ProductFailure):
            gpu.require_gpu_plan(dict(preview, downloadProfile='CPU'))
        status = {'installedFully': True, 'packages': [{'packageId': x, 'state': 'installed'} for x in ('chat', 'cuda-runtime-linux')]}
        gpu.require_gpu_install(status)
        with self.assertRaises(runner.ProductFailure):
            gpu.require_gpu_install(dict(status, packages=status['packages'] + [{'packageId': 'cuda-runtime', 'state': 'installed'}]))

    def test_backend_requires_observed_cuda(self):
        runtime = {'onnxFeatures': [{'id': x, 'modelActive': True, 'executionProvider': 'cuda', 'gpuFallback': False} for x in ('embed', 'splade')]}
        gpu.require_gpu_encoders(runtime)
        runtime['onnxFeatures'][0]['executionProvider'] = 'cpu'
        with self.assertRaises(runner.ProductFailure):
            gpu.require_gpu_encoders(runtime)

    def test_start_ai_is_a_separate_real_control_after_model_activation(self):
        run = Mock()
        run.api.side_effect = [
            {'inference': {'engineState': 'Healthy', 'chatEnabledSpec': True}},
            {'active': {'modelPath': '/private/models/chat.gguf', 'chatProfile': 'standard'}, 'activation': {'state': 'idle'}}]
        gpu.start_chat(run)
        run.wd.click.assert_called_once_with('button[aria-label="Start AI"]')
        self.assertEqual(run.api.call_args_list[0].args, ('/api/status',))

    def test_installed_runtime_paths_and_requested_gpu_layers_are_not_live_chat_readiness(self):
        status = {'inference': {'engineState': 'Healthy', 'chatEnabledSpec': True}}
        runtime = {'active': {'serverExecutablePath': '/pack/llama-server', 'gpuLayers': 99,
                              'modelPath': None, 'chatProfile': None}}
        self.assertFalse(gpu.chat_runtime_ready(status, runtime))
        runtime['active'].update(modelPath='/private/chat.gguf', chatProfile='standard')
        self.assertTrue(gpu.chat_runtime_ready(status, runtime))
        status['inference']['engineState'] = 'Starting'
        self.assertFalse(gpu.chat_runtime_ready(status, runtime))

    def test_chat_requires_answer_sources_and_terminal_success(self):
        events = [('chunk', {'text': 'The telescope is turquoise.'}), ('rag.citations', {'citations': [{'parentDocId': 'inspection-message'}]}), ('done', {})]
        gpu.require_chat_answer(events)
        for broken in (events[:-1], [events[0], events[2]], [events[1], events[2]], events + [('error', {'message': 'failed'})]):
            with self.assertRaises(runner.ProductFailure):
                gpu.require_chat_answer(broken)

    def test_development_mirror_never_qualifies_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'out'), vm=None, lane='gpu-linux',
                                         development_pack_mirror=Path(tmp))
            run = gpu.Run(args, {'journeys': [], 'scenarios': {}})
            self.assertFalse(run.save()['execution']['releaseQualification'])

    def test_loopback_registry_cannot_be_qualified_without_explicit_development_option(self):
        registry = {'packages': [{'supportingFiles': [{'downloadUrl': 'http://127.0.0.1:8769/pack.zip'}]}]}
        with self.assertRaisesRegex(runner.Infrastructure, 'explicit'):
            gpu.require_registry_urls(registry)
        with self.assertRaises(runner.Infrastructure):
            gpu.require_registry_urls({'downloadUrl': 'https://127.0.0.1/pack.zip'})
        gpu.require_registry_urls(registry, development=True)
        gpu.require_registry_urls({'packages': [{'variants': [{'downloadUrl': 'https://example.invalid/draft/pack.zip'}]}]})
        with self.assertRaises(runner.Infrastructure):
            gpu.require_registry_urls({'downloadUrl': 'https://example.invalid/TEST-ONLY-pack.zip'})

    def test_missing_ai_keeps_authored_state_restart_and_soak_unavailable(self):
        run = Mock()
        run.require.side_effect = runner.Unavailable('Default GPU AI installation did not complete')
        for check in (gpu.author_state, gpu.restart, gpu.soak, gpu.search):
            with self.assertRaises(runner.Unavailable):
                check(run)
        run.wd.execute.assert_not_called()
        run.api.assert_not_called()

    def test_irrelevant_soak_is_skipped_before_any_gpu_or_state_access(self):
        run = Mock()
        run.require.return_value = {'run': False, 'reason': 'No relevant paths changed'}
        with self.assertRaisesRegex(runner.SkippedByDecision, 'No relevant'):
            gpu.soak(run)
        run.api.assert_not_called()
        run.wd.execute.assert_not_called()

    def test_interrupted_check_never_runs_and_stays_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'out'), vm=None, lane='gpu-linux')
            run = gpu.Run(args, {'journeys': [], 'scenarios': {}})
            run.interrupted = True
            row = {'id': 'J9', 'checks': []}
            run.results.append(row)
            action = Mock()
            run.check(row, 'soak', action)
            action.assert_not_called()
            self.assertEqual(row['checks'][0]['status'], 'unavailable')
            self.assertFalse(run.save()['passed'])

    def test_resource_interruption_in_mid_check_is_infrastructure(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'out'), vm=None, lane='gpu-linux')
            run = gpu.Run(args, {'journeys': [], 'scenarios': {}})
            row = {'id': 'J9', 'checks': []}
            run.results.append(row)
            def interrupted():
                raise runner.RunStopped(gpu.INTERRUPTED)
            with patch.object(run.native, 'shot'):
                run.check(row, 'soak', interrupted)
            self.assertEqual(row['checks'][0]['status'], 'failed-infrastructure')
            self.assertEqual(row['checks'][0]['reason'], gpu.INTERRUPTED)

    def test_generated_long_formats_are_deterministic_and_reuse_rejects_tampering(self):
        import corpus
        with tempfile.TemporaryDirectory() as tmp:
            first = corpus.generate_soak(Path(tmp) / 'one', count=6)
            second = corpus.generate_soak(Path(tmp) / 'two', count=6)
            self.assertEqual(first, second)
            for suffix in ('.txt', '.md', '.html', '.csv', '.eml', '.docx'):
                self.assertTrue(any(Path(name).suffix == suffix and name.startswith('long-') for name in first['files']))
            # A small fixture is deliberately insufficient for the real J9.
            with self.assertRaisesRegex(ValueError, '6000'):
                corpus.verify_soak(Path(tmp) / 'one')
            # Hash validation, independently of the minimum corpus population.
            first['longDocuments'] = 6000
            for number in range(6, 6000):
                first['files'][f'long-{number:05d}.txt'] = first['files']['long-00000.txt']
            (Path(tmp) / 'one/soak-manifest.json').write_text(json.dumps(first))
            (Path(tmp) / 'one' / 'long-00000.txt').write_text('tampered input')
            with self.assertRaisesRegex(ValueError, 'changed'):
                corpus.verify_soak(Path(tmp) / 'one')

    def test_pid_marker_cleanup_does_not_stop_a_foreign_process_or_reused_identity(self):
        spec = importlib.util.spec_from_file_location('gpu_host', runner.HERE / 'gpu-host.py')
        host = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(host)
        own = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(60)'], env=dict(os.environ, QUAL_GPU_SESSION='w4c-unit-own'))
        foreign = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(60)'], env=dict(os.environ, QUAL_GPU_SESSION='w4c-unit-foreign'))
        try:
            rows = host.processes('w4c-unit-own')
            self.assertEqual([r['pid'] for r in rows], [own.pid])
            target = runner.Target()
            target.stop([dict(rows[0], ticks='0')])
            self.assertIsNone(own.poll())
            target.stop(rows)
            own.wait(timeout=5)
            self.assertIsNone(foreign.poll())
            self.assertEqual(host.processes('w4c-unit-own'), [])
        finally:
            for child in (own, foreign):
                if child.poll() is None:
                    child.terminate()
                child.wait(timeout=5)

    def test_development_candidate_contains_pins_without_changing_input_bytes(self):
        import hashlib
        spec = importlib.util.spec_from_file_location('gpu_candidate', runner.HERE / 'gpu-candidate.py')
        candidate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(candidate)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            payload = root / 'payload'
            control = payload / 'DEBIAN'
            control.mkdir(parents=True)
            (control / 'control').write_text('Package: just-search\nVersion: 0.3.0\nArchitecture: amd64\nMaintainer: Test <test@example.invalid>\nDescription: W4c fixture\n')
            resources = payload / 'usr/lib/just-search/resources/headless'
            (resources / 'lib').mkdir(parents=True)
            (resources / 'runtime/bin').mkdir(parents=True)
            (resources / 'runtime/bin/java').write_bytes(b'fixture bundled JVM')
            (payload / 'usr/bin').mkdir(parents=True)
            (payload / 'usr/bin/JustSearch').write_bytes(b'fixture packaged shell')
            registry = runner.HERE.parents[3] / 'modules/configuration/src/main/resources/ai/model-registry.v2.json'
            with zipfile.ZipFile(resources / 'lib/configuration-0.3.0.jar', 'w') as archive:
                archive.writestr('ai/model-registry.v2.json', registry.read_bytes())
            package = root / 'JustSearch_0.3.0_amd64.deb'
            subprocess.run(['dpkg-deb', '--root-owner-group', '-b', str(payload), str(package)], check=True, capture_output=True)
            before = runner.digest(package)
            packs = root / 'packs'
            packs.mkdir()
            for name in ('llama-b8571-linux-cuda12-x86_64.zip', 'ort-cuda12-cudnn9-linux-x86_64.zip'):
                (packs / name).write_bytes(b'fixture registry pin only')
            with patch.object(subprocess, 'run', wraps=subprocess.run):
                result = candidate.prepare(package, packs, root / 'assets')
            self.assertEqual(runner.digest(package), before)
            self.assertFalse(result['releaseQualification'])
            self.assertNotEqual(result['candidateSha256'], before)
            self.assertEqual(runner.verify_assets(root / 'assets', [package.name])[package.name], result['candidateSha256'])
            work = root / 'work'
            work.mkdir()
            private_root = root / 'private'
            private_root.mkdir()
            target = runner.Target()
            target.env = gpu.private_environment(os.environ, private_root, 'w4c-unit-extraction')
            args = types.SimpleNamespace(assets=root / 'assets', package=package.name,
                                         development_pack_mirror=packs, development_mirror_port=8769)
            run = types.SimpleNamespace(args=args, work=str(work), target=target, facts={}, evidence=Mock())
            extracted = gpu.extract_package(run)
            self.assertEqual(extracted['sha256'], result['candidateSha256'])
            self.assertEqual((Path(extracted['resourceDirectory']) / 'runtime/bin/java').read_bytes(), b'fixture bundled JVM')
            self.assertTrue(Path(run.binary).is_relative_to(work))


if __name__ == '__main__':
    unittest.main()
