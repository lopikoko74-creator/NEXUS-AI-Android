import os, tempfile, unittest
from pathlib import Path

TMP = tempfile.TemporaryDirectory()
os.environ["NEXUS_TOKEN_FILE"] = str(Path(TMP.name) / "token")
os.environ["NEXUS_TOKEN"] = "test-token"
import nexus_agent

class AgentTests(unittest.TestCase):
    def test_status_shape(self):
        data = nexus_agent.status()
        for key in ("hostname", "os", "system", "architecture", "disks", "agent"):
            self.assertIn(key, data)

    def test_unknown_action_rejected(self):
        with self.assertRaises(ValueError):
            nexus_agent.action("run_arbitrary_shell")

    def test_volume_range_checked(self):
        with self.assertRaises(ValueError):
            nexus_agent.action("set_volume", 101)

if __name__ == "__main__":
    unittest.main()
