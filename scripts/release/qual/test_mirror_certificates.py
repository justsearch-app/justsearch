"""Exercise the real test-CA producer; transport/guest calls stay mocked."""
import contextlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import vm


class MirrorCertificateTests(unittest.TestCase):
    def test_mirror_chain_meets_strict_ca_and_server_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);tree=root/'tree';tree.mkdir();(tree/'descriptor.json').write_text('{}')
            def copied(os_name, source, destination):
                source=Path(source)
                if source.name in ('ca.crt','server.crt'):shutil.copyfile(source,root/source.name)
            with patch.object(vm,'require_owner'),patch.object(vm,'copy_in',side_effect=copied),patch.object(vm,'guest_root'),patch.object(vm,'ssh'):
                vm.mirror('ubuntu',str(tree))
            result=subprocess.run(['openssl','verify','-x509_strict','-purpose','sslserver','-verify_hostname','github.com','-CAfile',str(root/'ca.crt'),str(root/'server.crt')],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            wrong=subprocess.run(['openssl','verify','-x509_strict','-verify_hostname','other.invalid','-CAfile',str(root/'ca.crt'),str(root/'server.crt')],capture_output=True,text=True)
            self.assertNotEqual(wrong.returncode,0)
            self.assertIn('hostname mismatch',wrong.stderr)
