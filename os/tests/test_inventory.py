import unittest

from os.tools.collect_device_inventory import FASTBOOT_VARIABLES, parse_devices


class InventoryParserTests(unittest.TestCase):
    def test_adb_serial_is_never_persisted(self):
        devices = parse_devices(
            "List of devices attached\nABC123456 device product:BG7n model:TECNO_BG7n device:BG7n\n"
        )
        self.assertEqual(len(devices), 1)
        self.assertNotIn("ABC123456", str(devices))
        self.assertEqual(devices[0]["state"], "device")
        self.assertEqual(devices[0]["product"], "BG7n")

    def test_empty_device_list(self):
        self.assertEqual(parse_devices("List of devices attached\n"), [])

    def test_fastboot_queries_are_read_only_metadata(self):
        forbidden = {"flash", "erase", "format", "unlock", "lock", "reboot", "sideload"}
        self.assertTrue(all(not any(word in item for word in forbidden) for item in FASTBOOT_VARIABLES))


if __name__ == "__main__":
    unittest.main()
