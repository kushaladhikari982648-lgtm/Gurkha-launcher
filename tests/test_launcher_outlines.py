"""Resource-only checks for the bold-outline refresh (no Android SDK needed).

Run: python3 -m unittest discover -s tests -v
Pixel appearance still needs an Android device/emulator check.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / 'ZalithLauncher/src/main/res'
A = '{http://schemas.android.com/apk/res/android}'
FAMILIES = ['background_item', 'background_item_2', 'button_background', 'background_edit_box']


def drawable(name):
    return ET.parse(RES / 'drawable' / (name + '.xml')).getroot()


def resolve(selector, **state):
    for item in selector.findall('item'):
        conditions = {key[len(A) + 6:]: value == 'true'
                      for key, value in item.attrib.items() if key.startswith(A + 'state_')}
        if all(state.get(key, False) == value for key, value in conditions.items()):
            return item.get(A + 'drawable')
    raise AssertionError('No selector fallback')


class OutlineTests(unittest.TestCase):
    def test_disabled_takes_priority_over_selected_checked_and_focus(self):
        for family in FAMILIES:
            selector = drawable(family)
            self.assertEqual(resolve(selector, enabled=False, selected=True, checked=True,
                                     focused=True, pressed=True), '@drawable/' + family + '_disabled')
            self.assertEqual(resolve(selector, enabled=True), '@drawable/' + family + '_unselected')
            for active in ['selected', 'checked', 'focused', 'pressed']:
                self.assertEqual(resolve(selector, enabled=True, **{active: True}),
                                 '@drawable/' + family + '_selected')

    def test_each_control_state_has_exactly_one_matching_border(self):
        for family in FAMILIES:
            for state in ['unselected', 'selected', 'disabled']:
                strokes = list(drawable(family + '_' + state).iter('stroke'))
                self.assertEqual(len(strokes), 1)
                color = 'launcher_outline' + {'unselected': '', 'selected': '_active', 'disabled': '_disabled'}[state]
                width = 'launcher_outline_active_width' if state == 'selected' else 'launcher_outline_width'
                self.assertEqual(strokes[0].get(A + 'color'), '@color/' + color)
                self.assertEqual(strokes[0].get(A + 'width'), '@dimen/' + width)

    def test_cards_and_dialogs_use_normal_outline(self):
        for name in ['background_card', 'dialog_background']:
            stroke = next(drawable(name).iter('stroke'))
            self.assertEqual(stroke.get(A + 'color'), '@color/launcher_outline')
            self.assertEqual(stroke.get(A + 'width'), '@dimen/launcher_outline_width')

    def test_light_and_dark_palettes_and_widths_are_defined(self):
        for directory in ['values', 'values-night']:
            values = ET.parse(RES / directory / 'launcher_outlines.xml').getroot()
            colors = {c.get('name'): c.text for c in values.findall('color')}
            self.assertEqual(set(colors), {'launcher_outline', 'launcher_outline_active', 'launcher_outline_disabled'})
            self.assertEqual(len(set(colors.values())), 3)
        values = ET.parse(RES / 'values/launcher_outlines.xml').getroot()
        widths = {d.get('name'): d.text for d in values.findall('dimen')}
        self.assertEqual(widths, {'launcher_outline_width': '2dp', 'launcher_outline_active_width': '3dp'})

    def test_focusing_an_input_does_not_change_its_padding(self):
        paddings = [next(drawable('background_edit_box_' + state).iter('padding')).attrib
                    for state in ['unselected', 'selected', 'disabled']]
        self.assertEqual(paddings[0], paddings[1])
        self.assertEqual(paddings[0], paddings[2])

    def test_button_insets_padding_and_fill_are_preserved_between_states(self):
        states = [drawable('button_background_' + state) for state in ['unselected', 'selected', 'disabled']]
        for state in states[1:]:
            self.assertEqual(states[0].attrib, state.attrib)
            self.assertEqual(next(states[0].iter('padding')).attrib, next(state.iter('padding')).attrib)
            self.assertEqual(next(states[0].iter('solid')).attrib, next(state.iter('solid')).attrib)

    def test_all_changed_drawable_references_resolve(self):
        names = FAMILIES + [family + '_' + state for family in FAMILIES
                            for state in ['unselected', 'selected', 'disabled']]
        for name in names:
            for element in drawable(name).iter():
                for value in element.attrib.values():
                    if value.startswith('@drawable/'):
                        self.assertTrue((RES / 'drawable' / (value.split('/')[1] + '.xml')).is_file(), value)


if __name__ == '__main__':
    unittest.main()
