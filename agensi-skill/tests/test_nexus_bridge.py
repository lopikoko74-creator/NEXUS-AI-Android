import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).parents[1] / "scripts" / "nexus_bridge.py"
SPEC = importlib.util.spec_from_file_location("nexus_bridge", SCRIPT)
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)

class AdbSelectionTests(unittest.TestCase):
    @patch.object(bridge, "adb_devices", return_value=[])
    def test_rejects_no_authorized_device(self, _):
        with self.assertRaisesRegex(bridge.BridgeError, "No authorized"):
            bridge.ensure_forward("adb")

    @patch.object(bridge, "adb_devices", return_value=["phone-a", "phone-b"])
    def test_rejects_multiple_devices(self, _):
        with self.assertRaisesRegex(bridge.BridgeError, "exactly one"):
            bridge.ensure_forward("adb")

    @patch.object(bridge.subprocess, "run")
    @patch.object(bridge, "adb_devices", return_value=["phone-a"])
    def test_forwards_only_selected_device(self, _, run):
        self.assertEqual(bridge.ensure_forward("adb"), "phone-a")
        self.assertEqual(run.call_args.args[0],
                         ["adb", "-s", "phone-a", "forward", "--no-rebind",
                          "tcp:8766", "tcp:8766"])

    @patch.dict("os.environ", {}, clear=True)
    def test_requires_token_without_echoing_any_secret(self):
        with self.assertRaisesRegex(bridge.BridgeError, "NEXUS_BRIDGE_TOKEN"):
            bridge.request("/v1/status")

if __name__ == "__main__":
    unittest.main()
