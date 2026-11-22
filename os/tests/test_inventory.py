import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "tools" / "collect_device_inventory.py"
SPEC = importlib.util.spec_from_file_location("nexus_inventory", MODULE_PATH)
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)


class InventoryParserTests(unittest.TestCase):
    def test_adb_serial_is_never_persisted(self):
        devices = inventory.parse_devices(
            "List of devices attached\nABC123456 device product:BG7n model:TECNO_BG7n device:BG7n\n"
        )
        self.assertEqual(len(devices), 1)
        self.assertNotIn("ABC123456", str(devices))
        self.assertEqual(devices[0]["state"], "device")
        self.assertEqual(devices[0]["product"], "BG7n")

    def test_empty_device_list(self):
        self.assertEqual(inventory.parse_devices("List of devices attached\n"), [])

    def test_fastboot_queries_are_read_only_metadata(self):
        forbidden = {"flash", "erase", "format", "unlock", "lock", "reboot", "sideload"}
        self.assertTrue(
            all(item not in forbidden for item in inventory.FASTBOOT_VARIABLES)
        )


if __name__ == "__main__":
    unittest.main()
