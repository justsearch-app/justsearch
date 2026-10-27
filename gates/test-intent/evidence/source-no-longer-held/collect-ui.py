#!/usr/bin/env python3
"""Summarize the unedited Vitest reports and validate assertion failures and preservation."""
import hashlib
import json
from pathlib import Path

folder = Path(__file__).resolve().parent
before = json.loads((folder / 'ui-before-results.json').read_text())
after = json.loads((folder / 'ui-after-results.json').read_text())
def cases(report):
    return {case['fullName']: case for file in report['testResults'] for case in file['assertionResults']}
old, new = cases(before), cases(after)
assert old.keys() == new.keys(), 'Before/after check selection differs'
assert all(case['status'] == 'passed' for case in new.values()), 'Candidate check did not pass'
failed = []
for name, case in old.items():
    assert case['status'] in ('passed', 'failed'), 'Skipped/unexecuted before check'
    if case['status'] == 'failed':
        message = '\n'.join(case.get('failureMessages', []))
        assert 'AssertionError' in message, 'Fail-before was not an assertion: ' + name
        failed.append({'name': name, 'failure': message.split('\n    at ')[0]})
summary = {
    'baselineRevision': 'bfd625c7eea6591250e5a3d231c25e7884aa4312',
    'reports': {name: hashlib.sha256((folder / name).read_bytes()).hexdigest()
                for name in ['ui-before-results.json', 'ui-after-results.json']},
    'before': {'passed': before['numPassedTests'], 'failed': before['numFailedTests'], 'skipped': before['numPendingTests']},
    'after': {'passed': after['numPassedTests'], 'failed': after['numFailedTests'], 'skipped': after['numPendingTests']},
    'assertionFailuresBefore': failed,
    'preservedChecks': [name for name, case in old.items() if case['status'] == 'passed'],
}
(folder / 'ui-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print('before:', summary['before'], 'after:', summary['after'])
