"""W4a-2 counterexamples: reports, trust boundaries and native observations.

OCR outputs are stubbed. No VM, network, display, installed app or host
privilege is used by this suite.
"""
import contextlib
import errno
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import hosted
import linux
import runner
from test_repair2 import immediate
from test_writes import wrapper

DEFINITION = json.loads((runner.HERE / 'journeys.json').read_text())


def rows(lane, ids=None):
    return [{'id': j['id'], 'checks': [{'name': c['id'],
            'status': 'passed' if lane in c['lanes'] else 'skipped-by-decision', 'evidence': []}
            for c in j['checks']]} for j in DEFINITION['journeys'] if ids is None or j['id'] in ids]


class Repair4Tests(unittest.TestCase):
    def test_predecessor_crashes_survive_combine_and_fail_exit(self):
        for source in ('predecessor', 'predecessor-setup'):
            with self.subTest(source=source), tempfile.TemporaryDirectory() as temp:
                candidate = rows('vm-linux-wayland', {'J1', 'J2', 'J3', 'J4', 'J7', 'J8'})
                predecessor = rows('vm-linux-wayland', {'J1', 'J2', 'J3', 'J4', 'J5', 'J6'})
                predecessor.append({'id': 'guest-crashes', 'checks': [{'name': 'guest-crashes',
                    'status': 'failed-product', 'reason': 'candidate Engine crashed', 'evidence': ['../guest-crashes.json']}]})
                args = SimpleNamespace(session='wayland', mode='fresh', journeys=None)
                code = wrapper.combine(args, Path(temp), {'candidate': {'journeys': candidate}, source: {'journeys': predecessor}})
                saved = json.loads((Path(temp) / 'summary.json').read_text())
                self.assertEqual(saved['counts']['failed-product'], 1)
                self.assertFalse(saved['passed'])
                self.assertEqual(code, 1)
                self.assertIn(source + '/guest-crashes.json', json.dumps(saved))
                self.assertEqual(sum(j['id'] == 'J1' for j in saved['journeys']), 1)

    def test_full_lane_pass_ignores_decision_skips_but_partial_runner_fails(self):
        for lane in ('hosted-linux-x11', 'vm-linux-wayland', 'vm-linux-x11'):
            with self.subTest(lane=lane), tempfile.TemporaryDirectory() as temp:
                args = SimpleNamespace(evidence=temp + '/run', vm=None, lane=lane, journeys=['J1'])
                run = runner.Run(args, DEFINITION)
                run.results = rows(lane)
                self.assertTrue(run.save()['passed'])
                run.results = rows(lane, {'J1'})
                self.assertEqual(run.save()['passed'], lane == 'vm-linux-x11')

    def test_partial_runner_with_only_j1_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run(SimpleNamespace(evidence=temp + '/run', vm=None, lane='hosted-linux-x11', journeys=['J1']), DEFINITION)
            run.results = rows('hosted-linux-x11', {'J1'})
            self.assertFalse(run.save()['passed'])

    def test_combine_partial_selection_has_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as temp:
            code = wrapper.combine(SimpleNamespace(session='wayland', mode='rerun', journeys=['J7']),
                                   Path(temp), {'rerun': {'journeys': rows('vm-linux-wayland', {'J7'})}})
            self.assertEqual(code, 1)
            self.assertFalse(json.loads((Path(temp) / 'summary.json').read_text())['passed'])

    def test_last_transport_error_retains_infrastructure_attribution(self):
        for exception in (runner.Infrastructure('SSH exited 255'), runner.Infrastructure('remote harness assertion'),
                          ValueError('malformed response'), OSError('transport failed')):
            with self.subTest(exception=exception), \
                 patch.object(runner.time, 'monotonic', side_effect=[0, 0, 2]), patch.object(runner.time, 'sleep'):
                with self.assertRaises(runner.Infrastructure):
                    runner.wait_for(Mock(side_effect=exception), 'Engine ready', seconds=1)

    def test_last_valid_observation_clears_transient_transport_error(self):
        with patch.object(runner.time, 'monotonic', side_effect=[0, 0, .5, 2]), patch.object(runner.time, 'sleep'):
            with self.assertRaises(AssertionError):
                runner.wait_for(Mock(side_effect=[runner.Infrastructure('SSH'), None]), 'Engine ready', seconds=1)

    def test_missing_app_window_after_successful_observation_is_product(self):
        run = Mock()
        run.native.screen_text.return_value = 'Activities desktop'
        run.native.app_window.return_value = None
        run.native.window_failure.side_effect = AssertionError('Installed app has no window')
        with patch.object(runner, 'wait_for', immediate), self.assertRaises(AssertionError):
            runner.Run.observe_window(run, 'launched')

    def test_desktop_justsearch_text_cannot_substitute_for_owned_window(self):
        run = Mock()
        run.native.screen_text.return_value = 'Activities JustSearch desktop launcher'
        run.native.app_window.return_value = None
        run.native.window_failure.side_effect = AssertionError('Installed app has no window')
        with patch.object(runner, 'wait_for', immediate), self.assertRaises(AssertionError):
            runner.Run.observe_window(run, 'launched')

    def test_first_run_missing_window_is_product(self):
        run = Mock(binary='/usr/bin/JustSearch')
        run.native.app_window.return_value = None
        run.native.screen_text.return_value = 'Activities'
        run.native.window_failure.side_effect = AssertionError('Installed app has no window')
        with patch.object(linux, 'wait_for', immediate), self.assertRaises(AssertionError):
            linux.first_run(run)

    def test_registered_x11_shortcut_without_app_response_is_product(self):
        run = Mock(work='/tmp/owned', binary='/usr/bin/JustSearch', run_id='owned')
        run.args.lane = 'hosted-linux-x11'
        run.wd.execute.return_value = 'Active'
        run.require.return_value = '/tmp/owned-corpus'
        run.native.focused_window.return_value = {'title': 'Fixture — Mousepad'}
        run.native.app_window.return_value = None
        run.native.window_failure.side_effect = AssertionError('Installed app has no window')
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'wait_for', immediate):
            with self.assertRaisesRegex(AssertionError, 'foregrounded search'):
                linux.shortcut(run)

    def test_hosted_adapter_refuses_developer_host_before_starting_tools(self):
        with patch.dict(os.environ, {'DISPLAY': ':99', 'DBUS_SESSION_BUS_ADDRESS': 'fake'}, clear=True), \
             patch.object(Path, 'is_file', return_value=False), patch.object(hosted.subprocess, 'run') as command, \
             patch.object(hosted.subprocess, 'Popen') as spawn, \
             patch('sys.argv', ['hosted.py']), tempfile.TemporaryDirectory() as temp, \
             patch.object(hosted.tempfile, 'TemporaryDirectory', return_value=contextlib.nullcontext(temp)):
            with self.assertRaisesRegex(runner.Infrastructure, 'Hosted lane requires'):
                hosted.main()
            command.assert_not_called(); spawn.assert_not_called()

    def test_direct_local_apt_refuses_developer_host(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=True), \
             patch.object(Path, 'is_file', return_value=False):
            run = Mock(out=Path(temp)); run.target.vm = None
            run.target.run.return_value = SimpleNamespace(returncode=0, stdout='', stderr='')
            with self.assertRaisesRegex(runner.Infrastructure, 'Hosted lane requires'):
                linux.apt(run, 'install', '/tmp/candidate.deb')
            run.target.run.assert_not_called()

    def test_cold_link_ignoring_dispatch_cannot_pass_from_restored_orchid(self):
        run = Mock(work='/tmp/owned', run_id='w4a-123456789', binary='/usr/bin/JustSearch')
        run.target.owned.return_value = [{'pid':1,'exe':run.binary}]
        run.wd.execute.return_value = 'Search v3 orchid lighthouse'
        run.native.app_text.side_effect = lambda name, *a, **kw: 'Library' if 'warm' in name else 'Search v3 orchid lighthouse'
        run.native.matching_text.return_value = None
        run.native.app_window.return_value = {'pid': 1, 'rect': {'width': 1024, 'height': 700}}
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'interaction_observation'), \
             patch.object(linux, 'wait_for', immediate):
            with self.assertRaisesRegex(runner.Infrastructure, 'Cold OS deep link'):
                linux.deep_links(run)
        run.start_ui.assert_not_called()
        run.wd.keys.assert_not_called()
        run.native.type.assert_not_called()
        self.assertEqual(len(run.native.matching_text.call_args.args), 2)

    def test_cold_link_accepts_unique_token_anywhere_in_owned_window(self):
        run = Mock(work='/tmp/owned', run_id='w4a-moving-composer', binary='/usr/bin/JustSearch')
        run.target.owned.return_value = [{'pid':1,'exe':run.binary}]
        query = linux.cold_link_query(run.run_id)
        run.native.matching_text.side_effect = [query, None, 'Library']
        run.native.app_window.return_value = {'pid':1, 'rect': {'width': 1024, 'height': 700}}
        with patch.object(linux, 'require_candidate'), patch.object(linux, 'interaction_observation'), \
             patch.object(linux, 'wait_for', immediate):
            result = linux.deep_links(run)
        self.assertEqual(result['cold'], query)
        run.wd.keys.assert_not_called()
        cold, before, warm = run.native.matching_text.call_args_list
        self.assertEqual(cold.args, ('J7-deep-link-cold', r'\s+'.join(map(re.escape, query.split()))))
        self.assertEqual(before.args[2], warm.args[2])
        self.assertEqual(warm.args[2]['height'], 40)
        self.assertTrue(run.desktop_spawn.call_args_list[0].args[0][1].endswith(query.replace(' ', '%20')))

    def update_run(self, package='JustSearch_0.3.1_amd64.deb'):
        run = Mock(binary='/usr/lib/JustSearch/JustSearch', work='/tmp/owned')
        run.facts = {}; run.args.package = package
        run.target.vm.config.return_value = {'password': 'guest'}
        run.require.side_effect = lambda key: '0.3.1' if key == 'initialVersion' else True
        run.target.owned.side_effect = [[{'pid': 1, 'ticks': '1', 'exe': run.binary}],
                                       [{'pid': 2, 'ticks': '2', 'exe': run.binary}]]
        run.native.screen_text.return_value = 'Authentication password'
        run.target.py.return_value = 'True'
        run.target.run.return_value = SimpleNamespace(stdout='', returncode=0)
        run.target.run.side_effect = lambda args, **kw: SimpleNamespace(returncode=0,
            stdout='/opt/app/runtime/bin/java\n/opt/app/ui-headless.jar' if args[0] == 'dpkg-query' else '')
        run.api.return_value = {'pid': 77}
        return run

    def test_update_rejects_old_engine_even_with_candidate_shell_and_dpkg(self):
        for field in ('executableMatches', 'jarMatches'):
            run = self.update_run()
            run.target.json.return_value = {'pid': 77, 'executableMatches': True, 'jarMatches': True, field: False}
            with patch.object(linux, 'installed_version', return_value='0.3.1'), \
                 patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
                 patch.object(linux, 'wait_for', immediate), self.assertRaisesRegex(AssertionError, 'Running Engine'):
                linux.update_success(run)

    def test_update_version_uses_package_metadata_instead_of_filename(self):
        run = self.update_run('renamed-candidate.deb')
        run.target.json.return_value = {'pid': 77, 'executableMatches': True, 'jarMatches': True}
        # dpkg --verify has no output, unlike the package path query.
        run.target.run.side_effect = lambda args, **kw: SimpleNamespace(returncode=0,
            stdout='/opt/app/runtime/bin/java\n/opt/app/ui-headless.jar' if args[0] == 'dpkg-query' else '')
        with patch.object(linux, 'installed_version', return_value='0.3.1'), \
             patch.object(linux, 'open_update_offer'), patch.object(linux, 'assert_state'), \
             patch.object(linux, 'wait_for', immediate):
            self.assertEqual(linux.update_success(run)['installedVersion'], '0.3.1')

    def restart_run(self, text):
        run = Mock()
        run.native = runner.Native(Mock(), Path('/tmp/unused'))
        run.native.app_window = Mock(return_value={'rect': {'x': 0, 'y': 0}})
        run.native.shot = Mock(); run.native.key = Mock(); run.native.click = Mock()
        run.require.side_effect = lambda key: {'button': {'x': 10, 'y': 20}, 'popup': {}} if key == 'bookmarkGeometry' else True
        def app_text(name, *a, psm=6, **kw):
            if name == 'J4-restored-settings': return 'Settings'
            if name == 'J4-settings-closed' or name.startswith('J4-before-bookmarks'): return ''
            return text(psm)
        run.native.app_text = Mock(side_effect=app_text)
        return run

    def test_real_predecessor_bookmark_fixture_uses_alternate_ocr_on_miss(self):
        def ocr(psm):
            return {6: 'Ubrary', 11: 'Library'}[psm]
        run = self.restart_run(ocr)
        with patch.object(linux, 'assert_state', return_value={'savedFiles': {'savedViews': [{'title': 'Library'}]}}), \
             patch.object(linux, 'interaction_observation'), patch.object(linux, 'wait_for', immediate):
            self.assertEqual(linux.restart(run)['savedFiles']['savedViews'], [{'title': 'Library'}])
        self.assertIn(11, [c.kwargs.get('psm') for c in run.native.app_text.call_args_list])

    def test_persistent_bookmark_ocr_miss_with_retained_state_is_product(self):
        run = self.restart_run(lambda psm: 'Ubrary')
        with patch.object(linux, 'assert_state', return_value={'savedFiles': {'savedViews': [{'title': 'Library'}]}}), \
             patch.object(linux, 'interaction_observation'), patch.object(linux, 'wait_for', immediate):
            with self.assertRaises(AssertionError):
                linux.restart(run)

    def test_black_desktop_retries_once_then_succeeds_or_stays_infrastructure(self):
        for persistent in (False, True):
            with self.subTest(persistent=persistent), tempfile.TemporaryDirectory() as temp:
                target = Mock(); target.run.return_value.stdout = 'false'
                def wait(fn, description, **kw):
                    if 'GNOME command dialog' in description and (persistent or root.call_count == 0):
                        raise runner.Infrastructure('Black compositor screen')
                    return True
                with patch.object(wrapper, 'Target', return_value=target), patch.object(wrapper, 'Native') as native, \
                     patch.object(wrapper, 'crash_evidence'), patch.object(wrapper.vm, 'guest_root') as root, \
                     patch.object(wrapper.vm, 'QMP'), patch.object(wrapper, 'wait_for', wait), patch('screen.key'):
                    if persistent:
                        with self.assertRaises(runner.Infrastructure):
                            wrapper.prepare_desktop(SimpleNamespace(session='wayland'), Path(temp))
                    else:
                        wrapper.prepare_desktop(SimpleNamespace(session='wayland'), Path(temp))
                        native.return_value.focused_window.assert_called_once()
                    self.assertEqual(root.call_count, 1)
                    self.assertEqual(target.prepare.call_count, 2)

    def test_search_word_exists_in_content_but_not_filename(self):
        self.assertTrue(set(linux.QUERY.lower().split()).isdisjoint(re.findall(r'\w+', linux.OPEN_FILE.lower())))
        with tempfile.TemporaryDirectory() as temp:
            linux.generate(Path(temp) / 'corpus')
            self.assertIn(linux.QUERY.lower(), (Path(temp) / 'corpus' / linux.OPEN_FILE).read_text().lower())

    def test_snapshot_cross_filesystem_refuses_with_diagnostic_before_rename(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); disk = root / 'run.qcow2'; disk.write_bytes(b'owned')
            out = root / 'evidence'; out.mkdir()
            actual = Path.stat
            def stat(path, **kw):
                value = actual(path, **kw)
                return SimpleNamespace(st_dev=value.st_dev + 1, st_ino=value.st_ino) if path == out else value
            with patch.object(wrapper.vm, 'ROOT', root), patch.object(wrapper.vm, 'ensure_free'), \
                 patch.object(Path, 'stat', stat), patch.object(Path, 'rename', side_effect=OSError(errno.EXDEV, 'Invalid cross-device link')), self.assertRaisesRegex(runner.Infrastructure, 'same filesystem'):
                wrapper.seal_snapshot(out, {'disk': str(disk)}, disk.stat().st_ino, None, 'candidate')
            self.assertEqual(disk.read_bytes(), b'owned')

    def test_hosted_guest_evidence_command_quotes_directory_name(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); out = root / 'spaces; echo injected'; out.mkdir()
            snapshot = root / 'snapshot'; snapshot.mkdir()
            (snapshot / 'checkpoint.json').write_text('{}')
            args = SimpleNamespace(session='hosted', package='candidate.deb')
            def extract(*args):
                (out / 'evidence').mkdir(); (out / 'evidence/summary.json').write_text('{}')
            with patch.object(wrapper.vm, 'guest_root'), patch.object(wrapper.vm, 'ssh', return_value=Mock(returncode=0)) as ssh, \
                 patch.object(wrapper.vm, 'ssh_args', return_value=[]), patch.object(wrapper.subprocess, 'run'), \
                 patch.object(wrapper, 'extract_evidence', extract):
                wrapper.execute(args, out, 'candidate', ['J7'], snapshot)
            command = next(c.args[1] for c in ssh.call_args_list if c.args[1].startswith('tar -cf'))
            self.assertEqual(shlex.split(command)[-1], 'evidence-' + out.name)
            self.assertEqual(len(shlex.split(command)), 6)

    def test_host_memory_wait_precedes_snapshot_vm_lock(self):
        events = []
        @contextlib.contextmanager
        def lock():
            events.append('lock'); raise RuntimeError('stop before any shared mutation'); yield
        with patch.object(wrapper.vm, 'ensure_free'), patch.object(wrapper.vm, 'wait_host_memory', side_effect=lambda: events.append('memory')), \
             patch.object(wrapper.vm, 'locked', lock), self.assertRaisesRegex(RuntimeError, 'stop before'):
            wrapper.start_snapshot(Path('/tmp/unused'))
        self.assertEqual(events, ['memory', 'lock'])


if __name__ == '__main__':
    unittest.main()
