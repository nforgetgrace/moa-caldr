#!/usr/bin/env python3
"""Small adb UI helper. Always target an explicitly selected emulator/device."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
parser.add_argument('action', choices=['dump', 'tap', 'screenshot', 'text', 'back', 'swipe'])
parser.add_argument('value', nargs='?', default='')
args = parser.parse_args()
base = [args.adb, '-s', args.serial]

def adb(*command):
    return subprocess.check_output(base + list(command))

def dump():
    for attempt in range(3):
        adb('shell', 'rm', '-f', '/sdcard/moa-ui.xml')
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/moa-ui.xml')
        if b'UI hierchary dumped' in result or b'UI hierarchy dumped' in result:
            return ET.fromstring(adb('exec-out', 'cat', '/sdcard/moa-ui.xml'))
        time.sleep(0.5)
    raise SystemExit('No fresh accessibility hierarchy available.')

if args.action == 'dump':
    for node in dump().iter('node'):
        text = node.get('text') or node.get('content-desc')
        if text:
            print(text, node.get('bounds'), 'clickable=' + node.get('clickable', ''))
elif args.action == 'tap':
    root = dump()
    matches = [n for n in root.iter('node') if n.get('text') == args.value or n.get('content-desc') == args.value]
    if not matches:
        raise SystemExit('UI element not found: ' + args.value)
    node = matches[0]
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
elif args.action == 'screenshot':
    Path(args.value).write_bytes(adb('exec-out', 'screencap', '-p'))
elif args.action == 'text':
    adb('shell', 'input', 'text', args.value.replace(' ', '%s'))
elif args.action == 'back':
    adb('shell', 'input', 'keyevent', '4')
elif args.action == 'swipe':
    values = args.value.split(',') if args.value else ['540', '1750', '540', '650', '350']
    adb('shell', 'input', 'swipe', *values)
