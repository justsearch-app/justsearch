import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("build", Path(__file__).with_name("build-linux-gpu-packs.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class LinuxGpuBuildTest(unittest.TestCase):
    """S13 "Linux GPU packs are built and published by a pinned, reproducible workflow; the
    registry entries carry their exact sizes and SHA-256; nothing is public before the owner publishes".
    """
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()

    def test_download_and_reused_cache_are_verified_before_consumption(self):
        source = self.root / "input"
        source.write_bytes(b"pinned input")
        item = dict(id="fixture", url=source.as_uri(), sha256=hashlib.sha256(source.read_bytes()).hexdigest())
        cached = build.fetch(item, self.cache)
        self.assertEqual(source.read_bytes(), cached.read_bytes())
        self.assertEqual(cached, build.fetch(item, self.cache))
        cached.write_bytes(b"modified cached input")
        with self.assertRaisesRegex(ValueError, "Cached SHA-256 mismatch"):
            build.fetch(item, self.cache)
        cached.unlink()
        source.write_bytes(b"modified upstream")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            build.fetch(item, self.cache)
        self.assertFalse(cached.exists())

    def test_archive_cannot_write_outside_its_private_tree(self):
        archive = self.root / "unsafe.tar"
        with tarfile.open(archive, "w") as tar:
            item = tarfile.TarInfo("../escaped")
            item.size = 3
            tar.addfile(item, io.BytesIO(b"bad"))
        with self.assertRaises(tarfile.FilterError):
            build.unpack(archive, self.root / "tree", dict(id="llama", sha256="a" * 64))
        self.assertFalse((self.root / "escaped").exists())

    def test_all_runtime_and_build_inputs_are_pinned_to_official_channels(self):
        pins = json.loads(build.PINS.read_text())["inputs"]
        self.assertEqual(len(pins), len({item["id"] for item in pins}))
        for item in pins:
            self.assertRegex(item["sha256"], r"^[a-f0-9]{64}$")
            self.assertTrue(item["version"])
            if item["id"] == "llama":
                self.assertEqual("b8571", item["version"])
                self.assertRegex(item["commit"], r"^[a-f0-9]{40}$")
                self.assertTrue(item["url"].endswith(item["commit"]))
            elif item["id"] == "ort":
                self.assertTrue(item["url"].startswith("https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"))
            elif item["id"] == "zlib":
                self.assertTrue(item["url"].startswith("https://archive.ubuntu.com/ubuntu/pool/main/z/zlib/"))
            else:
                self.assertTrue(item["url"].startswith("https://developer.download.nvidia.com/compute/"))
                self.assertIn("/redist/", item["url"])
        self.assertTrue({"llama", "ort", "cuda_nvcc", "cuda_cudart", "cuda_cccl", "cuda_nvrtc", "libcublas", "libcufft",
                         "libcurand", "libcusparse", "libcusolver", "libnvjitlink", "cudnn", "zlib"} <= {i["id"] for i in pins})


if __name__ == "__main__":
    unittest.main()
