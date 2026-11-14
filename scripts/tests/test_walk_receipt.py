import shutil
import subprocess
import unittest
from pathlib import Path


@unittest.skipUnless(shutil.which("pwsh"), "The walk receipt contracts require PowerShell 7")
class WalkReceipt(unittest.TestCase):
    def test_publish_takes_only_a_full_passing_walk_of_its_bundle(self):
        result = subprocess.run(
            [
                "pwsh",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                str(Path(__file__).resolve().parents[1] / "test-walk-receipt.ps1"),
            ],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("walk receipt contracts passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
