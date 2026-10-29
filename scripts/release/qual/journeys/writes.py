"""Whole-device guest counters, excluding partitions and memory-backed devices."""

# Executed in the tested guest, also when the runner itself is on the host.
COUNTERS = """
import json,os,subprocess
from pathlib import Path
subprocess.run(['sync'],check=True)
devices={}
for p in Path('/sys/block').iterdir():
 if p.name.startswith(('loop','ram','zram','sr')):continue
 fields=(p/'stat').read_text().split()
 devices[p.name]=int(fields[6])*512
stats=dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
print(json.dumps({'devices':devices,'writtenBytes':sum(devices.values()),
 'swapWrittenBytes':int(stats['pswpout'])*os.sysconf('SC_PAGE_SIZE')}))
"""


def difference(before, after):
    if set(before['devices']) != set(after['devices']):
        raise RuntimeError('Guest block devices changed during measurement')
    result = {key: after[key] - before[key] for key in ('writtenBytes', 'swapWrittenBytes')}
    if any(value < 0 for value in result.values()):
        raise RuntimeError('Guest write counters reset during measurement')
    return dict(result, source='guest whole-device disk counters; sync at boundaries')
