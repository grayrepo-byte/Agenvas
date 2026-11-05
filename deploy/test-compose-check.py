#!/usr/bin/env python3
"""Exercise Compose assertions with CLI output variants; never start containers."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parent.parent
COMPOSE_FILES = ("docker-compose.yml", "docker-compose.local.yml", "deploy/compose.yaml", "deploy/compose.dev.yaml")


class ComposeCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        environment = dict(os.environ)
        environment.pop("COMPOSE_FILE", None)
        environment.pop("COMPOSE_PROJECT_NAME", None)
        cls.configs = {}
        for name in COMPOSE_FILES:
            result = subprocess.run([
                "docker", "compose", "--env-file", "/dev/null", "-f", str(REPO / name),
                "config", "--format", "json",
            ], env=environment, capture_output=True, text=True, check=True, timeout=30)
            cls.configs[Path(name).name] = json.loads(result.stdout)

    def run_check(self, extra_hosts):
        with tempfile.TemporaryDirectory(prefix="agenvas-compose-check-") as directory:
            root = Path(directory)
            configs = copy.deepcopy(self.configs)
            for config in configs.values():
                config["services"]["server"]["extra_hosts"] = extra_hosts
            (root / "configs.json").write_text(json.dumps(configs))
            docker = root / "docker"
            docker.write_text(f"#!{sys.executable}\n" + """
import json, pathlib, sys
args = sys.argv[1:]
if not args or args[0] != 'compose' or 'config' not in args:
    sys.exit(2)
if '--quiet' not in args:
    name = pathlib.Path(args[args.index('-f') + 1]).name if '-f' in args else 'docker-compose.yml'
    configs = json.loads((pathlib.Path(__file__).parent / 'configs.json').read_text())
    print(json.dumps(configs[name]))
""")
            docker.chmod(0o700)
            return subprocess.run(["bash", str(REPO / "deploy/check-compose.sh")],
                                  env=dict(os.environ, PATH=f"{root}:{os.environ['PATH']}"),
                                  capture_output=True, text=True, timeout=30)

    def test_accepts_equivalent_host_gateway_encodings(self):
        for hosts in (["host.docker.internal:host-gateway"],
                      ["host.docker.internal=host-gateway"],
                      {"host.docker.internal": "host-gateway"}):
            with self.subTest(extra_hosts=hosts):
                self.assertEqual(self.run_check(hosts).returncode, 0)

    def test_rejects_missing_or_incorrect_host_gateway(self):
        for hosts in ([], ["other.host=host-gateway"],
                      ["host.docker.internal=127.0.0.1"],
                      {"host.docker.internal": "127.0.0.1"}):
            with self.subTest(extra_hosts=hosts):
                self.assertNotEqual(self.run_check(hosts).returncode, 0)


if __name__ == "__main__":
    unittest.main()
