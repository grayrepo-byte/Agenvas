#!/usr/bin/env python3
"""Web health regression; optional Docker fixture uses an existing image and no host ports."""
from functools import lru_cache
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parent.parent
COMPOSE_FILES = ("docker-compose.yml", "docker-compose.local.yml", "deploy/compose.yaml", "deploy/compose.dev.yaml")


@lru_cache(maxsize=None)
def compose_probe(path):
    environment = dict(os.environ)
    environment.pop("COMPOSE_FILE", None)
    environment.pop("COMPOSE_PROJECT_NAME", None)
    configured = subprocess.run(
        ["docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / path), "config", "--format", "json"],
        check=True, capture_output=True, text=True, timeout=60, env=environment,
    )
    return json.loads(configured.stdout)["services"]["web"]["healthcheck"]["test"]


class WebHealthConfigurationTest(unittest.TestCase):
    def test_probe_uses_nginx_ipv4_listener_in_all_entry_points(self):
        for path in COMPOSE_FILES:
            with self.subTest(compose=path):
                self.assertEqual(compose_probe(path), [
                    "CMD-SHELL", "wget -qO- http://127.0.0.1:8080/ >/dev/null",
                ])


@unittest.skipUnless(os.environ.get("AGENVAS_TEST_WEB_IMAGE"), "set AGENVAS_TEST_WEB_IMAGE to an existing local web image")
class WebHealthContainerTest(unittest.TestCase):
    def test_configured_probes_succeed_against_real_nginx(self):
        name = "agenvas-health-test-" + uuid.uuid4().hex[:12]
        try:
            subprocess.run([
                "docker", "run", "--detach", "--pull=never", "--name", name,
                "--network", "none", "--read-only", "--tmpfs", "/tmp:size=16m,mode=1777",
                "--add-host", "server:127.0.0.1", "--entrypoint", "nginx",
                os.environ["AGENVAS_TEST_WEB_IMAGE"], "-g", "daemon off;",
            ], check=True, capture_output=True, text=True, timeout=30)
            for attempt in range(20):
                ready = subprocess.run([
                    "docker", "exec", name, "wget", "-q", "-T", "1", "-O", "/dev/null",
                    "http://127.0.0.1:8080/",
                ], capture_output=True, timeout=5)
                if ready.returncode == 0:
                    break
                time.sleep(0.1)
            self.assertEqual(ready.returncode, 0, "isolated Nginx did not become ready on IPv4")
            for path in COMPOSE_FILES:
                with self.subTest(compose=path):
                    probe = compose_probe(path)
                    self.assertEqual(probe[0], "CMD-SHELL")
                    checked = subprocess.run(
                        ["docker", "exec", name, "sh", "-ec", probe[1]],
                        capture_output=True, text=True, timeout=5,
                    )
                    self.assertEqual(checked.returncode, 0, checked.stderr)
        finally:
            subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
