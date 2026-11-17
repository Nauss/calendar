import argparse
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, Mock

SPEC = importlib.util.spec_from_file_location('emulator_ui', Path(__file__).parents[1] / 'emulator_ui.py')
ui = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui)


class EmulatorSafetyTest(unittest.TestCase):
    def test_capture_waits_for_hierarchy_then_settled_frame(self):
        emulator = object.__new__(ui.Emulator)
        emulator.adb = 'adb'
        emulator.serial = 'emulator-5582'
        order = []
        png = b'\x89PNG\r\n\x1a\nfixture'
        with patch.object(emulator, 'dump', side_effect=lambda: order.append('dump') or '<hierarchy/>'), \
             patch.object(ui, 'time', create=True) as clock, \
             patch.object(ui.subprocess, 'run', side_effect=lambda *a, **k: order.append('capture') or Mock(returncode=0, stdout=png)), \
             patch.object(ui, 'OUT') as out:
            clock.sleep.side_effect = lambda seconds: order.append('settle')
            clock.time.return_value = 1.0
            metadata = emulator.capture('ordered')
            self.assertEqual(['dump', 'settle', 'capture'], order)
            self.assertEqual([], metadata['warnings'])

    def test_hierarchy_failure_keeps_png_and_warning(self):
        emulator = object.__new__(ui.Emulator)
        emulator.adb = 'adb'
        emulator.serial = 'emulator-5582'
        png = b'\x89PNG\r\n\x1a\nfixture'
        with patch.object(emulator, 'dump', side_effect=RuntimeError('hierarchy timeout')), \
             patch.object(ui, 'time', create=True) as clock, \
             patch.object(ui.subprocess, 'run', return_value=Mock(returncode=0, stdout=png)), \
             patch.object(ui, 'OUT') as out:
            clock.time.return_value = 1.0
            metadata = emulator.capture('partial')
            (out / 'partial.png').write_bytes.assert_called_once_with(png)
            self.assertIsNone(metadata['hierarchy'])
            self.assertIn('hierarchy timeout', metadata['warnings'][0])

    def test_rejects_physical_serial(self):
        for value in ['DEVICE123', '', 'emulator-5580;bad', 'emulator-1']:
            with self.assertRaises(argparse.ArgumentTypeError):
                ui.emulator_serial(value)

    def test_accepts_owned_emulator_format(self):
        self.assertEqual(ui.emulator_serial('emulator-5580'), 'emulator-5580')

    def test_rejects_path_traversal(self):
        for value in ['../secret', 'C:/secret', '/secret', 'a/b', '']:
            with self.assertRaises(argparse.ArgumentTypeError):
                ui.safe_name(value)

    def test_exact_accessible_text(self):
        xml = '<hierarchy><node text="Save" enabled="true" bounds="[10,20][50,60]"/></hierarchy>'
        self.assertEqual(ui.matching_bounds(xml, 'Save'), (30, 40))
        with self.assertRaises(ValueError):
            ui.matching_bounds(xml, 'Sav')

    def test_ambiguous_or_disabled_targets_fail_closed(self):
        node = '<node text="Save" enabled="true" bounds="[10,20][50,60]"/>'
        with self.assertRaises(ValueError):
            ui.matching_bounds('<hierarchy>' + node + node + '</hierarchy>', 'Save')
        with self.assertRaises(ValueError):
            ui.matching_bounds('<hierarchy>' + node.replace('true', 'false') + '</hierarchy>', 'Save')

    def test_semantic_description_supported(self):
        xml = '<hierarchy><node content-desc="Today" enabled="true" bounds="[0,0][48,48]"/></hierarchy>'
        self.assertEqual(ui.matching_bounds(xml, 'Today'), (24, 24))


if __name__ == '__main__':
    unittest.main()
