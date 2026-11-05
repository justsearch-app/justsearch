"""CPU confinement regressions using isolated Python processes, never an Engine."""
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import Mock

from click.testing import CliRunner
import pytest

from jseval.cli import main
from jseval.quality import inputs, run, sensitivity


@pytest.mark.parametrize('value', [None, '0', '-1', '3', 'invalid'])
def test_cpus_require_explicit_bounded_ort_threads_before_engine(monkeypatch, value):
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3})
    # An inherited value is not an explicit query-time override.
    monkeypatch.setenv('JUSTSEARCH_ORT_INTRA_OP_THREADS', '1')
    monkeypatch.setattr(run.backend, 'source_identity', lambda _: ('commit', 'dirty'))
    launch = Mock(side_effect=AssertionError('must refuse before Engine/admission'))
    monkeypatch.setattr(run.index, 'prepare_index', launch)
    monkeypatch.setattr(run.subprocess, 'Popen', launch)
    args = ['quality', 'sensitivity', 'scifact', '--cpus', '2,3']
    if value is not None:
        args += ['--set', 'JUSTSEARCH_ORT_INTRA_OP_THREADS='+value]
    result = CliRunner().invoke(main, args)
    assert result.exit_code == 2, result.output
    assert 'JUSTSEARCH_ORT_INTRA_OP_THREADS' in result.output, result.output
    if value is None or value == '3':
        assert '--cpus' in result.output and '--set' in result.output, result.output
    launch.assert_not_called()


@pytest.mark.parametrize('value', ['1', '2'])
def test_cpus_accept_explicit_ort_threads_within_cpu_count(monkeypatch, value):
    monkeypatch.setattr(os, 'sched_getaffinity', lambda _: {2, 3})
    monkeypatch.setattr(run.backend, 'source_identity', lambda _: ('commit', 'dirty'))
    result = CliRunner().invoke(main, ['quality', 'sensitivity', 'scifact', '--cpus', '2,3',
        '--set', 'JUSTSEARCH_ORT_INTRA_OP_THREADS='+value])
    assert result.exit_code == 2 and 'candidate tree is dirty' in result.output, result.output


@pytest.mark.parametrize('widen', [False, True])
def test_real_descendant_thread_affinity_is_checked(widen):
    allowed = sorted(os.sched_getaffinity(0))
    if len(allowed) < 2:
        pytest.skip('thread escape counterexample needs two allowed CPUs')
    requested = allowed[:1]
    worker_mask = allowed[:2] if widen else requested
    child_code = f'''
import ctypes, json, os, sys, threading
ctypes.CDLL(None).prctl(15, b"java", 0, 0, 0)
os.sched_setaffinity(0, {requested!r})
ready = threading.Event()
stop = threading.Event()
def worker():
    os.sched_setaffinity(0, {worker_mask!r})
    print(json.dumps({{"pid": os.getpid(), "tid": threading.get_native_id(),
                      "mask": sorted(os.sched_getaffinity(0))}}), flush=True)
    ready.set()
    stop.wait()
thread = threading.Thread(target=worker)
thread.start()
ready.wait()
sys.stdin.read()
stop.set()
thread.join()
'''
    parent_code = ('import os, subprocess, sys; '
                   f'os.sched_setaffinity(0, {requested!r}); '
                   f'sys.exit(subprocess.call([sys.executable, "-c", {child_code!r}], timeout=10))')
    parent_mask = os.sched_getaffinity(0)
    proc = subprocess.Popen([sys.executable, '-c', parent_code],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        thread = json.loads(proc.stdout.readline())
        handle = SimpleNamespace(_backend=SimpleNamespace(proc=proc))
        if widen:
            # Every process leader remains pinned; only the native worker escapes.
            assert sensitivity.psutil.Process(thread['pid']).cpu_affinity() == requested
            with pytest.raises(sensitivity.SensitivityRefused, match=rf"thread {thread['tid']}.*mask"):
                sensitivity.engine_cpus(handle, requested)
        else:
            assert sensitivity.engine_cpus(handle, requested) == requested
        check = {}
        if widen:
            with pytest.raises(sensitivity.SensitivityRefused, match=rf"thread {thread['tid']}.*mask"):
                sensitivity.engine_cpus(handle, requested, check)
        else:
            assert sensitivity.engine_cpus(handle, requested, check) == requested
        assert check['ok'] is not widen
        rows = {(row['pid'], row['tid']): row['mask'] for row in check['threads']}
        assert (proc.pid, proc.pid) in rows
        assert (thread['pid'], thread['pid']) in rows
        assert rows[thread['pid'], thread['tid']] == ','.join(map(str, worker_mask)) or (
            rows[thread['pid'], thread['tid']] == f'{worker_mask[0]}-{worker_mask[-1]}')
    finally:
        proc.communicate(timeout=10)
    assert proc.returncode == 0
    assert os.sched_getaffinity(0) == parent_mask


@pytest.mark.parametrize('status', ['', 'Cpus_allowed_list:\t\n', 'Cpus_allowed_list:\tinvalid\n',
                                  'Cpus_allowed_list:\t5-2\n', PermissionError('unreadable')])
def test_unavailable_or_malformed_thread_masks_refuse(monkeypatch, status):
    java = Mock(pid=20)
    java.name.return_value = 'java'
    java.children.return_value = []
    java.cpu_affinity.return_value = [2, 3]
    java.threads.return_value = [SimpleNamespace(id=21)]
    monkeypatch.setattr(sensitivity.psutil, 'Process', lambda _: java)
    def read_status(path):
        assert str(path) == '/proc/20/task/21/status'
        if isinstance(status, Exception):
            raise status
        return status
    monkeypatch.setattr(Path, 'read_text', read_status)
    with pytest.raises(sensitivity.SensitivityRefused, match='thread 21.*mask'):
        sensitivity.engine_cpus(Mock(), [2, 3])


@pytest.mark.parametrize('name', inputs.JVM_OPTION_VARIABLES)
@pytest.mark.parametrize('value', ['-Xmx3g', '-Djava.awt.headless=false', '-Xmx4g -Djava.awt.headless=true'])
def test_set_cannot_widen_core_jvm_allowed_values(monkeypatch, name, value):
    for variable in inputs.JVM_OPTION_VARIABLES:
        monkeypatch.delenv(variable, raising=False)
    assert value not in inputs.ALLOWED_JVM_OPTIONS[name]
    monkeypatch.setenv(name, value)
    with pytest.raises(sensitivity.SensitivityRefused, match=name):
        sensitivity.settings(['JUSTSEARCH_RERANK_ENABLED=false'])
    monkeypatch.delenv(name)
    with pytest.raises(sensitivity.SensitivityRefused, match=name):
        sensitivity.launch_environment({name: value}, {'JUSTSEARCH_RERANK_ENABLED': 'false'})
    with pytest.raises(sensitivity.SensitivityRefused, match=name):
        sensitivity.settings([name+'='+value])
