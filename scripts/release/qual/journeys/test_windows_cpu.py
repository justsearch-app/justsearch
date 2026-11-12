"""S22: a CPU install may truthfully report hardware limitations, never missing models.

Status shape observed through the installed app in w4b/j3-cpu. Windows product
AiInstallStatus documents installedFully=false for chat/CUDA hardware skips.
"""
import unittest
from unittest.mock import Mock, patch

import linux


def immediate(fn, description, *args, error=AssertionError, **kwargs):
    value = fn()
    if not value:
        raise error(description)
    return value


class WindowsCPUReadinessTests(unittest.TestCase):
    def status(self):
        return {'state': 'completed', 'phase': 'done', 'installedFully': False,
                'repairNeeded': False, 'readyCapabilities': ['retrieval-core'],
                'packages': [dict(packageId=name, state='installed', error='') for name in
                             ('embedding', 'splade', 'reranker', 'ner', 'citation-scorer')] +
                            [dict(packageId=name, state='skipped', skipCause='hardware', error='')
                             for name in ('chat', 'cuda-runtime')]}

    def run_ai(self, status, *, platform='windows', inactive=None):
        run = Mock(platform=platform, facts={})
        run.wd.execute.return_value = []
        run.wd.text.return_value = 'Chat is not supported here.'
        runtime = {'onnxFeatures': [
            {'id': name, 'modelActive': name != inactive}
            for name in ('embed', 'splade', 'reranker', 'citation_scorer')]}
        if inactive == 'splade':
            runtime['onnxFeatures'][1].update(status='unknown', reason='worker_not_answered')
        def api(path, *args):
            return {'/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                        {'id': 'chat', 'state': 'unavailable'}, {'id': 'embedding', 'state': 'installed'}]},
                    '/api/ai/install/manifest': {}, '/api/ai/install/status': status,
                    '/api/ai/runtime/status': runtime}[path]
        run.api.side_effect = api
        retrieval = {'results': [linux.OPEN_FILE]}
        with patch.object(linux, 'wait_for', immediate), patch.object(linux, 'activate_downloaded'), \
             patch.object(linux, 'semantic_search', return_value=retrieval) as semantic:
            result = linux.install_ai(run)
            semantic.assert_called_once_with(run)
            run.evidence.assert_any_call('J3-runtime', runtime)
        return result

    def test_complete_cpu_hardware_limitations_reach_semantic_search_and_runtime_checks(self):
        result = self.run_ai(self.status())
        self.assertFalse(result['installed']['installedFully'])
        self.assertEqual(len(result['runtime']['onnxFeatures']), 4)

    def test_limitations_never_cover_missing_failed_or_unclassified_packages(self):
        cases = []
        for name in ('embedding', 'splade', 'reranker', 'ner', 'citation-scorer'):
            for state in ('pending', 'failed', 'skipped'):
                value = self.status()
                next(p for p in value['packages'] if p['packageId'] == name).update(state=state, skipCause='hardware')
                cases.append(value)
        for change in ({'repairNeeded': True}, {'phase': 'activation_required'}, {'readyCapabilities': []}):
            cases.append(dict(self.status(), **change))
        for cause in ('', 'user-declined'):
            value = self.status();value['packages'][-1]['skipCause'] = cause;cases.append(value)
        value = self.status();value['packages'].pop(0);cases.append(value)
        value = self.status();value['packages'].append(dict(packageId='unexpected', state='skipped', skipCause='hardware'));cases.append(value)
        value = self.status();value['packages'][0]['error'] = 'checksum failed';cases.append(value)
        for value in cases:
            with self.subTest(status=value), self.assertRaisesRegex(AssertionError, 'Downloaded default CPU search encoders activated'):
                self.run_ai(value)

    def test_disabled_splade_allows_fresh_cpu_activation(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform):
                status = self.status()
                status['installedFully'] = platform == 'linux'
                result = self.run_ai(status, platform=platform, inactive='splade')
                self.assertEqual(result['runtime']['onnxFeatures'][1], {
                    'id': 'splade', 'status': 'unknown', 'reason': 'worker_not_answered',
                    'modelActive': False})

    def test_inactive_embed_does_not_satisfy_cpu_activation(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform), self.assertRaisesRegex(
                    AssertionError, 'Default CPU AI install|Downloaded default CPU search encoders activated'):
                status = self.status()
                status['installedFully'] = platform == 'linux'
                self.run_ai(status, platform=platform, inactive='embed')

    def test_default_off_reranker_allows_fresh_cpu_activation(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform):
                status = self.status()
                status['installedFully'] = platform == 'linux'
                result = self.run_ai(status, platform=platform, inactive='reranker')
                self.assertFalse(result['runtime']['onnxFeatures'][2]['modelActive'])

    def test_citation_scorer_remains_required(self):
        for platform in ('linux', 'windows'):
            with self.subTest(platform=platform), self.assertRaisesRegex(AssertionError, 'All default retrieval models active'):
                status = self.status()
                status['installedFully'] = platform == 'linux'
                self.run_ai(status, platform=platform, inactive='citation_scorer')

    def test_linux_completion_requirement_is_preserved(self):
        with self.assertRaisesRegex(AssertionError, 'Downloaded default CPU search encoders activated'):
            self.run_ai(self.status(), platform='linux')

    def test_existing_fully_installed_status_still_passes(self):
        value = self.status();value['installedFully'] = True
        self.assertTrue(self.run_ai(value)['installed']['installedFully'])
