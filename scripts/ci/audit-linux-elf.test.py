#!/usr/bin/env python3
"""Exercise the audit at real ELF/JAR/loader boundaries, with disposable C fixtures."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


AUDIT = Path(__file__).with_name("audit-linux-elf.py")


class ElfAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="js-elf-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def library(self, symbol_version, soname="libfixture.so"):
        (self.root / "fixture.c").write_text("int fixture(void) { return 0; }\n")
        (self.root / "versions.map").write_text(f"{symbol_version} {{ global: fixture; }};\n")
        subprocess.run(["cc", "-shared", "-fPIC", "fixture.c", "-Wl,--version-script=versions.map",
                        f"-Wl,-soname,{soname}", "-o", "libfixture.so"], cwd=self.root, check=True)
        (self.root / "main.c").write_text("extern int fixture(void); int main(void) { return fixture(); }\n")
        subprocess.run(["cc", "main.c", "-L.", "-lfixture", "-o", "probe"], cwd=self.root, check=True)
        return self.root / "probe"

    def audit(self, target):
        report = self.root / "report.json"
        result = subprocess.run([sys.executable, str(AUDIT), str(target), str(report)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return result.returncode, json.loads(report.read_text())

    def test_version_needs_above_each_ceiling_fail_and_supported_versions_pass(self):
        for symbol, expected in [("GLIBC_2.39", 0), ("GLIBC_2.40", 1),
                                 ("GLIBCXX_3.4.33", 0), ("GLIBCXX_3.4.34", 1)]:
            with self.subTest(symbol=symbol):
                code, report = self.audit(self.library(symbol))
                self.assertEqual(code, expected, report)
                if expected:
                    self.assertTrue(any(f"{symbol} exceeds" in error for error in report["errors"]), report)

    def test_unknown_missing_library_fails(self):
        probe = self.library("GLIBC_2.39")
        (self.root / "libfixture.so").unlink()
        code, report = self.audit(probe)
        self.assertEqual(code, 1, report)
        self.assertTrue(any("Unresolved library: libfixture.so" in error for error in report["errors"]), report)

    def test_jar_native_requirements_are_audited_and_foreign_variants_listed(self):
        probe = self.library("GLIBC_2.40")
        payload = self.root / "payload"
        payload.mkdir()
        with zipfile.ZipFile(payload / "natives.jar", "w") as archive:
            archive.write(probe, "native/linux-x86_64/probe")
            archive.write(self.root / "libfixture.so", "native/linux-x86_64/libfixture.so")
            archive.write(probe, "native/Linux-Musl/x86_64/probe")
            foreign = bytearray(probe.read_bytes())
            foreign[18:20] = (183).to_bytes(2, "little")
            archive.writestr("native/linux-aarch64/probe", foreign)
        code, report = self.audit(payload)
        self.assertEqual(code, 1, report)
        self.assertTrue(any("!/native/linux-x86_64/probe: GLIBC_2.40 exceeds" in error for error in report["errors"]), report)
        self.assertEqual(sum("ignored" in row for row in report["objects"]), 2)
        self.assertFalse(any("Unresolved library" in error for error in report["errors"]), report)

    def test_empty_payload_fails(self):
        code, report = self.audit(self.root)
        self.assertEqual(code, 1)
        self.assertIn("No ELF objects found", report["errors"])

    def test_gpu_pack_and_ort_java_loader_dependencies_are_report_only(self):
        # Deliberately nonexistent versions keep the fixture independent of host GPU packs.
        for soname in ["libcudart.so.999", "libcudnn.so.999", "libnvinfer.so.999", "libonnxruntime.so.1"]:
            with self.subTest(soname=soname):
                code, report = self.audit(self.library("GLIBC_2.39", soname))
                self.assertEqual(code, 0, report)
                # Hosts with GPU libraries may resolve them, but they stay visible.
                row = report["objects"][0]
                self.assertIn(soname, row["needed"])
                if soname in row["unresolved"]:
                    self.assertIn(soname, row["report_only"])


if __name__ == "__main__":
    unittest.main()
