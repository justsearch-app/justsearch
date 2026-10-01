"""Bounded stage-E startup gate using jseval's exact measurement preflight."""
import argparse
import json
import time
from pathlib import Path

from jseval.preflight import assert_capabilities, derive_intended_engines


def wait_ready(base_url, timeout, *, check=assert_capabilities, clock=time.monotonic,
               pause=time.sleep):
    intended = derive_intended_engines('lexical,hybrid', cross_encoder=True)
    deadline = clock() + timeout
    attempts = []
    while clock() < deadline:
        verdict = check(base_url, intended, timeout=min(10, deadline - clock()))
        attempts.append(verdict)
        if verdict['ok']:
            return {'ok': True, 'intended': sorted(intended), 'attempts': attempts}
        pause(min(2, max(0, deadline - clock())))
    return {'ok': False, 'intended': sorted(intended), 'attempts': attempts}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--timeout', type=float, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = wait_ready(args.base_url, args.timeout)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    if not result['ok']:
        raise SystemExit('Capability readiness deadline: ' + json.dumps(result['attempts'][-1:]))
