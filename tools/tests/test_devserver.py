import datetime as dt
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('devserver', Path(__file__).parents[1] / 'devserver.py')


class FixturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(cls.server)

    def test_server_is_loopback_only(self):
        self.assertEqual(self.server.BASE_URL, 'http://127.0.0.1:15232')

    def test_fixture_identifiers_are_stable_and_unique(self):
        day = dt.date(2026, 11, 17)
        first = self.server.fixture_events(day)
        self.assertEqual(first, self.server.fixture_events(day))
        self.assertEqual(len(first), 8)
        self.assertEqual(len(set(first)), 8)
        for uid, ics in first.items():
            self.assertIn('UID:' + uid + '\r\n', ics)
            self.assertTrue(ics.startswith('BEGIN:VCALENDAR\r\n'))
            self.assertTrue(ics.endswith('END:VCALENDAR\r\n'))
            self.assertNotIn('\n', ics.replace('\r\n', ''))

    def test_all_day_end_is_exclusive(self):
        ics = self.server.fixture_events(dt.date(2026, 11, 17))['qa-all-day']
        self.assertIn('DTSTART;VALUE=DATE:20261117\r\n', ics)
        self.assertIn('DTEND;VALUE=DATE:20261118\r\n', ics)

    def test_relative_dates_cross_month_boundary(self):
        ics = self.server.fixture_events(dt.date(2026, 12, 31))['qa-tomorrow']
        self.assertIn('DTSTART:20270101T093000Z', ics)

    def test_server_configuration_has_no_network_exposure(self):
        config = self.server.config_text(Path('qa-scratch'))
        self.assertIn('hosts = 127.0.0.1:15232', config)
        self.assertIn('type = htpasswd', config)
        self.assertNotIn('0.0.0.0', config)
        self.assertNotIn('verify = false', config.lower())


if __name__ == '__main__':
    unittest.main()
