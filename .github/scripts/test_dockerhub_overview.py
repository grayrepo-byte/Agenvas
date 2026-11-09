"""Run the Overview credential preflight without contacting Docker Hub."""

from pathlib import Path
import subprocess
import unittest

from test_ci import job_run_commands, workflow_jobs


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/dockerhub-overview.yml"


class OverviewCredentialsTest(unittest.TestCase):
    def run_preflight(self, username="", token=""):
        self.assertTrue(WORKFLOW.is_file(), "The Overview workflow must exist")
        commands = list(job_run_commands(workflow_jobs(WORKFLOW.read_text())["sync"]))
        self.assertTrue(commands, "A missing credential must fail before contacting Docker Hub")
        return subprocess.run(
            ["bash", "-e", "-o", "pipefail", "-c", "\n".join(commands)],
            env={"PATH": "/usr/bin:/bin", "DOCKERHUB_USERNAME": username, "DOCKERHUB_TOKEN": token},
            capture_output=True, text=True, timeout=10,
        )

    def test_missing_credentials_fail_with_actionable_names(self):
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("DOCKERHUB_USERNAME", result.stderr)
        self.assertIn("DOCKERHUB_TOKEN", result.stderr)

    def test_missing_username_fails_without_printing_token(self):
        result = self.run_preflight(token="synthetic-token-do-not-log")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("synthetic-token-do-not-log", result.stdout + result.stderr)

    def test_missing_token_fails_without_printing_username(self):
        result = self.run_preflight(username="synthetic-user-do-not-log")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("synthetic-user-do-not-log", result.stdout + result.stderr)

    def test_present_credentials_pass_without_echoing_values(self):
        result = self.run_preflight("synthetic-user-do-not-log", "synthetic-token-do-not-log")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout + result.stderr, "")


if __name__ == "__main__":
    unittest.main()
