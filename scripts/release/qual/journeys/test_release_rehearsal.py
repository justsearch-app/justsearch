"""Release rehearsal regressions, using isolated DOM, files and process records."""
import contextlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import linux
import runner
from test_writes import wrapper


class RehearsalTests(unittest.TestCase):
    def test_search_opens_exact_fixture_after_refinement(self):
        fixture = '/tmp/Release fixtures/' + linux.OPEN_FILE
        run = Mock(facts={}, binary='/usr/bin/JustSearch')
        run.require.side_effect = lambda key: str(Path(fixture).parent) if key == 'corpus' else True
        run.wd.session = 'isolated'
        run.wd.text.return_value = linux.OPEN_FILE
        run.wd.element.return_value = {'element-6066-11e4-a52e-4f735466cecf': 'wrong-email-row'}
        observations = []
        def execute(script, args=None):
            # Execute the actual selector against quick/refining/settled DOMs.
            stage = ['quick', 'refining', 'settled'][min(len(observations), 2)]
            observations.append(stage)
            program = '''
const fixture=JSON.parse(process.argv[1]), stage=process.argv[2];
const row=id=>({getAttribute:()=>id,getBoundingClientRect:()=>({width:100,height:20}),
 'element-6066-11e4-a52e-4f735466cecf':id===fixture?'fixture-row':'wrong-email-row'});
const rows=[row('/tmp/elsewhere/'+fixture.split('/').at(-1)),row('/tmp/message.eml'),row(fixture)];
if(stage==='settled')rows.reverse();
const root={querySelectorAll:s=>s==='*'?[]:s.includes('search-result-row')?rows:[]};
const card={snapshot:{query:'lighthouse',isSearching:false,isRefining:stage==='refining',passStage:stage==='quick'?'quick':'refined'},
 shadowRoot:root,getBoundingClientRect:()=>({width:100,height:100})};
global.document={querySelectorAll:s=>s==='*'||s==='jf-results-card'?[card]:[]};
const fn=new Function('arguments',JSON.parse(process.argv[3]));
console.log(JSON.stringify(fn(JSON.parse(process.argv[4]))));
'''
            return json.loads(subprocess.check_output(['node', '-e', program, json.dumps(fixture), stage,
                json.dumps(runner.DEEP_QUERY + script), json.dumps(args or [])], text=True))
        run.wd.execute.side_effect = execute
        def poll(fn, description, **kw):
            for _ in range(4):
                value = fn()
                if value:
                    return value
            self.fail(description)
        with patch.object(linux, 'search_api', return_value={'results': []}), \
             patch.object(linux, 'wait_for', poll), patch.object(linux, 'receiving_viewer', return_value={'pid': 123}):
            linux.search_open(run)
        action = run.wd.call.call_args_list[0].args[1]['actions'][0]['actions'][0]
        self.assertEqual(action['origin']['element-6066-11e4-a52e-4f735466cecf'], 'fixture-row')
        self.assertEqual(observations, ['quick', 'refining', 'settled'])

    def test_wayland_subset_without_predecessor_is_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            driver = Path(temp) / 'tauri-driver'; driver.touch()
            argv = ['vm-run.py', '--session', 'wayland', '--assets', temp, '--package', 'candidate.deb',
                    '--registry', temp + '/server.json', '--driver', str(driver), '--evidence', temp + '/out',
                    '--journeys', 'J1', 'J6']
            with patch.object(sys, 'argv', argv), patch.object(wrapper.vm, 'owner'):
                self.assertEqual(wrapper.parse_args().journeys, ['J1', 'J6'])

    def test_default_wayland_without_predecessor_runs_candidate_and_records_update(self):
        with tempfile.TemporaryDirectory() as temp:
            args = NS(evidence=Path(temp)/'out', session='wayland', mode='fresh', journeys=None,
                      predecessor=None, mirror=None)
            stages = []
            def stage(args, out, phase, journeys, snapshot=None, seal=False):
                stages.append((phase, journeys))
                out.mkdir(parents=True)
                (out/'summary.json').write_text('{"journeys":[]}')
                if seal:
                    (out/'snapshot').mkdir()
            with patch.object(wrapper.vm, 'ROOT', Path(temp)), patch.object(wrapper, 'parse_args', return_value=args), \
                 patch.object(wrapper, 'run_overlay', side_effect=stage), patch.object(wrapper, 'combine', return_value=1):
                wrapper.main()
            self.assertTrue(all(phase == 'candidate' for phase, _ in stages))
            self.assertEqual(stages[-1][1], ['J4', 'J5', 'J6', 'J7', 'J8'])

    def test_wayland_subset_also_records_missing_update_coverage(self):
        with tempfile.TemporaryDirectory() as temp:
            args = NS(evidence=Path(temp)/'out', session='wayland', mode='fresh', journeys=['J1'],
                      predecessor=None, mirror=None)
            def stage(args, out, phase, journeys, snapshot=None, seal=False):
                out.mkdir(parents=True)
                (out/'summary.json').write_text('{"journeys":[]}')
            with patch.object(wrapper.vm, 'ROOT', Path(temp)), patch.object(wrapper, 'parse_args', return_value=args), \
                 patch.object(wrapper, 'run_overlay', side_effect=stage) as overlay, \
                 patch.object(wrapper, 'combine', return_value=1):
                wrapper.main()
            self.assertEqual(overlay.call_args.args[3], ['J1', 'J5'])

    def run_selected(self, selection, checks, phase='candidate'):
        with tempfile.TemporaryDirectory() as temp:
            definition = {'journeys': [j for j in json.loads((runner.HERE/'journeys.json').read_text())['journeys']
                                        if j['id'] in selection]}
            run = Mock(work='', binary='', facts={}, results=[])
            run.save.side_effect = lambda: dict(runner.summarize(run.results), journeys=run.results)
            argv = ['runner.py', '--lane', 'vm-linux-wayland', '--vm', 'ubuntu', '--assets', temp,
                    '--package', 'candidate.deb', '--evidence', temp, '--phase', phase, '--journeys', *selection]
            # Only replace external setup/check execution; retain real selection and summary.
            def check(row, name, fn):
                fn(); row['checks'].append({'name': name, 'status': 'passed', 'evidence': []})
            run.check.side_effect = check
            text = Path.read_text
            with patch.object(sys, 'argv', argv), patch.object(runner, 'Run', return_value=run), \
                 patch.object(runner, 'verify_assets', return_value={}), patch.dict(linux.CHECKS, checks), \
                 patch.object(Path, 'read_text', lambda p, *a, **kw: json.dumps(definition) if p == runner.HERE/'journeys.json' else text(p,*a,**kw)), \
                 contextlib.redirect_stdout(io.StringIO()):
                runner.main()
            return run.results

    def test_candidate_j6_executes_and_explicit_j5_is_unavailable(self):
        reinstall, remove = Mock(), Mock()
        rows = self.run_selected(['J5', 'J6'], {'reinstall': reinstall, 'remove-retain-reinstall': remove})
        self.assertEqual([r['id'] for r in rows], ['J5', 'J6'])
        self.assertTrue(all(c['status'] == 'unavailable' and 'predecessor' in c['reason'] for c in rows[0]['checks']))
        reinstall.assert_called_once(); remove.assert_called_once()

    def test_explicit_out_of_phase_journey_keeps_applicability_row(self):
        # J9 is also out of phase but remains an applicability skip for this lane.
        rows = self.run_selected(['J9'], {})
        self.assertEqual(rows[0]['id'], 'J9')
        self.assertEqual(rows[0]['checks'][0]['status'], 'skipped-by-decision')

    def test_explicit_candidate_journey_on_predecessor_records_correct_reason(self):
        rows = self.run_selected(['J7'], {}, phase='predecessor')
        self.assertEqual(rows[0]['id'], 'J7')
        self.assertTrue(all(c['status'] == 'unavailable' and 'requires the candidate phase' in c['reason']
                            for c in rows[0]['checks']))

    def test_provision_suppresses_desktop_notifier_and_restarts_existing_session(self):
        commands = []
        with tempfile.TemporaryDirectory() as temp, patch.object(wrapper.vm, 'guest_root', side_effect=lambda _, s: commands.append(s)), \
             patch.object(wrapper.vm, 'ssh'), patch.object(wrapper.vm, 'copy_in'), \
             patch.object(wrapper.vm, 'config', return_value={'password': 'guest'}):
            wrapper.provision(NS(driver=Path(temp)/'driver', session='wayland'), Path(temp))
        config = '\n'.join(commands)
        self.assertIn('/home/qual/.config/autostart/update-notifier.desktop', config)
        self.assertIn('Hidden=true', config)
        self.assertIn('Prompt=never', config)
        self.assertEqual(commands[-1], 'systemctl restart gdm3')
        self.assertNotIn('apport.service', config)

    def test_guest_copy_rejects_corruption_and_quotes_destination(self):
        vm = wrapper.vm
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)/'source file'; source.write_bytes(b'verified release bytes')
            destination = str(Path(temp)/"guest file's copy")
            def scp(*args):
                Path(destination).write_bytes(b'corrupt bytes')
            def ssh(name, command, **kw):
                return subprocess.run(shlex.split(command), **kw)
            with patch.object(vm, 'require_owner'), patch.object(vm, 'config', return_value={'user': 'qual'}), \
                 patch.object(vm, 'ssh_args', return_value=[]), patch.object(vm, 'run', side_effect=scp), \
                 patch.object(vm, 'ssh', side_effect=ssh):
                with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
                    vm.copy_in('ubuntu', source, destination)
                with patch.object(vm, 'run', side_effect=lambda *a: Path(destination).write_bytes(source.read_bytes())):
                    vm.copy_in('ubuntu', source, destination)

    def test_empty_crash_collection_waits_for_inflight_collectors(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out/'summary.json').write_text(json.dumps({'journeys': [], 'fullSelection': True}))
            results = [NS(returncode=1, stdout='-- Notice: 1 systemd-coredump@.service unit is running, output may be incomplete.\n', stderr='No coredumps found.\n'),
                       NS(returncode=1, stdout='', stderr='No coredumps found.\n'),
                       NS(returncode=0, stdout='', stderr='')]
            with patch.object(runner.subprocess, 'run', side_effect=results), patch.object(runner.time, 'sleep'):
                runner.collect_hosted_crashes(out, '2026-10-31T00:00:00Z')
            report = json.loads((out/'summary.json').read_text())
            self.assertEqual(report['counts']['failed-infrastructure'], 0)
            self.assertEqual(json.loads((out/'coredumps.json').read_text()), [])

    def test_observed_product_crash_survives_unfinished_collection(self):
        entry = {'exe': '/opt/JustSearch', 'sig': 11}
        notice = '-- Notice: 1 systemd-coredump@.service unit is running, output may be incomplete.\n'
        for finished in (False, True):
            with self.subTest(finished=finished), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)
                (out/'summary.json').write_text(json.dumps({'journeys': [], 'fullSelection': True}))
                observations = iter([
                    NS(returncode=0, stdout=json.dumps([entry]) + '\n' + notice, stderr=''),
                    NS(returncode=0, stdout='[]' if finished else json.dumps([entry]) + '\n' + notice, stderr='')])
                def command(args, **kw):
                    if 'coredumpctl' in args:
                        return next(observations)
                    self.assertEqual(args, ['dpkg-query', '-L', 'just-search'])
                    return NS(returncode=0, stdout='/opt/JustSearch\n', stderr='')
                with patch.object(runner.subprocess, 'run', side_effect=command), \
                     patch.object(runner.time, 'monotonic', side_effect=[0, 0, 30, 60]), \
                     patch.object(runner.time, 'sleep'):
                    code = runner.collect_hosted_crashes(out, '2026-10-31T00:00:00Z')
                report = json.loads((out/'summary.json').read_text())
                self.assertEqual(code, 1)
                self.assertEqual(report['counts']['failed-product'], 1)
                self.assertEqual(report['counts']['failed-infrastructure'], 0 if finished else 1)
                checks = [c for j in report['journeys'] for c in j['checks']]
                product = next(c for c in checks if c['status'] == 'failed-product')
                self.assertIn('/opt/JustSearch', product['reason'])
                self.assertIn('"Signal": "11"', product['reason'])
                for evidence in ('coredumps.json', 'hosted-crashes.json'):
                    self.assertIn(evidence, product['evidence'])
                self.assertEqual(json.loads((out/'coredumps.json').read_text()), [entry])
                self.assertEqual(json.loads((out/'hosted-crashes.json').read_text())[0]['attribution'], 'product')
                self.assertEqual(len(json.loads((out/'coredumpctl.json').read_text())), 2)
                if not finished:
                    collection = next(c for c in checks if c['status'] == 'failed-infrastructure')
                    self.assertIn('Hosted coredump collectors finished timed out after 60s', collection['reason'])
                    self.assertIn('coredumpctl.json', collection['evidence'])

    def test_package_lookup_failure_keeps_packaged_product_crash(self):
        entry = {'exe': '/opt/JustSearch', 'sig': 11, 'package': 'just-search'}
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out/'summary.json').write_text(json.dumps({'journeys': [], 'fullSelection': True}))
            def command(args, **kw):
                if 'coredumpctl' in args:
                    return NS(returncode=0, stdout=json.dumps([entry]), stderr='')
                return NS(returncode=2, stdout='', stderr='dpkg-query: database unavailable\n')
            with patch.object(runner.subprocess, 'run', side_effect=command):
                code = runner.collect_hosted_crashes(out, '2026-10-31T00:00:00Z')
            report = json.loads((out/'summary.json').read_text())
            self.assertEqual(code, 1)
            self.assertEqual(report['counts']['failed-product'], 1)
            self.assertEqual(report['counts']['failed-infrastructure'], 1)
            checks = [c for j in report['journeys'] for c in j['checks']]
            self.assertIn('package paths unavailable', next(
                c for c in checks if c['status'] == 'failed-infrastructure')['reason'])

    def test_shutdown_captures_owned_identities_before_driver_retires_engine(self):
        run = Mock(wd=Mock(), facts={}, target=Mock())
        driver = run.wd
        run.target.run.return_value.stdout = '/usr/bin/JustSearch\n/runtime/bin/java\n'
        identities = [{'pid': 123, 'ticks': '1', 'exe': '/runtime/bin/java'}]
        def owned():
            if driver.close.called:
                raise runner.Infrastructure('Cannot read owned process 123')
            return identities
        run.target.owned.side_effect = owned
        runner.Run.stop_app(run)
        run.target.stop.assert_called_once_with(identities)

    def test_owned_process_exiting_between_stat_and_environment_is_retired(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'42/environ'
            path.parent.mkdir(); path.touch()
            target = runner.Target(); target.env['QUAL_JOURNEY_RUN'] = 'isolated'
            target.app_processes[(42, '99')] = {'pid': 42, 'ticks': '99'}
            def execute(source, value, **kw):
                output = io.StringIO()
                stats = ['42 (engine) S 1 ' + '0 '*17 + '99', '42 (engine) Z 1 ' + '0 '*17 + '99']
                with patch.object(sys, 'argv', ['probe', json.dumps(value)]), \
                     patch.object(Path, 'glob', return_value=[path]), \
                     patch.object(Path, 'read_text', side_effect=stats), \
                     patch.object(Path, 'read_bytes', side_effect=PermissionError('retiring Engine')), \
                     contextlib.redirect_stdout(output):
                    exec(source, {})
                return json.loads(output.getvalue())
            with patch.object(target, 'json', side_effect=execute):
                self.assertEqual(target.owned(), [])


if __name__ == '__main__':
    unittest.main()
