"""Memory guard checks use synthetic procfs readings, never shared VMs."""
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import vm


class MemoryTests(unittest.TestCase):
    def test_default_is_twelve_gib_for_all_guests(self):
        self.assertEqual(vm.MEMORY_MIB, 12288)

    def test_exact_threshold_starts_without_waiting(self):
        with patch.object(Path, 'read_text', return_value='MemAvailable: 14680064 kB\n'), \
             patch.object(vm.time, 'sleep') as sleep:
            vm.wait_host_memory()
        sleep.assert_not_called()

    def test_low_memory_waits_then_continues_when_available(self):
        with patch.object(Path, 'read_text', side_effect=['MemAvailable: 14680063 kB\n',
                                                         'MemAvailable: 14680064 kB\n']), \
             patch.object(vm.time, 'monotonic', side_effect=[0, 0]), \
             patch.object(vm.time, 'sleep') as sleep:
            vm.wait_host_memory()
        sleep.assert_called_once_with(10)

    def test_low_memory_refuses_launch_after_fifteen_minutes(self):
        with patch.object(vm, 'owner', return_value='own-run'), patch.object(vm, 'ensure_free'), \
             patch.object(Path, 'read_text', return_value='MemAvailable: 14680063 kB\n'), \
             patch.object(vm.time, 'monotonic', side_effect=[0, 900]), \
             patch.object(vm.time, 'sleep') as sleep, patch.object(vm.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError, '14 GiB.*900'):
                vm.launch('ubuntu', Path('own.qcow2'), Path('own.fd'), lease=123)
        spawn.assert_not_called()
        sleep.assert_not_called()

    def test_missing_available_memory_refuses_instead_of_guessing(self):
        with patch.object(Path, 'read_text', return_value='MemFree: 20000000 kB\n'):
            with self.assertRaisesRegex(RuntimeError, 'MemAvailable'):
                vm.wait_host_memory()


if __name__ == '__main__':
    unittest.main()
