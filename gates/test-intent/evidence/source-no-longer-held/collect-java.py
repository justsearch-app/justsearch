#!/usr/bin/env python3
"""Capture actual JUnit counts without retaining large synthetic test stdout."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[4]
module = sys.argv[1]
results = repo / 'modules' / module / 'build/test-results/test'
files = sorted(results.glob('TEST-*.xml'))
if not files:
    raise SystemExit('No JUnit results for ' + module)
summary = {'module': module, 'observedUtc': subprocess.check_output(['date', '-u', '+%Y-%m-%dT%H:%M:%SZ'], text=True).strip(),
           'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
           'totals': dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0), 'suites': []}
for file in files:
    root = ET.parse(file).getroot()
    counts = {key: int(root.attrib[key]) for key in summary['totals']}
    for key, value in counts.items():
        summary['totals'][key] += value
    summary['suites'].append({'name': root.attrib['name'], **counts,
                              'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                              'failed': [case.attrib['name'] for case in root.findall('testcase') if case.find('failure') is not None or case.find('error') is not None],
                              'skippedCases': [case.attrib['name'] for case in root.findall('testcase') if case.find('skipped') is not None]})
out = Path(__file__).parent / ('java-' + module + '.json')
out.write_text(json.dumps(summary, indent=2) + '\n')
print(module, summary['totals'])
