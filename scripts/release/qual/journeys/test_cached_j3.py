"""A cached setup cannot provide fresh J3 activation evidence."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import linux
from runner import Run
from test_windows_cpu import immediate


class CachedJ3Tests(unittest.TestCase):
    def test_resumed_setup_reports_cpu_ai_unavailable(self):
        for platform in ('linux', 'windows'):
            for phase in ('idle', 'activation_required', 'done', 'failed'):
                with self.subTest(platform=platform, phase=phase), tempfile.TemporaryDirectory() as temp:
                    run = Mock(platform=platform, facts={'resumedSetup': True}, out=Path(temp))
                    run.wd.text.return_value = 'Chat is not supported here.'
                    run.wd.execute.return_value = []
                    runtime = {'onnxFeatures': [{'id': name, 'modelActive': True}
                               for name in ('embed', 'citation_scorer')]}
                    run.api.side_effect = lambda path: {
                        '/api/ai/install/plan-preview': {'downloadProfile': 'CPU', 'components': [
                            {'id': 'chat', 'state': 'unavailable'},
                            {'id': 'embedding', 'state': 'installed'}]},
                        '/api/ai/install/manifest': {},
                        '/api/ai/install/status': {'state': 'failed' if phase == 'failed' else 'completed',
                            'phase': phase, 'installedFully': True, 'readyCapabilities': ['retrieval-core']},
                        '/api/ai/runtime/status': runtime,
                    }[path]
                    journey = {'id': 'J3', 'checks': []}
                    with patch.object(linux, 'wait_for', side_effect=immediate) as wait, \
                         patch.object(linux, 'activate_downloaded') as activate, \
                         patch.object(linux, 'semantic_search', return_value={}) as search:
                        Run.check(run, journey, 'cpu-ai', lambda: linux.install_ai(run))
                    check = journey['checks'][0]
                    self.assertEqual(check['status'], 'unavailable')
                    self.assertEqual(check['reason'],
                        'cached setup: activation cannot be re-observed; run J3 on a fresh install')
                    run.evidence.assert_any_call('J3-resume-runtime', runtime)
                    run.wd.click.assert_not_called()
                    activate.assert_not_called()
                    search.assert_not_called()
                    wait.assert_not_called()
                    self.assertNotIn('ai', run.facts)


if __name__ == '__main__':
    unittest.main()
