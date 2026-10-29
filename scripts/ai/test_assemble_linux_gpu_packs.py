import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("assembler", Path(__file__).with_name("assemble-linux-gpu-packs.py"))
assembler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembler)


class LinuxGpuPackTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.patchelf = os.environ.get("PATCHELF", "patchelf")
        self.source = self.root / "input"
        self.source.mkdir()
        c = self.source / "dependency.c"
        c.write_text("int gpu_value(void) { return 42; }\n")
        subprocess.run(["gcc", "-shared", "-fPIC", "-Wl,-soname,libfixture_gpu.so.1", "-o", str(self.source / "libfixture_gpu.so.1.0"), str(c)], check=True)
        (self.source / "libfixture_gpu.so.1").symlink_to("libfixture_gpu.so.1.0")
        main = self.source / "main.c"
        main.write_text("#include <stdio.h>\nint gpu_value(void); int main(void) { printf(\"%d\\n\", gpu_value()); }\n")
        subprocess.run(["gcc", "-o", str(self.source / "llama-server"), str(main), "-L" + str(self.source), "-l:libfixture_gpu.so.1", "-Wl,-rpath," + str(self.source)], check=True)
        self.pack = self.root / "pack"
        assembler.copy_elf(self.source, self.pack, self.patchelf)

    def test_relocated_pack_materializes_soname_and_runs_without_original_inputs(self):
        shutil.rmtree(self.source)
        self.assertFalse((self.pack / "libfixture_gpu.so.1").is_symlink())
        self.assertEqual("42", assembler.run(str(self.pack / "llama-server")).strip())
        self.assertEqual(2, len(assembler.audit(self.pack)))
        output = self.root / "pack.zip"
        entry = assembler.archive(self.pack, output)
        self.assertEqual(output.stat().st_size, entry["sizeBytes"])
        with assembler.zipfile.ZipFile(output) as archive:
            self.assertIn("libfixture_gpu.so.1", archive.namelist())
            self.assertTrue(all(item.external_attr >> 16 & 0o170000 == 0o100000 for item in archive.infolist()))

    def test_ggml_dlopen_alias_is_materialized_without_input_symlinks(self):
        library = self.source / "libggml-cuda.so.0.9"
        subprocess.run(["gcc", "-shared", "-fPIC", "-Wl,-soname,libggml-cuda.so.0",
                        "-o", str(library), str(self.source / "dependency.c")], check=True)
        output = self.root / "ggml-pack"
        assembler.copy_elf(self.source, output, self.patchelf)
        for name in ("libggml-cuda.so", "libggml-cuda.so.0"):
            self.assertTrue((output / name).is_file())
            self.assertFalse((output / name).is_symlink())

    def test_missing_dependency_is_rejected_even_when_host_loader_can_find_it(self):
        (self.pack / "libfixture_gpu.so.1").unlink()
        with self.assertRaisesRegex(ValueError, "Dependency outside pack"):
            assembler.audit(self.pack)

    def test_rejects_absolute_runpath_and_high_glibc_floor(self):
        assembler.run(self.patchelf, "--set-rpath", str(self.source), str(self.pack / "llama-server"))
        with self.assertRaisesRegex(ValueError, "Non-relocatable"):
            assembler.audit(self.pack)
        assembler.run(self.patchelf, "--set-rpath", "$ORIGIN", str(self.pack / "llama-server"))
        with self.assertRaisesRegex(ValueError, "glibc floor exceeded"):
            assembler.audit(self.pack, (2, 1))


if __name__ == "__main__":
    unittest.main()
