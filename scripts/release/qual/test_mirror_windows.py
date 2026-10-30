"""Windows mirror fixtures use their own files; no guest, CA store or socket."""
import contextlib
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('windows_mirror_fixture',Path(__file__).with_name('mirror-windows.py'))
mirror=importlib.util.module_from_spec(spec);spec.loader.exec_module(mirror)


class WindowsMirrorTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name)
        self.root=self.folder/'mirror';self.incoming=self.folder/'input';self.hosts=self.folder/'hosts'
        self.original=b'# original\r\n127.0.0.1 localhost\r\n';self.hosts.write_bytes(self.original)
        for name,value in [('ROOT',self.root),('INPUT',self.incoming),('HOSTS',self.hosts)]:
            patcher=patch.object(mirror,name,value);patcher.start();self.addCleanup(patcher.stop)
        self.ps=Mock();patcher=patch.object(mirror,'powershell',self.ps);patcher.start();self.addCleanup(patcher.stop)
        patcher=patch.object(mirror.subprocess,'run');patcher.start();self.addCleanup(patcher.stop)

    def prepare(self):
        self.incoming.mkdir()
        for name in ('ca.crt','server.crt','server.key'):(self.incoming/name).write_text('test fixture')
        payload=self.folder/'release.v2.json';payload.write_text('descriptor')
        with tarfile.open(self.incoming/'files.tar.gz','w:gz') as tar:
            tar.add(payload,arcname='justsearch-app/justsearch/releases/latest/download/release.v2.json')
        self.process={'pid':42,'created':'2026-10-29T09:00:00.0000000Z','exe':'C:/Qual/Python/python.exe','ppid':10}
        def command(source):
            if 'Import-Certificate' in source:return 'A'*40
            if 'Start-ScheduledTask' in source:(self.root/'ready.json').write_text(json.dumps(self.process))
            return ''
        self.ps.side_effect=command

    def test_setup_and_removal_restore_exact_hosts_and_remove_only_test_ca(self):
        self.prepare()
        with patch.object(mirror.socket,'create_connection',return_value=contextlib.nullcontext()):mirror.setup(False)
        self.assertIn(b'github.com',self.hosts.read_bytes())
        self.assertEqual(json.loads((self.root/'server.json').read_text())['process'],self.process)
        self.assertTrue((self.root/'www/justsearch-app/justsearch/releases/latest/download/release.v2.json').is_file())
        with patch.object(mirror,'ps_json',side_effect=[[self.process],[]]):mirror.remove()
        self.assertEqual(self.hosts.read_bytes(),self.original)
        self.assertFalse(self.root.exists())
        self.assertTrue(any('Cert:/LocalMachine/Root/'+('A'*40) in c.args[0] for c in self.ps.call_args_list))

    def test_untrusted_setup_does_not_import_ca(self):
        self.prepare()
        with patch.object(mirror.socket,'create_connection',return_value=contextlib.nullcontext()):mirror.setup(True)
        self.assertIsNone(json.loads((self.root/'server.json').read_text())['caThumbprint'])
        self.assertFalse(any('Import-Certificate' in c.args[0] for c in self.ps.call_args_list))

    def test_suspend_resume_reuses_payload_and_ca_and_checks_hosts_before_mutating(self):
        self.prepare()
        with patch.object(mirror.socket,'create_connection',return_value=contextlib.nullcontext()):mirror.setup(False)
        payload=self.root/'www/justsearch-app/justsearch/releases/latest/download/release.v2.json'
        inode=payload.stat().st_ino
        with patch.object(mirror,'ps_json',side_effect=[[self.process],[]]):mirror.suspend()
        self.assertEqual(self.hosts.read_bytes(),self.original)
        self.ps.reset_mock()
        with patch.object(mirror.socket,'create_connection',return_value=contextlib.nullcontext()):mirror.resume()
        self.assertEqual(payload.stat().st_ino,inode)
        self.assertIn(b'github.com',self.hosts.read_bytes())
        self.assertFalse(any('Import-Certificate' in c.args[0] for c in self.ps.call_args_list))
        with patch.object(mirror,'ps_json',side_effect=[[self.process],[]]):mirror.suspend()
        self.hosts.write_bytes(b'unrelated edits')
        self.ps.reset_mock()
        with self.assertRaisesRegex(RuntimeError,'hosts changed'):mirror.resume()
        self.ps.assert_not_called()

    def test_changed_hosts_are_preserved_before_any_cleanup_mutation(self):
        self.root.mkdir();(self.root/'hosts.before').write_bytes(self.original);(self.root/'hosts.mirror').write_bytes(b'expected')
        self.hosts.write_bytes(b'other session edits')
        with self.assertRaisesRegex(RuntimeError,'hosts changed'):mirror.remove()
        self.ps.assert_not_called()
        self.assertEqual(self.hosts.read_bytes(),b'other session edits')

    def test_reused_pid_is_never_stopped(self):
        self.root.mkdir();process={'pid':42,'created':'old','exe':'C:/Qual/Python/python.exe'}
        (self.root/'server.json').write_text(json.dumps({'process':process,'caThumbprint':None}))
        with patch.object(mirror,'ps_json',return_value=[dict(process,created='new')]):mirror.remove()
        self.assertFalse(any('Stop-Process' in c.args[0] for c in self.ps.call_args_list))
