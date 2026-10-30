#!/usr/bin/env python3
"""Host screen and input through host-only Unix-socket QMP; guests use a US keyboard layout."""
import argparse
import binascii
import json
from pathlib import Path
import re
import struct
import sys
import tempfile
import time
import zlib

import vm
from vm import OS, ROOT, QMP, require_owner


def ppm(data):
    # Consume exactly the P6 header delimiter; pixel 0 may itself be whitespace.
    match = re.match(rb'P6\s+(\d+)\s+(\d+)\s+255\r?\n', data)
    if not match:
        raise ValueError('Expected an 8-bit RGB QEMU PPM image')
    width, height = map(int, match.groups())
    pixels = data[match.end():]
    if len(pixels) != width * height * 3:
        raise ValueError('Truncated or oversized QMP screendump')
    return width, height, pixels


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data))


def shot(qmp, out, exclusive=False):
    with tempfile.TemporaryDirectory(prefix='screen-', dir=ROOT) as tmp:
        src = Path(tmp) / 'screen.ppm'
        qmp.command('screendump', filename=str(src))
        width, height, pixels = ppm(src.read_bytes())
    rows = b''.join(b'\0' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
    with Path(out).open('xb' if exclusive else 'wb') as target:
        target.write(png)
    return width, height


def key_event(code, down):
    return {'type': 'key', 'data': {'down': down, 'key': {'type': 'qcode', 'data': code}}}


def key(qmp, combo):
    aliases = {'enter': 'ret', 'return': 'ret', 'escape': 'esc', 'super': 'meta_l',
               'win': 'meta_l', 'alt': 'alt', 'ctrl': 'ctrl', 'control': 'ctrl',
               'shift': 'shift', 'space': 'spc', 'backspace': 'backspace',
               'delete': 'delete', 'pageup': 'pgup', 'pagedown': 'pgdn'}
    codes = [aliases.get(c.lower(), c.lower()) for c in combo.split('+')]
    qmp.command('input-send-event', events=[key_event(c, True) for c in codes])
    time.sleep(.04)
    qmp.command('input-send-event', events=[key_event(c, False) for c in reversed(codes)])


UNSHIFTED = dict(zip('`1234567890-=[]\\;\',./',
                     ['grave_accent', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0',
                      'minus', 'equal', 'bracket_left', 'bracket_right', 'backslash',
                      'semicolon', 'apostrophe', 'comma', 'dot', 'slash']))
SHIFTED = dict(zip('~!@#$%^&*()_+{}|:"<>?', UNSHIFTED.values()))


def type_text(qmp, text):
    # Validate the complete text before emitting any events.
    keys = []
    for char in text:
        if char in 'abcdefghijklmnopqrstuvwxyz':
            keys.append(char)
        elif char in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ':
            keys.append('shift+' + char.lower())
        elif char in UNSHIFTED:
            keys.append(UNSHIFTED[char])
        elif char in SHIFTED:
            keys.append('shift+' + SHIFTED[char])
        elif char in {' ': 'spc', '\n': 'ret', '\t': 'tab'}:
            keys.append({' ': 'spc', '\n': 'ret', '\t': 'tab'}[char])
        else:
            raise ValueError(f'Cannot type {char!r} on the configured US keyboard')
    for combo in keys:
        key(qmp, combo)
        time.sleep(.025)


def move(qmp, x, y):
    with tempfile.TemporaryDirectory(prefix='size-', dir=ROOT) as tmp:
        src = Path(tmp) / 'screen.ppm'
        qmp.command('screendump', filename=str(src))
        width, height, _ = ppm(src.read_bytes())
    if not (0 <= x < width and 0 <= y < height):
        raise ValueError(f'Coordinates outside {width}x{height} screen')
    qmp.command('input-send-event', events=[
        {'type': 'abs', 'data': {'axis': 'x', 'value': round(x * 32767 / max(1, width - 1))}},
        {'type': 'abs', 'data': {'axis': 'y', 'value': round(y * 32767 / max(1, height - 1))}}])


def perform(qmp, action, values, out=None, exclusive=False):
    expected = {'shot': set(), 'click': {'x', 'y', 'button'}, 'move': {'x', 'y'},
                'key': {'combo'}, 'type': {'text'}}
    if action not in expected or set(values) != expected[action]:
        raise ValueError('Only shot, click, move, key, type and their exact arguments are accepted')
    if action == 'shot':
        return '%dx%d' % shot(qmp, out, exclusive=exclusive)
    if action in ('move', 'click'):
        if type(values['x']) is not int or type(values['y']) is not int:
            raise ValueError('Coordinates must be integers')
        if action == 'click' and values['button'] not in ('left', 'middle', 'right'):
            raise ValueError('Invalid mouse button')
        move(qmp, values['x'], values['y'])
        if action == 'click':
            for down in (True, False):
                qmp.command('input-send-event', events=[{'type': 'btn', 'data': {'down': down, 'button': values['button']}}])
                time.sleep(.05)
    elif action == 'key':
        if not isinstance(values['combo'], str) or len(values['combo']) > 128:
            raise ValueError('Key combination must be a string of at most 128 characters')
        key(qmp, values['combo'])
    else:
        if not isinstance(values['text'], str) or len(values['text']) > 4096:
            raise ValueError('Text must be a string of at most 4096 characters')
        type_text(qmp, values['text'])
    return 'ok'


def queue_path(data):
    return ROOT / 'screen-queue' / f"{data['pid']}-{data['start_ticks']}"


def broker(os_name):
    data = require_owner(os_name)
    folder = queue_path(data)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    if folder.is_symlink() or folder.parent.is_symlink():
        raise RuntimeError('Screen queue directories must not be symlinks')
    # One broker per run; crash releases the lock. Readiness is never inferred from PID visibility.
    with vm.locked('screen-broker.lock'):
        ready = folder / 'ready'
        vm.save(ready, {'ready': True})
        print(f'Screen broker ready: {folder}', flush=True)
        try:
            next_probe = 0
            while vm.current() == data:
                if time.monotonic() >= next_probe:
                    if not vm.live_reasons():
                        break
                    next_probe = time.monotonic() + 5
                for request in sorted(folder.glob('*.request')):
                    name = request.stem
                    if not re.fullmatch(r'[0-9a-f]{32}', name):
                        continue
                    reply = folder / (name + '.reply')
                    try:
                        if request.is_symlink() or request.stat().st_size > 32768:
                            raise ValueError('Request too large')
                        job = json.loads(request.read_text())
                        if set(job) != {'owner', 'action', 'values'} or job['owner'] != data['owner']:
                            raise ValueError(f"VM belongs to owner {data['owner']}")
                        if require_owner(os_name) != data:
                            raise RuntimeError('VM run changed')
                        with QMP(os_name) as qmp:
                            result = perform(qmp, job['action'], job['values'], folder / (name + '.png'), exclusive=True)
                        response = {'result': result}
                    except (OSError, RuntimeError, ValueError, TypeError, KeyError) as exc:
                        response = {'error': str(exc)}
                    vm.save(reply, response)
                    request.unlink(missing_ok=True)
                time.sleep(.1)
        finally:
            ready.unlink(missing_ok=True)


def via_broker(os_name, action, values, out=None):
    import uuid
    data = require_owner(os_name)
    folder = queue_path(data)
    if not (folder / 'ready').exists():
        raise RuntimeError('Start screen.py broker on the host first')
    name = uuid.uuid4().hex
    request = folder / (name + '.request')
    reply = folder / (name + '.reply')
    png = folder / (name + '.png')
    vm.save(request, {'owner': vm.owner(), 'action': action, 'values': values})
    deadline = time.monotonic() + 360
    try:
        while time.monotonic() < deadline:
            if reply.exists():
                response = json.loads(reply.read_text())
                if 'error' in response:
                    raise RuntimeError(response['error'])
                if action == 'shot':
                    Path(out).write_bytes(png.read_bytes())
                return response['result']
            if require_owner(os_name) != data:
                raise RuntimeError('VM run changed while waiting for screen broker')
            time.sleep(.1)
        raise RuntimeError('Screen broker timed out; check its host process')
    finally:
        request.unlink(missing_ok=True)
        reply.unlink(missing_ok=True)
        png.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--broker', action='store_true', help='Use the host file-queue broker (no sockets needed)')
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('shot', 'click', 'move', 'type', 'key'):
        p = sub.add_parser(action)
        p.add_argument('os', choices=OS)
        if action == 'shot':
            p.add_argument('out')
        elif action in ('click', 'move'):
            p.add_argument('x', type=int)
            p.add_argument('y', type=int)
            if action == 'click':
                p.add_argument('button', nargs='?', default='left', choices=('left', 'middle', 'right'))
        elif action == 'type':
            p.add_argument('text')
        else:
            p.add_argument('combo')
    p = sub.add_parser('broker', help='Run the screen-only file-queue broker on the host')
    p.add_argument('os', choices=OS)
    args = parser.parse_args()
    require_owner(args.os)
    if args.action == 'broker':
        broker(args.os)
        return
    values = {k: v for k, v in vars(args).items() if k not in ('action', 'os', 'broker', 'out')}
    if args.broker:
        print(via_broker(args.os, args.action, values, getattr(args, 'out', None)))
    else:
        with QMP(args.os) as qmp:
            print(perform(qmp, args.action, values, getattr(args, 'out', None)))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError) as exc:
        sys.exit(str(exc))
