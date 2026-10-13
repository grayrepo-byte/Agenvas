#!/usr/bin/env python3
"""Exercise update orchestration with a fake Docker CLI; never modify running services."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

REPO = Path(__file__).resolve().parent.parent


class UpdateLocalTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="agenvas-update-test-")
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.log = root / "docker.jsonl"
        docker = root / "docker"
        docker.write_text(f"#!{sys.executable}\n" + textwrap.dedent("""
            import json, os, sys
            args = sys.argv[1:]
            with open(os.environ['UPDATE_TEST_LOG'], 'a') as output:
                output.write(json.dumps(args) + '\\n')
            action = next((value for value in args if value in
                ['version', 'info', 'config', 'build', 'up', 'ps']), '')
            if action == os.environ.get('UPDATE_TEST_FAIL'):
                sys.exit(19)
        """))
        docker.chmod(0o700)
        self.env = dict(os.environ, PATH=f"{root}:/usr/bin:/bin", UPDATE_TEST_LOG=str(self.log))
        self.env.pop("UPDATE_TEST_FAIL", None)

    def run_update(self, *args, failure=None):
        if failure:
            self.env["UPDATE_TEST_FAIL"] = failure
        result = subprocess.run(["bash", str(REPO / "deploy/update-local.sh"), *args],
                                env=self.env, capture_output=True, text=True, timeout=10)
        commands = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, commands

    def command(self, commands, action):
        return next(command[command.index(action):] for command in commands if action in command)

    def test_default_build_reuses_local_bases_and_updates_without_pulling_again(self):
        result, commands = self.run_update()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.command(commands, "build"), ["build", "server", "web"])
        update = self.command(commands, "up")
        self.assertIn("--no-build", update)
        self.assertEqual(update[update.index("--pull") + 1], "missing")
        self.assertIn("--wait", update)
        self.assertIn("--wait-timeout", update)

    def test_explicit_pull_can_build_only_backend(self):
        result, commands = self.run_update("--pull", "server")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.command(commands, "build"), ["build", "--pull", "server"])

    def test_frontend_only_build_and_duplicate_services(self):
        result, commands = self.run_update("web", "web")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.command(commands, "build"), ["build", "web"])

    def test_help_needs_no_docker_connection(self):
        result, commands = self.run_update("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("--pull", result.stdout)
        self.assertEqual(commands, [])

    def test_invalid_argument_never_touches_docker(self):
        result, commands = self.run_update("postgres")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(commands, [])

    def test_build_failure_preserves_running_containers(self):
        result, commands = self.run_update(failure="build")
        self.assertEqual(result.returncode, 19)
        self.assertFalse(any("up" in command for command in commands))

    def test_invalid_compose_never_builds_or_updates(self):
        result, commands = self.run_update(failure="config")
        self.assertEqual(result.returncode, 19)
        self.assertFalse(any("build" in command or "up" in command for command in commands))

    def test_health_failure_is_not_reported_as_success(self):
        result, commands = self.run_update(failure="up")
        self.assertEqual(result.returncode, 19)
        self.assertFalse(any("ps" in command for command in commands))
        self.assertNotIn("Local deployment is healthy", result.stdout)

    def test_reports_build_and_update_times(self):
        result, _ = self.run_update()
        self.assertEqual(result.returncode, 0)
        self.assertRegex(result.stdout, r"Build completed in \d+s")
        self.assertRegex(result.stdout, r"Container update completed in \d+s")


if __name__ == "__main__":
    unittest.main()
