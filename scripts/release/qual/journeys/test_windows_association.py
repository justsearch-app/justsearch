"""Disposable Windows viewer setup: native selection never substitutes for evidence."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

from runner import Infrastructure

spec=importlib.util.spec_from_file_location('native_association',Path(__file__).with_name('windows-association.py'))
association=importlib.util.module_from_spec(spec);spec.loader.exec_module(association)


class ViewerSetupTests(unittest.TestCase):
    def test_verified_existing_handler_needs_no_gui_or_reconfiguration(self):
        observed={'executable':r'C:\Windows\editor.exe','command':'registered command'}
        target,native=Mock(),Mock()
        with patch.object(association,'default_viewer',return_value=observed):
            result=association.configure(target,native,{'viewer':r'c:\WINDOWS\editor.exe'},Path('unused'))
        self.assertTrue(result['verified']);self.assertEqual(result['association'],observed)
        target.spawn.assert_not_called();native.automation.assert_not_called()

    def test_reboot_verification_refuses_another_handler_without_silently_repairing_it(self):
        target,native=Mock(),Mock()
        observed={'executable':r'C:\Windows\OpenWith.exe','command':'open chooser'}
        with patch.object(association,'default_viewer',return_value=observed),self.assertRaisesRegex(Infrastructure,'survive reboot'):
            association.configure(target,native,{'viewer':r'C:\Windows\editor.exe'},Path('unused'),verify=True)
        target.spawn.assert_not_called();native.automation.assert_not_called()

    def test_snapshot_viewer_task_requires_real_success_receipt_and_removes_its_task(self):
        spec=importlib.util.spec_from_file_location('association_wrapper',Path(__file__).with_name('vm-windows.py'))
        wrapper=importlib.util.module_from_spec(spec);spec.loader.exec_module(wrapper)
        import json
        for verified in (False,True):
            with tempfile.TemporaryDirectory() as temp:
                def copied(source,destination):destination.write_text(json.dumps({'verified':verified}))
                with patch.object(wrapper,'guest_ps',return_value='True') as guest,patch.object(wrapper,'copy_out',side_effect=copied):
                    if verified:wrapper.prepare_viewer(Path(temp),verify=True)
                    else:
                        with self.assertRaisesRegex(Infrastructure,'viewer verify failed'):wrapper.prepare_viewer(Path(temp),verify=True)
                self.assertTrue(any('-RunLevel Limited' in c.args[0] for c in guest.call_args_list))
                self.assertTrue(any('Unregister-ScheduledTask' in c.args[0] for c in guest.call_args_list))
