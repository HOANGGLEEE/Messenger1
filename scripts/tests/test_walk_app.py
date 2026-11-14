import shutil
import subprocess
import unittest
from pathlib import Path


@unittest.skipUnless(shutil.which("pwsh"), "The walk-app contracts require PowerShell 7")
class WalkApp(unittest.TestCase):
    def test_a_crash_fails_and_a_skipped_screen_is_partial(self):
        result = subprocess.run(
            [
                "pwsh",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                str(Path(__file__).resolve().parents[1] / "test-walk-app.ps1"),
            ],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("walk-app contracts passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
