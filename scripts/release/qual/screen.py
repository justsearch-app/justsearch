#!/usr/bin/env python3
"""Host screen and input through QMP only; guests use a US keyboard layout."""
import argparse
import binascii
from pathlib import Path
import re
import struct
import sys
import tempfile
import time
import zlib

from vm import OS, ROOT, QMP


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


def shot(qmp, out):
    with tempfile.TemporaryDirectory(prefix='screen-', dir=ROOT) as tmp:
        src = Path(tmp) / 'screen.ppm'
        qmp.command('screendump', filename=str(src))
        width, height, pixels = ppm(src.read_bytes())
    rows = b''.join(b'\0' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
    Path(out).write_bytes(png)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
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
    args = parser.parse_args()
    with QMP(args.os) as qmp:
        if args.action == 'shot':
            print('%dx%d' % shot(qmp, args.out))
        elif args.action == 'type':
            type_text(qmp, args.text)
        elif args.action == 'key':
            key(qmp, args.combo)
        else:
            move(qmp, args.x, args.y)
            if args.action == 'click':
                qmp.command('input-send-event', events=[{'type': 'btn', 'data': {'down': True, 'button': args.button}}])
                time.sleep(.05)
                qmp.command('input-send-event', events=[{'type': 'btn', 'data': {'down': False, 'button': args.button}}])


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError) as exc:
        sys.exit(str(exc))
