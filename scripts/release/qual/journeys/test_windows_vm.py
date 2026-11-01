"""Host-side Windows orchestration fixtures; no shared VM or guest operations."""
import contextlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from runner import Infrastructure

spec=importlib.util.spec_from_file_location('windows_vm_fixture',Path(__file__).with_name('vm-windows.py'))
wrapper=importlib.util.module_from_spec(spec);spec.loader.exec_module(wrapper)


class WindowsVMTests(unittest.TestCase):
    def test_update_effect_requires_stopped_services_policies_and_unchanged_downloads(self):
        before = {'time': '2026-10-29T00:00:00Z', 'services': [
            {'Name': name, 'StartMode': 'Manual', 'State': 'Stopped'} for name in ('wuauserv', 'UsoSvc')],
            'policies': {'NoAutoUpdate': 1, 'AUOptions': 2}, 'downloadBytes': 0,
            'downloads': [], 'events': [], 'eventLogEnabled': True}
        wrapper.verify_update_effect(before, before)
        for change in ({'services': [dict(before['services'][0], State='Running'), before['services'][1]]},
                       {'services': []}, {'policies': {'NoAutoUpdate': 0, 'AUOptions': 2}},
                       {'policies': {'NoAutoUpdate': 1}}, {'downloadBytes': 10},
                       {'downloads': [{'name': 'new-update', 'bytes': 0}]},
                       {'events': [{'id': 44, 'keywords': ['Download', 'Started']}]},
                       {'eventLogEnabled': False}):
            with self.subTest(change=change), self.assertRaises(Infrastructure):
                wrapper.verify_update_effect(before, dict(before, **change))
        wrapper.verify_update_effect(before, dict(before, events=[{'id': 26, 'keywords': ['Search']}]))

    def test_write_stop_ends_shared_polling_immediately_without_a_retry(self):
        import runner
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); disk = root/'disk.qcow2'; disk.write_bytes(b'thin')
            budget = wrapper.WriteBudget({'pid': 123, 'disk': str(disk)}, root)
            budget.error = 'Windows run exceeded 15 GB write budget; stopped'
            with patch.object(runner.time, 'monotonic', side_effect=[0, 0, 1]), \
                 patch.object(runner.time, 'sleep') as sleep, \
                 patch.object(budget, 'check', wraps=budget.check) as check:
                with self.assertRaises(Infrastructure) as caught:
                    runner.wait_for(budget.check, 'completion', seconds=0.5)
            self.assertEqual(str(caught.exception), budget.error)
            check.assert_called_once()
            sleep.assert_not_called()

    def test_only_a_dedicated_j3_run_gets_the_25_gb_allowance(self):
        self.assertEqual(wrapper.write_limit(['J3']), 25_000_000_000)
        for journeys in (['J1'], ['J5'], ['J1', 'J2'], ['J3', 'J4']):
            self.assertEqual(wrapper.write_limit(journeys), 15_000_000_000)
        wrapper.enforce_write_limit({'hostQemuWrittenBytes': 20_000_000_000}, 25_000_000_000)
        with self.assertRaisesRegex(Infrastructure, '25 GB'):
            wrapper.enforce_write_limit({'guestWrittenBytes': 25_000_000_001}, 25_000_000_000)

    def test_full_run_isolates_j3_budget_and_stops_all_stages_after_a_write_stop(self):
        import sys
        for stop in (False, True):
            with self.subTest(stop=stop), tempfile.TemporaryDirectory() as temp:
                out = Path(temp)/'out'
                calls = []
                def run(args, folder, phase, journeys, snapshot, seal):
                    calls.append((phase, journeys))
                    folder.mkdir(parents=True)
                    (folder/'snapshot').mkdir()
                    rows = [{'id': 'write-budget', 'checks': []}] if stop and journeys == ['J3'] else []
                    (folder/'summary.json').write_text(json.dumps({'journeys': rows}))
                argv = ['vm-windows.py', '--assets', 'assets', '--package', 'candidate.exe',
                        '--registry', 'registry.json', '--driver', 'driver.exe', '--predecessor', 'parent.exe',
                        '--mirror', 'mirror', '--setup-snapshot', 'tools', '--evidence', str(out)]
                with patch.object(wrapper.vm, 'ROOT', Path(temp)), patch.object(sys, 'argv', argv), patch.object(wrapper.vm, 'owner'), \
                     patch.object(wrapper, 'run_overlay', side_effect=run), patch.object(wrapper, 'combine', return_value=1):
                    wrapper.main()
                expected = [('candidate', ['J1', 'J2']), ('candidate', ['J3'])]
                if not stop:
                    expected += [('candidate', ['J4', 'J8']), ('predecessor', ['J1', 'J2']),
                                 ('predecessor', ['J3']), ('predecessor', ['J4', 'J5', 'J6'])]
                self.assertEqual(calls, expected)

    def test_chained_snapshot_reruns_keep_distinct_guest_evidence_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            args=SimpleNamespace(session='vm',mirror=None,package='candidate.exe',predecessor=None)
            scripts=[]
            def capture(system,source,destination):
                scripts.append(source.read_text())
            def extract(archive,out,folder):
                (out/'evidence').mkdir()
                (out/'evidence/summary.json').write_text('{}')
            with patch.object(wrapper,'stage_inputs'),patch.object(wrapper,'guest_ps',return_value='0'), \
                 patch.object(wrapper.vm,'copy_in',side_effect=capture),patch.object(wrapper,'wait_for',return_value='0'), \
                 patch.object(wrapper,'copy_out'),patch.object(wrapper.common,'extract_evidence',side_effect=extract) as extraction:
                for name in ('j2','j3'):
                    out=root/name/'rerun';out.mkdir(parents=True)
                    wrapper.execute(args,out,'candidate',['J2'],None)
            folders=[call.args[2] for call in extraction.call_args_list]
            self.assertEqual(len(set(folders)),2,'A checkpoint retains earlier evidence; reusing its directory aborts the next journey')
            for folder,script in zip(folders,scripts):
                self.assertIn('C:/Qual/'+folder,script)

    def test_setup_verifies_exact_driver_after_reboot_and_records_snapshot_versions(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);disk=root/'run.qcow2';disk.write_bytes(b'cached tools')
            (root/'run-vars.fd').write_bytes(b'firmware')
            started={'disk':str(disk),'pid':999999999,'owner':'fixture'}
            source=root/'source';source.mkdir()
            (source/'checkpoint.json').write_text('{"retained":"actual-installed-state"}')
            (source/'manifest.json').write_text(json.dumps({'kind':'application','pins':{'phase':'predecessor'}}))
            args=SimpleNamespace(mirror=None)
            events=[]
            def guest(source,**kwargs):
                if 'windows-edge.ps1' in source:
                    if '-ExecutionPolicy Bypass -File' not in source:
                        raise Infrastructure('Guest execution policy refuses script')
                    events.append('edge-verify' if '-VerifyOnly' in source else 'edge-cache')
                return ''
            def copy(source,destination,**kwargs):
                destination.write_text(json.dumps({'webview2':'156.0.4314.50','msedgedriver':'156.0.4314.50'}))
            def quiet(mode,out):
                events.append(mode)
                (out/('quiet-'+mode.lower()+'.json')).write_text(json.dumps({'verified':mode=='Verify'}))
            def stop():events.append('stop');return True
            with patch.object(wrapper.vm,'ROOT',root),patch.object(wrapper.vm,'wait_free'),patch.object(wrapper,'wait_for',return_value=True), \
                 patch.object(wrapper,'start_snapshot'),patch.object(wrapper.vm,'require_owner',return_value=started), \
                 patch.object(wrapper.vm,'wait_ssh'),patch.object(wrapper,'WriteBudget') as budget, \
                 patch.object(wrapper,'stage_inputs'),patch.object(wrapper,'guest_ps',side_effect=guest), \
                 patch.object(wrapper,'copy_out',side_effect=copy),patch.object(wrapper,'quiet_guest',side_effect=quiet), \
                 patch.object(wrapper,'verify_crash_access'),patch.object(wrapper,'prepare_viewer',side_effect=lambda out,verify=False:events.append('viewer-verify' if verify else 'viewer-configure')),patch.object(wrapper.vm,'stop',side_effect=stop), \
                 patch.object(wrapper.vm,'wait_host_memory'),patch.object(wrapper.vm,'locked',side_effect=lambda:contextlib.nullcontext(Mock())), \
                 patch.object(wrapper.vm,'ensure_free'),patch.object(wrapper.vm,'launch'), \
                 patch.object(wrapper.vm,'read_state',return_value=None),patch.object(wrapper,'pins',return_value={}):
                budget.return_value.finish.side_effect=lambda:{'overlayInitialBytes':0}
                budget.return_value.error=None
                self.assertEqual(wrapper.prepare_setup(args,root/'out',root/'source'),0,(root/'out/summary.json').read_text())
            self.assertEqual(events,['viewer-configure','Apply','edge-cache','stop','Verify','viewer-verify','edge-verify','stop'])
            manifest=json.loads((root/'out/manifest.json').read_text())
            self.assertEqual(manifest['kind'],'application')
            self.assertEqual(json.loads((root/'out/checkpoint.json').read_text()),{'retained':'actual-installed-state'})
            self.assertEqual(manifest['webview2'],'156.0.4314.50')
            self.assertEqual(manifest['edgeDriver']['msedgedriver'],manifest['webview2'])

    def test_crash_access_requires_the_limited_task_success_receipt(self):
        for verified in (False,True):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                def copied(source,destination):
                    destination.write_text(json.dumps({'verified':verified,'elevated':False,'error':None if verified else 'Access denied'}))
                with patch.object(wrapper.vm,'copy_in'),patch.object(wrapper,'guest_ps',return_value='True') as guest, \
                     patch.object(wrapper,'copy_out',side_effect=copied):
                    if verified:
                        wrapper.verify_crash_access(root)
                    else:
                        with self.assertRaisesRegex(Infrastructure,'cannot read crash archives'):
                            wrapper.verify_crash_access(root)
                self.assertTrue(any('-RunLevel Limited' in call.args[0] for call in guest.call_args_list))
                self.assertTrue(any('Unregister-ScheduledTask' in call.args[0] for call in guest.call_args_list))

    def test_application_snapshot_keeps_suspended_mirror_for_later_update(self):
        for failed_effect in (False,True):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp);disk=root/'run.qcow2';disk.write_bytes(b'installed state')
                (root/'run-vars.fd').write_bytes(b'firmware')
                snapshot=root/'source';snapshot.mkdir()
                (snapshot/'manifest.json').write_text(json.dumps({'kind':'tools','pins':{}}))
                (snapshot/'quiet-verify.json').write_text(json.dumps({'verified':True}))
                started={'disk':str(disk),'pid':999999999,'owner':'fixture'};current=[started]
                args=SimpleNamespace(session='vm',mode='fresh',mirror=Path('mirror'))
                def execute(args,out,*rest):
                    (out/'summary.json').write_text(json.dumps({'journeys':[],'fullSelection':False}))
                    (out/'checkpoint.json').write_text('{}')
                    return 0
                def stop():current[0]=None;return True
                with patch.object(wrapper.vm,'ROOT',root),patch.object(wrapper,'pins',return_value={}),patch.object(wrapper.vm,'wait_free'), \
                     patch.object(wrapper,'wait_for',return_value=True),patch.object(wrapper,'start_snapshot'), \
                     patch.object(wrapper.vm,'require_owner',return_value=started),patch.object(wrapper.vm,'wait_ssh'), \
                     patch.object(wrapper.vm,'read_state',side_effect=lambda:current[0]), \
                     patch.object(wrapper,'update_observation',return_value={'time':'2026-10-29T00:00:00Z'}) as updates,patch.object(wrapper,'verify_update_effect') as effects, \
                     patch.object(wrapper,'guest_ps',return_value='True') as guest,patch.object(wrapper.vm,'mirror') as mirror, \
                     patch.object(wrapper,'WriteBudget') as budget,patch.object(wrapper,'execute',side_effect=execute), \
                     patch.object(wrapper.vm,'locked',return_value=contextlib.nullcontext()), \
                     patch.object(wrapper.vm,'ensure_free'),patch.object(wrapper.vm,'stop',side_effect=stop):
                    effects.side_effect=[None,Infrastructure('Windows Update download folder changed')] if failed_effect else None
                    budget.return_value.finish.return_value={};budget.return_value.initial=0;budget.return_value.error=None
                    self.assertEqual(wrapper.run_overlay(args,root/'out','candidate',['J1'],snapshot,seal=True),0)
                self.assertTrue((root/'out/snapshot/disk.qcow2').is_file())
                mirror.assert_not_called()
                self.assertEqual(updates.call_count,2)
                self.assertEqual(effects.call_count,2)
                self.assertIn('after',json.loads((root/'out/update-activity.json').read_text()))
                self.assertEqual(any('mirror-windows.py suspend' in call.args[0] for call in guest.call_args_list),not failed_effect)
                report=json.loads((root/'out/summary.json').read_text())
                self.assertEqual(report['counts']['failed-infrastructure'],1 if failed_effect else 0)

    def test_write_limit_uses_written_bytes_and_overlay_growth_independently(self):
        for key in ('hostQemuWrittenBytes', 'guestWrittenBytes', 'overlayGrowthBytes'):
            with self.subTest(key=key):
                wrapper.enforce_write_limit({key: 15_000_000_000})
                with self.assertRaisesRegex(Infrastructure, '15 GB'):
                    wrapper.enforce_write_limit({key: 15_000_000_001})

    def test_budget_watcher_stops_only_its_exact_owned_vm_and_records_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);disk=root/'disk.qcow2';disk.write_bytes(b'thin')
            owned={'pid':123,'disk':str(disk),'owner':'fixture','start_ticks':'1'}
            for current in (owned,dict(owned,pid=456)):
                budget=wrapper.WriteBudget(owned,root)
                with patch.object(wrapper.vm,'read_state',return_value=current),patch.object(wrapper.vm,'visible_process',return_value=True),patch.object(budget,'sample',side_effect=Infrastructure('15 GB write budget exceeded')),patch.object(wrapper.vm,'stop') as stop:
                    budget.watch()
                self.assertEqual(stop.call_count,1 if current==owned else 0)
                if current==owned:
                    self.assertTrue((root/'write-budget-exceeded.json').is_file())
                    with self.assertRaisesRegex(Infrastructure,'15 GB'):budget.check()

    def test_code_staging_does_not_recopy_unchanged_tools_corpus_or_packages(self):
        args=SimpleNamespace()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(wrapper,'guest_ps',return_value='{}'),patch.object(wrapper.vm,'copy_in') as copy:
                wrapper.stage_inputs(args,root,root/'snapshot')
            self.assertEqual(copy.call_count,1)
            # The code-only archive never includes immutable input directories.
            self.assertEqual(copy.call_args.args[2], 'C:/Qual/input.tar')

    def test_unchanged_source_does_not_even_transfer_an_archive(self):
        hashes={p.name:wrapper.digest(p) for p in wrapper.HERE.iterdir() if p.suffix in ('.py','.json','.ps1')}
        hashes['../mirror-windows.py']=wrapper.digest(wrapper.HERE.parent/'mirror-windows.py')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(wrapper,'guest_ps',return_value=json.dumps(hashes)),patch.object(wrapper.vm,'copy_in') as copy:
                wrapper.stage_inputs(SimpleNamespace(),root,root/'snapshot')
            copy.assert_not_called()

    def test_fresh_run_requires_verified_tools_snapshot_before_vm_start(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);snapshot=root/'snapshot';snapshot.mkdir()
            (snapshot/'manifest.json').write_text(json.dumps({'kind':'tools','pins':{}}))
            (snapshot/'quiet-verify.json').write_text(json.dumps({'verified':False}))
            args=SimpleNamespace(session='vm',mode='fresh',mirror=None)
            with patch.object(wrapper,'pins',return_value={}),patch.object(wrapper.vm,'start') as start,patch.object(wrapper,'start_snapshot') as resume:
                self.assertEqual(wrapper.run_overlay(args,root/'out','candidate',['J1'],snapshot),1)
            start.assert_not_called();resume.assert_not_called()
            self.assertIn('write settings', (root/'out/wrapper-error.txt').read_text())

    def test_wrong_snapshot_pins_fail_before_starting_any_vm(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);snapshot=root/'snapshot';snapshot.mkdir();(snapshot/'manifest.json').write_text(json.dumps({'pins':{'wrong':'bytes'}}))
            args=SimpleNamespace(session='vm',mode='rerun',mirror=None)
            with patch.object(wrapper,'pins',return_value={'actual':'bytes'}),patch.object(wrapper.vm,'start') as start,patch.object(wrapper,'start_snapshot') as resume:
                self.assertEqual(wrapper.run_overlay(args,root/'out','candidate',['J4'],snapshot),1)
            start.assert_not_called();resume.assert_not_called()
            summary=json.loads((root/'out/summary.json').read_text())
            self.assertFalse(summary['passed'])
            self.assertGreater(summary['counts']['unavailable'],0)

    def test_snapshot_sealing_renames_disk_and_records_pins_without_copying_gigabytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'out';out.mkdir();disk=root/'run.qcow2';disk.write_bytes(b'expensive setup');inode=disk.stat().st_ino
            (root/'run-vars.fd').write_bytes(b'firmware');(out/'checkpoint.json').write_text('{}')
            (out/'summary.json').write_text('{}')
            (out/'quiet-verify.json').write_text(json.dumps({'verified':True}))
            with patch.object(wrapper.vm,'ROOT',root),patch.object(wrapper.vm,'locked',return_value=contextlib.nullcontext()),patch.object(wrapper.vm,'ensure_free'),patch.object(wrapper,'pins',return_value={'sha':'exact'}):
                wrapper.seal_snapshot(out,{'disk':str(disk)},inode,None,'candidate')
            self.assertFalse(disk.exists())
            self.assertEqual((out/'snapshot/disk.qcow2').stat().st_ino,inode)
            self.assertEqual(json.loads((out/'snapshot/manifest.json').read_text())['pins'],{'sha':'exact'})
            self.assertTrue(json.loads((out/'snapshot/quiet-verify.json').read_text())['verified'])

    def test_preparation_failure_stops_only_the_run_it_started(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);disk=root/'disk.qcow2';disk.write_bytes(b'disk')
            started={'os':'windows','pid':999999999,'disk':str(disk),'owner':'fixture'}
            args=SimpleNamespace(session='vm',mode='fresh',mirror=None)
            snapshot=root/'snapshot';snapshot.mkdir();(snapshot/'manifest.json').write_text(json.dumps({'kind':'application','pins':{}}))
            (snapshot/'quiet-verify.json').write_text(json.dumps({'verified':True}))
            for same in (True,False):
                disk.write_bytes(b'disk')
                out=root/str(same)
                with patch.object(wrapper.vm,'ROOT',root),patch.object(wrapper,'pins',return_value={}),patch.object(wrapper.vm,'wait_free'),patch.object(wrapper,'wait_for',return_value=True),patch.object(wrapper,'start_snapshot'),patch.object(wrapper.vm,'require_owner',return_value=started),patch.object(wrapper.vm,'wait_ssh',side_effect=Infrastructure('SSH failed')),patch.object(wrapper.vm,'read_state',return_value=started if same else dict(started,pid=1)),patch.object(wrapper,'guest_ps',return_value='{}'),patch.object(wrapper,'WriteBudget') as budget,patch.object(wrapper.vm,'locked',return_value=contextlib.nullcontext()),patch.object(wrapper.vm,'ensure_free'),patch.object(wrapper.vm,'stop',return_value=True) as stop:
                    budget.return_value.finish.return_value={};budget.return_value.initial=0;budget.return_value.error=None
                    self.assertEqual(wrapper.run_overlay(args,out,'candidate',['J1'],snapshot),1)
                self.assertEqual(stop.call_count,1 if same else 0)

    def test_partial_rerun_cannot_become_full_qualification(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            source={'lane':'vm-windows','journeys':[{'id':'J4','checks':[{'name':'restart','status':'passed','evidence':['restart.json']}]}],
                    'writeVolume':{'guestWrittenBytes':123,'hostQemuWrittenBytes':456,'overlayGrowthBytes':78}}
            args=SimpleNamespace(session='vm',mode='rerun',journeys=['J4'])
            with patch.object(wrapper,'full_selection',return_value=True):
                self.assertEqual(wrapper.combine(args,root,{'rerun':source}),1)
            report=json.loads((root/'summary.json').read_text())
            self.assertFalse(report['execution']['releaseQualification'])
            self.assertEqual(report['status'],'incomplete')
            self.assertEqual(report['journeys'][0]['checks'][0]['evidence'],['rerun/evidence/restart.json'])
            self.assertEqual(report['writeVolume']['hostQemuWrittenBytes'],456)
            self.assertEqual(report['writeVolume']['guestWrittenBytes'],123)
            self.assertEqual(report['writeVolume']['overlayGrowthBytes'],78)

    def test_hosted_workflow_is_dispatch_only_read_only_guarded_and_advisory(self):
        # The real YAML is read/parsed separately as acceptance; these guard the
        # policy and executable command contract without adding a YAML dependency.
        repo=Path(__file__).resolve().parents[4]
        workflow=(repo/'.github/workflows/qualify-release-windows.yml').read_text()
        self.assertIn("if ($env:RUNNER_ENVIRONMENT -cne 'github-hosted')",workflow)
        self.assertIn('contents: read',workflow)
        self.assertIn('verify_assets(root',workflow)
        self.assertNotIn('gh workflow run',workflow)
        self.assertNotIn('secrets.',workflow)
        policy=json.loads((repo/'scripts/ci/workflow-signal-policy.v1.json').read_text())
        row=next(w for w in policy['workflows'] if w['path']=='.github/workflows/qualify-release-windows.yml')
        self.assertEqual(row['expectedTriggers'],['workflow_dispatch'])
        self.assertTrue(row['advisory']);self.assertFalse(row['blocking'])


class InstallerApprovalTests(unittest.TestCase):
    def test_only_exact_release_paths_and_checksums_reach_the_highest_installer_task(self):
        args=SimpleNamespace(package='candidate.exe',assets=Path('assets'),predecessor=Path('parent.exe'))
        nonce='a'*32
        with patch.object(wrapper,'digest',side_effect=lambda p:'1'*64 if str(p)=='assets/candidate.exe' else '2'*64):
            self.assertEqual(wrapper.approved_installer(args,{'nonce':nonce,'path':'C:/Qual/assets/candidate.exe','sha256':'1'*64}),'install')
            self.assertEqual(wrapper.approved_installer(args,{'nonce':nonce,'path':'C:/Qual/predecessor/parent.exe','sha256':'2'*64}),'install')
            for request in ({'nonce':nonce,'path':'C:/Windows/cmd.exe','sha256':'1'*64},
                            {'nonce':nonce,'path':'C:/Qual/assets/candidate.exe','sha256':'3'*64},
                            {'nonce':'../escape','path':'C:/Qual/assets/candidate.exe','sha256':'1'*64}):
                with self.assertRaises(Infrastructure):wrapper.approved_installer(args,request)
