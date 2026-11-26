import unittest
from server_craftengine_item_smoke import diagnostics


class ProviderDiagnosticsTest(unittest.TestCase):
    def test_only_complete_warning_inside_deliberate_disable_is_expected(self):
        blank = '[12:34:56 ERROR]: [CraftEngine]  \n'
        warning = '[12:34:56 ERROR]: [CraftEngine] Please do not disable plugins at runtime.\n'
        block = [blank] * 3 + [warning] + [blank] * 3
        lines = ['CRAFTENGINE_INTENTIONAL_DISABLE_BEGIN', *block, 'CRAFTENGINE_INTENTIONAL_DISABLE_END']
        errors, expected = diagnostics(lines)
        self.assertEqual([], errors)
        self.assertEqual(7, len(expected))
        self.assertEqual(7, len(diagnostics(block)[0]))
        self.assertTrue(diagnostics(lines[:-2] + lines[-1:])[0])

    def test_unexpected_failure_inside_or_after_disable_still_fails(self):
        for message in ('[12:34:56 ERROR]: [CraftEngine] Failed to save data',
                        '[12:34:56 ERROR]: Error occurred while disabling StarGenesisCraftEngine',
                        '[12:34:56 ERROR]: [AnotherPlugin] Broken'):
            self.assertTrue(diagnostics(['CRAFTENGINE_INTENTIONAL_DISABLE_BEGIN', message])[0])
            self.assertTrue(diagnostics(['CRAFTENGINE_INTENTIONAL_DISABLE_END', message])[0])


if __name__ == '__main__': unittest.main()
