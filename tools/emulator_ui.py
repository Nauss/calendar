"""Small emulator-only inspection helper. Never selects a physical device.

Examples:
  python tools/emulator_ui.py --serial emulator-5580 info
  python tools/emulator_ui.py --serial emulator-5580 capture agenda-light
  python tools/emulator_ui.py --serial emulator-5580 dump
  python tools/emulator_ui.py --serial emulator-5580 tap 'Settings'

Captures live under ignored artifacts/emulator/. It intentionally has no reset,
install, account, credential, audio or device-wipe operation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts' / 'emulator'


def emulator_serial(value: str) -> str:
    if not re.fullmatch(r'emulator-\d{4,5}', value):
        raise argparse.ArgumentTypeError('An explicit emulator-NNNN serial is required; physical devices are forbidden.')
    return value


def safe_name(value: str) -> str:
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', value):
        raise argparse.ArgumentTypeError('Use a simple alphanumeric capture name.')
    return value


def nodes(xml: str) -> list[dict]:
    return [dict(node.attrib) for node in ET.fromstring(xml).iter('node')]


def matching_bounds(xml: str, text: str) -> tuple[int, int]:
    matches = []
    for node in nodes(xml):
        if text not in (node.get('text'), node.get('content-desc'), node.get('resource-id')):
            continue
        if node.get('enabled') == 'false':
            continue
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            if x2 > x1 and y2 > y1:
                matches.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if len(matches) != 1:
        raise ValueError(f'Expected one enabled match for {text!r}, found {len(matches)}. Inspect the dump instead of guessing.')
    return matches[0]


class Emulator:
    def __init__(self, serial: str):
        self.serial = emulator_serial(serial)
        self.adb = shutil.which('adb')
        if not self.adb:
            raise RuntimeError('adb is not on PATH')
        if self.run('get-state').strip() != 'device':
            raise RuntimeError('Requested emulator is not ready')
        if self.run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
            raise RuntimeError('Refusing a non-emulator target')

    def run(self, *args: str) -> str:
        result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True, timeout=40)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
        return result.stdout.decode('utf-8', errors='replace')

    def dump(self) -> str:
        path = '/sdcard/agenda-qa-window.xml'
        self.run('shell', 'uiautomator', 'dump', path)
        return self.run('shell', 'cat', path)

    def capture(self, name: str) -> dict:
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / (safe_name(name) + '.png')
        warnings = []
        xml_path = path.with_suffix('.xml')
        hierarchy = None
        try:
            xml = self.dump()
            nodes(xml)  # Do not label a stale/invalid dump as evidence.
            xml_path.write_text(xml, encoding='utf-8')
            hierarchy = str(xml_path)
        except Exception as error:
            warnings.append('Hierarchy unavailable: ' + str(error))
        # uiautomator waits for idle; give the composed frame time to present.
        time.sleep(0.4)
        result = subprocess.run([self.adb, '-s', self.serial, 'exec-out', 'screencap', '-p'], capture_output=True, timeout=40)
        if result.returncode or not result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
            raise RuntimeError('Native screenshot capture failed')
        path.write_bytes(result.stdout)
        metadata = {'image': str(path), 'hierarchy': hierarchy, 'source': 'adb exec-out screencap -p', 'serial': self.serial,
                    'warnings': warnings, 'settle_seconds': 0.4, 'captured_at_unix': time.time()}
        path.with_suffix('.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, type=emulator_serial)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('info')
    commands.add_parser('dump')
    capture = commands.add_parser('capture')
    capture.add_argument('name', type=safe_name)
    tap = commands.add_parser('tap')
    tap.add_argument('text')
    args = parser.parse_args()
    emulator = Emulator(args.serial)
    if args.command == 'capture':
        print(json.dumps(emulator.capture(args.name), indent=2))
    elif args.command == 'dump':
        print(json.dumps(nodes(emulator.dump()), ensure_ascii=False, indent=2))
    elif args.command == 'tap':
        x, y = matching_bounds(emulator.dump(), args.text)
        emulator.run('shell', 'input', 'tap', str(x), str(y))
        print(json.dumps({'serial': args.serial, 'tapped': args.text, 'at': [x, y]}))
    else:
        print(json.dumps({
            'serial': args.serial,
            'api': emulator.run('shell', 'getprop', 'ro.build.version.sdk').strip(),
            'display': emulator.run('shell', 'wm', 'size').strip(),
            'font_scale': emulator.run('shell', 'settings', 'get', 'system', 'font_scale').strip(),
            'night_mode': emulator.run('shell', 'cmd', 'uimode', 'night').strip(),
            'locale': emulator.run('shell', 'settings', 'get', 'system', 'system_locales').strip(),
        }, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
