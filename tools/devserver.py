"""Disposable loopback-only CalDAV fixture server; never targets a personal NAS.

Install tools/requirements-test.txt in a virtualenv, then run:
  python tools/devserver.py prepare
  python tools/devserver.py serve
  python tools/devserver.py seed          # in another terminal
  python tools/devserver.py check

For an owned Android emulator: adb -s SERIAL reverse tcp:15232 tcp:15232.
Use http://127.0.0.1:15232/tester/ with the deliberately public fixture login below.
No real credentials or personal events belong here. Files live in ignored .ops/.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

BASE_URL = 'http://127.0.0.1:15232'
TEST_USER = 'tester'
TEST_PASSWORD = 'synthetic-only'  # Public, local test fixture; never a real account.
CALENDAR_PATH = '/tester/agenda-qa/'
ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.ops' / 'radicale'


def fixture_events(day: dt.date) -> dict[str, str]:
    def event(uid: str, title: str, start: str, end: str, extra: tuple[str, ...] = ()) -> str:
        return '\r\n'.join([
            'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Agenda//Synthetic QA//EN',
            'BEGIN:VEVENT', 'UID:' + uid, 'DTSTAMP:20260101T000000Z',
            'SUMMARY:' + title, start, end,
            'DESCRIPTION:Synthetic test fixture. No personal data.',
            *extra, 'END:VEVENT', 'END:VCALENDAR', ''
        ])
    stamp = day.strftime('%Y%m%d')
    tomorrow = (day + dt.timedelta(days=1)).strftime('%Y%m%d')
    later = (day + dt.timedelta(days=3)).strftime('%Y%m%d')
    trip_end = (day + dt.timedelta(days=5)).strftime('%Y%m%d')
    specs = [
        ('qa-all-day', 'Une journée pour soi', 'DTSTART;VALUE=DATE:' + stamp, 'DTEND;VALUE=DATE:' + tomorrow, ()),
        ('qa-focus', 'Projet maison', 'DTSTART:' + stamp + 'T080000Z', 'DTEND:' + stamp + 'T093000Z', ('LOCATION:À la maison',)),
        ('qa-overlap', 'Appel de préparation', 'DTSTART:' + stamp + 'T083000Z', 'DTEND:' + stamp + 'T090000Z', ()),
        ('qa-lunch', 'Déjeuner en ville', 'DTSTART:' + stamp + 'T113000Z', 'DTEND:' + stamp + 'T123000Z', ('LOCATION:Centre-ville',)),
        ('qa-exercise', 'Une heure au grand air', 'DTSTART:' + stamp + 'T160000Z', 'DTEND:' + stamp + 'T170000Z', ()),
        ('qa-tomorrow', 'Préparer le week-end', 'DTSTART:' + tomorrow + 'T093000Z', 'DTEND:' + tomorrow + 'T103000Z', ()),
        ('qa-recurring', 'Le rendez-vous du mardi', 'DTSTART:' + stamp + 'T180000Z', 'DTEND:' + stamp + 'T190000Z', ('RRULE:FREQ=WEEKLY;COUNT=6',)),
        ('qa-trip', 'Escapade', 'DTSTART;VALUE=DATE:' + later, 'DTEND;VALUE=DATE:' + trip_end, ()),
    ]
    return {uid: event(uid, title, start, end, extra) for uid, title, start, end, extra in specs}


def config_text(state: Path) -> str:
    return f'''[server]
hosts = 127.0.0.1:15232
[auth]
type = htpasswd
htpasswd_filename = {(state / 'users').resolve().as_posix()}
htpasswd_encryption = plain
delay = 0
[rights]
type = owner_only
[storage]
filesystem_folder = {(state / 'collections').resolve().as_posix()}
[logging]
level = warning
'''


def prepare() -> Path:
    STATE.mkdir(parents=True, exist_ok=True)
    config = STATE / 'config'
    config.write_text(config_text(STATE), encoding='utf-8')
    (STATE / 'users').write_text(TEST_USER + ':' + TEST_PASSWORD + '\n', encoding='utf-8')
    return config


def request(method: str, path: str, body: str | None = None, **headers):
    import requests
    if not path.startswith('/') or path.startswith('//') or '..' in path:
        raise ValueError('Only fixed local paths are allowed.')
    return requests.request(method, BASE_URL + path, data=body.encode('utf-8') if body else None,
                            auth=(TEST_USER, TEST_PASSWORD), timeout=15, headers=headers,
                            allow_redirects=False)


def seed(day: dt.date) -> dict:
    collection = '''<?xml version="1.0"?><C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/"><D:set><D:prop><D:displayname>Personnel · Démonstration</D:displayname><A:calendar-color>#3F6CB5FF</A:calendar-color><C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>'''
    result = request('MKCALENDAR', CALENDAR_PATH, collection, **{'Content-Type': 'application/xml; charset=utf-8'})
    if result.status_code not in (201, 405):
        raise RuntimeError(f'MKCALENDAR failed: {result.status_code}')
    statuses = {}
    for uid, ics in fixture_events(day).items():
        response = request('PUT', CALENDAR_PATH + uid + '.ics', ics,
                           **{'Content-Type': 'text/calendar; charset=utf-8', 'If-None-Match': '*'})
        if response.status_code not in (201, 204, 412):
            raise RuntimeError(f'Fixture PUT failed: {response.status_code}')
        statuses[uid] = response.status_code
    return {'server': BASE_URL, 'calendar': CALENDAR_PATH, 'date': day.isoformat(), 'fixtures': statuses}


def check() -> dict:
    body = '''<?xml version="1.0"?><C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><D:getetag/><C:calendar-data/></D:prop><C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT"/></C:comp-filter></C:filter></C:calendar-query>'''
    result = request('REPORT', CALENDAR_PATH, body, **{'Depth': '1', 'Content-Type': 'application/xml'})
    if result.status_code != 207:
        raise RuntimeError(f'REPORT failed: {result.status_code}')
    root = ET.fromstring(result.content)
    hrefs = sorted(node.text for node in root.findall('{DAV:}response/{DAV:}href'))
    if len(hrefs) != len(set(hrefs)):
        raise RuntimeError('Duplicate hrefs from the test server')
    return {'server': BASE_URL, 'calendar': CALENDAR_PATH, 'status': 207, 'count': len(hrefs), 'hrefs': hrefs}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'serve', 'seed', 'check'))
    parser.add_argument('--date', type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()
    if args.action == 'prepare':
        print(prepare())
    elif args.action == 'serve':
        return subprocess.call([sys.executable, '-m', 'radicale', '--config', str(prepare())])
    elif args.action == 'seed':
        print(json.dumps(seed(args.date), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(check(), ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
