"""The Windows producer's SHA256SUMS headers are metadata, never checksum passes."""
from pathlib import Path
import tempfile
import unittest
from runner import Infrastructure, digest, verify_assets


class WindowsChecksumTests(unittest.TestCase):
    def test_windows_producer_comments_blanks_and_utf8_bom_preserve_byte_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);exe=root/'candidate-setup.exe';exe.write_bytes(b'MZexact package')
            (root/'SHA256SUMS').write_text('# SHA-256 checksums for JustSearch release assets.\n\n'+digest(exe)+'  '+exe.name+'\n',encoding='utf-8-sig')
            self.assertEqual(verify_assets(root,[exe.name]),{exe.name:digest(exe)})
            exe.write_bytes(b'MZtampered package')
            with self.assertRaisesRegex(Infrastructure,'mismatch'):verify_assets(root,[exe.name])

    def test_comment_only_or_malformed_manifest_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);exe=root/'candidate-setup.exe';exe.write_bytes(b'MZpackage')
            for manifest in ('# no checksum present\n','not a checksum\n'):
                (root/'SHA256SUMS').write_text(manifest)
                with self.assertRaises(Infrastructure):verify_assets(root,[exe.name])
