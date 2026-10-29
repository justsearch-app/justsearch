"""Harness checks; no app, display, network, shared VM or installed packages."""
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch


class RunnerTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('journey_runner', Path(__file__).with_name('runner.py'))
        self.r = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.r)

    def test_assertion_is_product_failure(self):
        self.assertEqual(self.r.classify(AssertionError('wrong search result')), 'failed-product')

    def test_driver_failure_is_infrastructure(self):
        self.assertEqual(self.r.classify(self.r.Infrastructure('driver mismatch')), 'failed-infrastructure')

    def test_download_outage_is_infrastructure(self):
        self.assertEqual(self.r.classify(self.r.Infrastructure('download unavailable')), 'failed-infrastructure')

    def test_unknown_exception_is_not_blame_on_product(self):
        self.assertEqual(self.r.classify(ValueError('harness bug')), 'failed-infrastructure')

    def test_blocked_prerequisite_is_unavailable(self):
        self.assertEqual(self.r.classify(self.r.Unavailable('AI download did not complete')), 'unavailable')

    def test_unavailable_is_not_pass(self):
        report = self.r.summarize([{'id': 'J1', 'checks': [{'status': 'unavailable', 'reason': 'no driver'}]}])
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['passed'], 0)
        self.assertEqual(report['counts']['unavailable'], 1)

    def test_decision_skip_is_not_pass(self):
        report = self.r.summarize([{'id': 'J5', 'checks': [{'status': 'skipped-by-decision', 'reason': 'VM only'}]}])
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['passed'], 0)

    def test_mixed_failure_families_are_preserved(self):
        report = self.r.summarize([{'id': 'J2', 'checks': [{'status': 'passed'},
            {'status': 'failed-product'}, {'status': 'failed-infrastructure'}]}])
        self.assertEqual(report['counts']['failed-product'], 1)
        self.assertEqual(report['counts']['failed-infrastructure'], 1)
        self.assertFalse(report['passed'])

    def test_empty_report_cannot_pass(self):
        self.assertFalse(self.r.summarize([])['passed'])

    def test_all_executed_checks_must_pass(self):
        self.assertTrue(self.r.summarize([{'id': 'J1', 'checks': [{'status': 'passed'}]}])['passed'])

    def test_invalid_status_is_rejected(self):
        with self.assertRaises(ValueError):
            self.r.summarize([{'id': 'J1', 'checks': [{'status': 'skip'}]}])

    def test_tampered_release_asset_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'candidate.deb').write_bytes(b'altered')
            (root / 'SHA256SUMS').write_text('0' * 64 + '  candidate.deb\n')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_checksums_cannot_escape_asset_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'SHA256SUMS').write_text('0' * 64 + '  ../candidate.deb\n')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['../candidate.deb'])

    def test_duplicate_checksum_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'SHA256SUMS').write_text(('0' * 64 + '  candidate.deb\n') * 2)
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_missing_checksum_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'candidate.deb').write_bytes(b'package')
            (root / 'SHA256SUMS').write_text('')
            with self.assertRaises(self.r.Infrastructure):
                self.r.verify_assets(root, ['candidate.deb'])

    def test_steps_keep_both_failure_families_and_write_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = types.SimpleNamespace(evidence=str(Path(tmp) / 'evidence'), vm=None, lane='hosted-linux-x11')
            run = self.r.Run(args, json.loads(Path(__file__).with_name('journeys.json').read_text()))
            row = {'id': 'J2', 'checks': []}
            run.results.append(row)
            def fail(exc):
                raise exc
            with patch.object(run.native, 'shot', side_effect=OSError('no desktop')):
                run.check(row, 'search', lambda: fail(AssertionError('fixture absent')))
                run.check(row, 'viewer', lambda: fail(self.r.Infrastructure('focus failed')))
                run.check(row, 'models', lambda: fail(self.r.Unavailable('download not completed')))
            summary = json.loads((run.out / 'summary.json').read_text())
            self.assertEqual([c['status'] for c in summary['journeys'][0]['checks']],
                             ['failed-product', 'failed-infrastructure', 'unavailable'])
            self.assertFalse(summary['passed'])
            self.assertEqual(len(list(run.out.glob('*.error.txt'))), 3)

    def test_settled_scenario_quotes_are_preserved(self):
        expected = {
            'S3': "Journeys J1-J9 run against the draft's exact bytes on hosted runners, the two clean VMs and the GPU host, and produce one result summary that separates passed, failed, skipped by decision (Windows GPU) and unavailable",
            'S7': 'Clean Ubuntu 24.04 (Wayland and X11): .deb installs, launches, adds a folder via the system picker, indexes, searches, opens a result, Install AI gives search without chat on CPU; X11 shortcut registers, Wayland shows the --focus-search alternative; uninstall/reinstall keeps data',
            'S8': 'Linux in-app update via descriptor v2 and pkexec apt-get: success relaunches the new version; cancelled prompt, lock timeout or failure resumes the old Engine and re-offers the update',
            'S21': "The MCP bridge's manifest and registry entry carry the app's version, the release URL and checksum, declare Linux and Windows, and the bridge works on both (J8)",
            'S22': 'Journeys run on clean installs without a developer JDK, models or inherited runtime paths; a missing download, skipped or unavailable check is reported as such, never as a pass',
        }
        definition = json.loads(Path(__file__).with_name('journeys.json').read_text())
        expected['S-Windows-update'] = 'Windows in-app update via descriptor v2 from a VM-local mirror: success relaunches the new version with data kept; a cancelled elevation or failed install resumes the old version and re-offers'
        self.assertEqual(definition['scenarios'], expected)

    def test_preparation_failure_retains_unavailable_checks(self):
        report = self.r.preparation_failure('hosted-linux-x11', 'Download outage')
        self.assertFalse(report['passed'])
        self.assertEqual(report['counts']['failed-infrastructure'], 1)
        self.assertGreater(report['counts']['unavailable'], 0)
        self.assertGreater(report['counts']['skipped-by-decision'], 0)
        self.assertEqual(report['counts']['passed'], 0)

    def test_mcp_missing_fixture_is_unavailable_before_client_launch(self):
        import linux
        def require(key):
            if key == 'folder':
                raise self.r.Unavailable('picker did not complete')
            return True
        with self.assertRaises(self.r.Unavailable):
            linux.mcp(types.SimpleNamespace(require=require))

    def test_ai_missing_fixture_is_unavailable_before_downloading(self):
        import linux
        def require(key):
            if key == 'folder':
                raise self.r.Unavailable('picker did not complete')
            return True
        with self.assertRaises(self.r.Unavailable):
            linux.install_ai(types.SimpleNamespace(require=require))

    def test_predecessor_cannot_certify_candidate_desktop_integration(self):
        import linux
        run = types.SimpleNamespace(require=lambda key: '0.3.1')
        with patch.object(linux, 'installed_version', return_value='0.3.0'):
            with self.assertRaises(linux.Unavailable):
                linux.require_candidate(run)

    def test_x11_only_exercises_fresh_install(self):
        definition = json.loads(Path(__file__).with_name('journeys.json').read_text())
        active = [j['id'] for j in definition['journeys']
                  if any('vm-linux-x11' in c['lanes'] for c in j['checks'])]
        self.assertEqual(active, ['J1'])

    def test_hosted_picker_is_skipped_and_api_add_is_exercised(self):
        definition = json.loads(Path(__file__).with_name('journeys.json').read_text())
        checks = {c['id']: c for j in definition['journeys'] for c in j['checks']}
        self.assertNotIn('hosted-linux-x11', checks['folder-picker']['lanes'])
        self.assertIn('hosted-linux-x11', checks['folder-api']['lanes'])

    def test_viewer_requires_exact_fixture_path_in_receiving_process(self):
        import linux
        fixture = '/tmp/this-run/' + linux.OPEN_FILE
        row = {'exe': '/usr/bin/mousepad', 'argv': ['mousepad', fixture], 'fds': []}
        self.assertEqual(linux.viewer_fixture([row], fixture), row)
        self.assertIsNone(linux.viewer_fixture([dict(row, argv=['mousepad', '/other/' + linux.OPEN_FILE])], fixture))
        self.assertIsNone(linux.viewer_fixture([dict(row, exe='/usr/bin/JustSearch')], fixture))
        self.assertEqual(linux.viewer_fixture([dict(row, argv=['mousepad'], fds=[fixture])], fixture)['fds'], [fixture])

    def test_open_result_closes_only_the_identified_viewer(self):
        import linux
        from unittest.mock import Mock
        run = Mock()
        run.facts = {}
        run.work = '/tmp/own-run'
        run.require.return_value = '/tmp/own-corpus'
        run.wd.text.return_value = linux.OPEN_FILE
        run.wd.element.return_value = {'element-6066-11e4-a52e-4f735466cecf': 'fixture'}
        run.native.screen_text.return_value = 'File Edit Document ' + linux.OPEN_FILE
        viewer = {'pid': 123, 'ticks': '456', 'exe': '/usr/bin/mousepad'}
        with patch.object(linux, 'search_api', return_value={}), \
             patch.object(linux, 'receiving_viewer', return_value=viewer):
            linux.search_open(run)
        run.target.stop.assert_called_once_with([viewer])
        self.assertNotIn('alt+f4', [c.args[0] for c in run.native.key.call_args_list])

    def test_update_starts_from_banner_action(self):
        import linux
        from unittest.mock import Mock
        run = Mock()
        run.args.phase = 'predecessor'
        run.args.predecessor.name = 'JustSearch_0.3.0_amd64.deb'
        run.work = '/tmp/own-run'
        run.facts = {}
        run.require.side_effect = lambda key: '0.3.1' if key == 'initialVersion' else {}
        run.target.run.side_effect = [types.SimpleNamespace(stdout='0.3.0'),
                                      types.SimpleNamespace(stdout='', returncode=0)]
        run.wd.execute.side_effect = [{'text': 'JustSearch 0.3.1 is available. Review and install', 'repair': False}, True]
        with patch.object(linux, 'installed_version', return_value='0.3.0'), \
             patch.object(linux, 'digest', return_value='package-hash'):
            linux.update_prepare(run)
        run.nav.assert_not_called()
        self.assertEqual(run.wd.click.call_args_list[0].args,
                         ('button[aria-label="Open app update settings"]',))
        self.assertTrue(run.facts['updateOffered'])
        run.wd.element.assert_called_with('button[aria-label="Install update"]:not(:disabled)', seconds=45)

    def test_recovered_update_returns_through_banner_after_settings_reset(self):
        import linux
        from unittest.mock import Mock, call
        run = Mock()
        run.require.return_value = '0.3.1'
        run.wd.execute.side_effect = [{'text': 'JustSearch 0.3.1 is available. Review and install', 'repair': False}, True]
        linux.open_update_offer(run, 'recovered')
        run.close_settings.assert_called_once()
        self.assertEqual(run.wd.click.call_args_list, [
            call('button[aria-label="Open app update settings"]'),
            call('button[data-settings-category="desktop"]')])
        run.nav.assert_not_called()
        run.wd.element.assert_called_with('button[aria-label="Install update"]:not(:disabled)', seconds=45)

    def test_interaction_timing_records_running_indexing_and_unknown(self):
        import linux
        from unittest.mock import Mock
        run = Mock()
        run.require.return_value = 10
        for state, expected in [('RUNNING', True), ('IDLE', False), (None, None)]:
            run.api.return_value = {'operating': {'indexing': {'state': state}}}
            with patch.object(linux.time, 'monotonic', return_value=12.5):
                result = linux.interaction_observation(run, 'click')
            self.assertEqual(result['secondsSinceWindowShown'], 2.5)
            self.assertIs(result['indexingRunning'], expected)

    def test_candidate_overlay_excludes_update_and_retention(self):
        self.assertEqual(self.r.phase_journeys('candidate'), {'J1', 'J2', 'J3', 'J4', 'J7', 'J8'})

    def test_fresh_predecessor_overlay_prepares_state_before_update(self):
        self.assertEqual(self.r.phase_journeys('predecessor'), {'J1', 'J2', 'J3', 'J4', 'J5', 'J6'})

    def test_hosted_add_uses_canonical_production_root_api(self):
        import linux
        from unittest.mock import Mock
        run = Mock()
        run.require.return_value = '/tmp/own-corpus'
        run.facts = {}
        run.api.side_effect = [{}, {'roots': ['/tmp/own-corpus']}]
        linux.add_folder_api(run)
        self.assertEqual(run.api.call_args_list[0].args,
                         ('/api/indexing/roots', {'path': '/tmp/own-corpus', 'collection': 'default'}))
        run.wd.click.assert_not_called()

    def test_vm_launch_waits_for_native_dialog_and_acknowledgment(self):
        from unittest.mock import Mock
        run = Mock()
        run.target.vm = True
        run.target.env = {'HOME': '/home/qual'}
        run.work = '/tmp/own-run'
        run.target.py.side_effect = [None, 'True\n']
        run.native.screen_text.return_value = 'Run a Command'
        self.r.Run.desktop_spawn(run, ['/usr/bin/JustSearch'], '/tmp/own-run/app.log')
        self.assertEqual([c.args[0] for c in run.native.key.call_args_list],
                         ['escape', 'alt+f2', 'ctrl+a', 'enter'])
        run.native.screen_text.assert_called()
        self.assertEqual(run.target.py.call_count, 2)

    def test_static_rail_navigation_uses_native_webdriver_element_click(self):
        from unittest.mock import Mock
        run = Mock()
        run.wd.session = 'session'
        run.wd.element.return_value = {'element-6066-11e4-a52e-4f735466cecf': 'rail-button'}
        self.r.Run.nav(run, 'Settings')
        run.wd.call.assert_called_once_with('/session/session/element/rail-button/click', {})
        run.wd.click.assert_not_called()

    def test_ocr_is_unavailable_after_a_launcher_failure(self):
        import linux
        from unittest.mock import Mock
        run = Mock()
        run.require.side_effect = lambda key: (_ for _ in ()).throw(linux.Unavailable('No launched Engine')) if key == 'ready' else True
        with patch.object(linux, 'require_candidate'):
            with self.assertRaises(linux.Unavailable):
                linux.ocr(run)
        run.ready.assert_not_called()

    def test_vm_restart_waits_for_bindable_ports(self):
        import socket
        spec = importlib.util.spec_from_file_location('journey_vm', Path(__file__).with_name('vm-run.py'))
        wrapper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(wrapper)
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            port = occupied.getsockname()[1]
            self.assertFalse(wrapper.ports_available([port]))
        self.assertTrue(wrapper.ports_available([port]))

    def test_guest_evidence_links_resolve_and_traversal_is_rejected(self):
        import io
        import tarfile
        spec = importlib.util.spec_from_file_location('journey_vm', Path(__file__).with_name('vm-run.py'))
        wrapper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(wrapper)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'guest.tar'
            for name in ('evidence/viewer.png', '../escaped.png'):
                with tarfile.open(archive, 'w') as tar:
                    member = tarfile.TarInfo(name)
                    member.size = 4
                    tar.addfile(member, io.BytesIO(b'shot'))
                if name.startswith('evidence/'):
                    wrapper.extract_evidence(archive, root / 'out')
                    self.assertEqual((root / 'out/evidence/viewer.png').read_bytes(), b'shot')
                else:
                    with self.assertRaises(wrapper.Infrastructure):
                        wrapper.extract_evidence(archive, root / 'out')
                    self.assertFalse((root / 'escaped.png').exists())


if __name__ == '__main__':
    unittest.main()
