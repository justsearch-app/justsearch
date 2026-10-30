"""Isolation metadata and independent J9 stage progress; no shared resources."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import gpu
import runner
from test_gpu_isolation import host


def status(completed=1, pending=100):
    return {'worker': {'core': {'indexedDocuments': completed, 'pendingJobs': pending},
        'enrichment': {'embeddingEnabled': True, 'spladeEnabled': True, 'nerEnabled': True,
            'embeddingCompletedCount': completed, 'embeddingPendingCount': pending,
            'spladeCompletedCount': completed, 'spladePendingCount': pending,
            'completedNerCount': completed, 'pendingNerCount': pending,
            'chunk': {'chunkEmbeddingCompletedCount': completed, 'chunkEmbeddingPendingCount': pending,
                'chunkSpladeEnabled': True, 'chunkSpladeCompletedCount': completed,
                'chunkSpladePendingCount': pending}}}}


def observe(tracker, seconds, sample):
    # Exercise the original aggregate on the baseline to demonstrate its gap.
    if hasattr(gpu, 'progress_stages'):
        progress = gpu.progress_stages(sample)
    else:
        worker = sample['worker']
        progress = worker['core']['indexedDocuments'] + sum(worker['enrichment'][k] for k in
            ('embeddingCompletedCount', 'spladeCompletedCount', 'completedNerCount'))
    tracker.observe(seconds, progress, 100, 200, 'engine')


class StageProgressTests(unittest.TestCase):
    def test_each_pending_stage_must_advance_despite_other_progress(self):
        paths = [('indexing', 'core', 'indexedDocuments'),
                 ('document embedding', 'enrichment', 'embeddingCompletedCount'),
                 ('document SPLADE', 'enrichment', 'spladeCompletedCount'),
                 ('NER', 'enrichment', 'completedNerCount'),
                 ('chunk embedding', 'chunk', 'chunkEmbeddingCompletedCount'),
                 ('chunk SPLADE', 'chunk', 'chunkSpladeCompletedCount')]
        for name, section, key in paths:
            with self.subTest(stage=name):
                tracker = gpu.SoakProgress(0)
                initial = status()
                if name != 'indexing':
                    initial['worker']['core']['pendingJobs'] = 0
                observe(tracker, 0, initial)
                with self.assertRaisesRegex(runner.ProductFailure,
                        name + r'.*completed=1.*pending=100.*last advance.*0'):
                    for seconds in range(30, 631, 30):
                        sample = status(seconds)
                        if name != 'indexing':
                            sample['worker']['core']['pendingJobs'] = 0
                        target = sample['worker']['enrichment']['chunk'] if section == 'chunk' else sample['worker'][section]
                        target[key] = 1
                        observe(tracker, seconds, sample)

    def test_all_pending_stages_advance_for_full_hour(self):
        tracker = gpu.SoakProgress(0)
        for seconds in range(0, 3601, 30):
            observe(tracker, seconds, status(seconds))
        self.assertEqual(tracker.complete(3600)['seconds'], 3600)

    def test_twenty_minute_indexing_then_advancing_enrichment_passes(self):
        tracker = gpu.SoakProgress(0)
        for seconds in range(0, 3601, 30):
            sample = status(1 if seconds < 1200 else seconds - 1199)
            sample['worker']['core'] = {'indexedDocuments': min(seconds, 1200),
                                       'pendingJobs': 100 if seconds < 1200 else 0}
            observe(tracker, seconds, sample)
        self.assertEqual(tracker.complete(3600)['seconds'], 3600)
        self.assertEqual(tracker.stages['chunk embedding']['pendingSinceSeconds'], 1200)

    def test_stuck_enrichment_gets_ten_minutes_after_indexing_goes_idle(self):
        tracker = gpu.SoakProgress(0)
        for seconds in range(0, 1801, 30):
            sample = status()
            sample['worker']['core'] = {'indexedDocuments': min(seconds, 1200),
                                       'pendingJobs': 100 if seconds < 1200 else 0}
            observe(tracker, seconds, sample)
        with self.assertRaisesRegex(runner.ProductFailure,
                r'chunk embedding: completed=1, pending=100, last advance at soak \+0.000s.*indexing idle since \+1200.000s'):
            observe(tracker, 1801, sample)

    def test_resumed_indexing_resets_enrichment_window(self):
        tracker = gpu.SoakProgress(0)
        for seconds in (0, 600, 900, 1500, 2100):
            sample = status()
            sample['worker']['core'] = {'indexedDocuments': seconds,
                                       'pendingJobs': 100 if seconds == 900 else 0}
            observe(tracker, seconds, sample)
        with self.assertRaisesRegex(runner.ProductFailure, r'indexing idle since \+1500.000s'):
            observe(tracker, 2101, sample)

    def test_inflight_indexing_defers_enrichment_window(self):
        for field in ('pendingVduCount', 'writerQueueDepth', 'writerPendingDocs',
                      'pendingJobsCount', 'processingJobsCount', 'migrationEnumerator'):
            with self.subTest(field=field):
                tracker = gpu.SoakProgress(0)
                for seconds in range(0, 1201, 30):
                    sample = status()
                    core = sample['worker']['core']
                    core.update(indexedDocuments=seconds, pendingJobs=0)
                    if field in ('pendingVduCount', 'writerQueueDepth', 'writerPendingDocs'):
                        core[field] = 1
                    else:
                        sample['worker']['migration'] = {field: {'running': True} if field == 'migrationEnumerator' else 1}
                    observe(tracker, seconds, sample)

    def test_new_enrichment_pending_work_after_indexing_idle_gets_own_window(self):
        tracker = gpu.SoakProgress(0)
        observe(tracker, 0, status(1, 0))
        sample = status()
        sample['worker']['core']['pendingJobs'] = 0
        observe(tracker, 1000, sample)
        observe(tracker, 1600, sample)
        with self.assertRaises(runner.ProductFailure):
            observe(tracker, 1601, sample)

    def test_disabled_or_without_pending_work_is_exempt(self):
        for disabled in (False, True):
            with self.subTest(disabled=disabled):
                tracker = gpu.SoakProgress(0)
                for seconds in range(0, 3601, 30):
                    sample = status(1, 0)
                    if disabled:
                        sample = status(1, 100)
                        sample['worker']['core'] = {'indexedDocuments': seconds, 'pendingJobs': 0}
                        e = sample['worker']['enrichment']
                        e.update(embeddingEnabled=False, spladeEnabled=False, nerEnabled=False)
                        e['chunk']['chunkSpladeEnabled'] = False
                    observe(tracker, seconds, sample)
                tracker.complete(3600)

    def test_new_pending_work_gets_its_own_window(self):
        tracker = gpu.SoakProgress(0)
        observe(tracker, 0, status(1, 0))
        observe(tracker, 1000, status())
        observe(tracker, 1600, status())
        with self.assertRaises(runner.ProductFailure):
            observe(tracker, 1601, status())

    def test_advance_after_ten_minute_gap_still_fails(self):
        tracker = gpu.SoakProgress(0)
        observe(tracker, 0, status())
        with self.assertRaises(runner.ProductFailure):
            observe(tracker, 601, status(2))

    def test_disabled_missing_counters_can_later_be_enabled(self):
        tracker = gpu.SoakProgress(0)
        sample = status()
        sample['worker']['enrichment'].update(embeddingEnabled=False)
        del sample['worker']['enrichment']['chunk']['chunkEmbeddingCompletedCount']
        observe(tracker, 0, sample)
        observe(tracker, 30, status(2))

    def test_missing_enabled_stage_counter_is_infrastructure(self):
        sample = status()
        del sample['worker']['enrichment']['chunk']['chunkEmbeddingPendingCount']
        with self.assertRaises(runner.Infrastructure):
            observe(gpu.SoakProgress(0), 0, sample)


class OwnerMetadataTests(unittest.TestCase):
    def wrapper(self, home, out, during):
        argv = ['gpu-host.py', '--evidence', str(out), '--assets', str(out.parent),
                '--package', 'candidate.deb', '--source', str(out.parent),
                '--soak-corpus', str(out.parent), '--journeys', 'J1', 'J2']
        def attempt(*args):
            during()
            return {'journeys': [], 'fullSelection': False}, False
        with patch.object(sys, 'argv', argv), patch.object(Path, 'home', return_value=home), \
                patch.dict(os.environ, XDG_DATA_HOME=str(home / '.local/share')), \
                patch.object(host, 'mount_device', return_value='unit'), \
                patch.object(gpu, 'device_writes', return_value=0), \
                patch.object(host, 'run_attempt', side_effect=attempt):
            host.main()
        return json.loads((out / 'summary.json').read_text())

    def test_each_owner_root_directory_mtime_is_checked_after_cleanup(self):
        for relative in ('.justsearch', '.local/share/io.justsearch.shell',
                         '.local/share/io.justsearch.other', '.config/io.justsearch.shell',
                         '.cache/io.justsearch.shell'):
            with self.subTest(root=relative), tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp) / 'owner'
                for name in (relative, '.local/share/io.justsearch.shell'):
                    (home / name).mkdir(parents=True, exist_ok=True)
                root = home / relative
                def mutate():
                    info = root.stat()
                    os.utime(root, ns=(info.st_atime_ns, info.st_mtime_ns + 1_000_000))
                report = self.wrapper(home, Path(tmp) / 'evidence', mutate)
                self.assertTrue(any(c['status'] == 'failed-infrastructure' and 'metadata' in c['name']
                    for j in report['journeys'] for c in j['checks']), report)

    def test_wrapper_never_creates_owner_sentinel_or_reads_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / 'owner'
            data = home / '.local/share/io.justsearch.shell'
            data.mkdir(parents=True)
            original = data.stat().st_mtime_ns
            def untouched():
                self.assertEqual(list(data.iterdir()), [])
            with patch.object(Path, 'read_bytes', side_effect=AssertionError('owner contents read')):
                report = self.wrapper(home, Path(tmp) / 'evidence', untouched)
            self.assertEqual(data.stat().st_mtime_ns, original)
            self.assertFalse(any(c['status'].startswith('failed') for j in report['journeys'] for c in j['checks']))


if __name__ == '__main__':
    unittest.main()
