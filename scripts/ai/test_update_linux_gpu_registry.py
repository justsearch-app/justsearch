import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("registry", Path(__file__).with_name("update-linux-gpu-registry.py"))
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class LinuxGpuRegistryTest(unittest.TestCase):
    """S13 "Linux GPU packs are built and published by a pinned, reproducible workflow; the
    registry entries carry their exact sizes and SHA-256; nothing is public before the owner publishes".
    """
    def setUp(self):
        self.original = json.loads((Path(__file__).resolve().parents[2] /
            "modules/configuration/src/main/resources/ai/model-registry.v2.json").read_text())
        self.hashes = "".join(str(i) * 64 + "  " + name + "\n" for i, name in enumerate(registry.FILENAMES, 1))
        self.sizes = "".join(str(i * 10000) + "  " + name + "\n" for i, name in enumerate(registry.FILENAMES, 1))

    def test_exact_final_coordinates_and_windows_entries_preserved(self):
        result = registry.update(self.original, "linux-cuda12-1", self.hashes, self.sizes)
        self.assertEqual(self.original["packages"], result["packages"][:-1])
        linux = result["packages"][-1]
        self.assertEqual("cuda-runtime", linux["replaces"])
        self.assertEqual("linux-x86_64", linux["nativeTarget"])
        for i, item in enumerate(linux["supportingFiles"], 1):
            self.assertEqual(i * 10000, item["sizeBytes"])
            self.assertEqual(str(i) * 64, item["sha256"])
            self.assertEqual("https://github.com/eliasjustus/justsearch-releases/releases/download/linux-cuda12-1/"
                             + registry.FILENAMES[i - 1], item["downloadUrl"])
            self.assertTrue(item["extract"])
        self.assertEqual(result, registry.update(result, "linux-cuda12-1", self.hashes, self.sizes))

    def test_rejects_partial_duplicate_unpinned_test_or_unsafe_inputs(self):
        for hashes, sizes, tag in [
            (self.hashes.splitlines()[0], self.sizes, "linux-cuda12-1"),
            (self.hashes + self.hashes, self.sizes, "linux-cuda12-1"),
            (self.hashes.replace("1" * 64, "placeholder"), self.sizes, "linux-cuda12-1"),
            (self.hashes.replace("llama-b8571", "TEST-ONLY-llama-b8571"), self.sizes, "linux-cuda12-1"),
            (self.hashes, self.sizes.replace("10000", "0"), "linux-cuda12-1"),
            (self.hashes, self.sizes, "../tag"),
            (self.hashes, self.sizes, "linux-cuda12-0"),
        ]:
            with self.subTest(hashes=hashes, sizes=sizes, tag=tag), self.assertRaises(ValueError):
                registry.update(self.original, tag, hashes, sizes)


if __name__ == "__main__":
    unittest.main()
