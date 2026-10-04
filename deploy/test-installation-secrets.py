#!/usr/bin/env python3
"""Targeted deployment tests. Only temporary Compose projects/volumes are modified.

Secret values stay in subprocess buffers or private temporary files; failures never
print them. No application build, model request, or existing deployment is used.
"""
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

REPO = Path(__file__).resolve().parent.parent
SECRET_ROOT = "/run/agenvas/credentials/installation"
SECRET_FILES = {
    "AGENVAS_DB_PASSWORD": "database-password",
    "AGENVAS_CREDENTIAL_MASTER_KEY": "credential-master-key",
}
# Explicitly synthetic import fixtures, never credentials for an installation.
SYNTHETIC_SECRETS = {
    "AGENVAS_DB_PASSWORD": "synthetic-test-database-password",
    "AGENVAS_CREDENTIAL_MASTER_KEY": base64.b64encode(bytes(range(32))).decode(),
}


def run_command(command, *, env=None, expect_success=True):
    result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=120)
    if expect_success and result.returncode:
        # Docker/psql output may contain secrets; report only the action and exit status.
        raise RuntimeError(f"{command[0]} operation failed (exit {result.returncode}); output withheld")
    return result


class InstallationSecretsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        result = run_command([
            "docker", "compose", "--env-file", "/dev/null", "-f",
            str(REPO / "docker-compose.yml"), "config", "--format", "json",
        ])
        cls.postgres = json.loads(result.stdout)["services"]["postgres"]
        cls.image = cls.postgres["image"]
        if run_command(["docker", "image", "inspect", cls.image], expect_success=False).returncode:
            run_command(["docker", "pull", cls.image])

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="agenvas-installation-test-")
        self.project = "agenvas-secret-test-" + uuid.uuid4().hex[:12]
        self.config_file = Path(self.directory.name) / "compose.json"
        postgres = copy.deepcopy(self.postgres)
        postgres.pop("ports", None)
        postgres["restart"] = "no"
        postgres["pull_policy"] = "never"
        config = {
            "services": {"postgres": postgres},
            "volumes": {"postgres-data": {}, "credentials-data": {}},
        }
        self.config_file.write_text(json.dumps(config))
        self.config_file.chmod(0o600)
        self.compose = [
            "docker", "compose", "--env-file", "/dev/null", "-p", self.project,
            "-f", str(self.config_file),
        ]

    def tearDown(self):
        try:
            run_command(self.compose + ["down", "--volumes", "--remove-orphans"])
        finally:
            self.directory.cleanup()

    def start_database(self):
        run_command(self.compose + ["up", "-d", "--wait", "--wait-timeout", "90"])

    def generate(self, overrides=None, *, expect_success=True):
        command = self.compose + ["run", "--rm", "--no-deps", "-T"]
        environment = os.environ.copy()
        for name, value in (overrides or {}).items():
            environment[name] = value
            # Values are passed via inherited process environment, not command arguments.
            command.extend(["-e", name])
        return run_command(command + ["postgres", "true"], env=environment, expect_success=expect_success)

    def volume_command(self, script, *, source="credentials-data", user="0", readonly=False):
        mount = f"type=volume,source={self.project}_{source},target=/volume"
        if readonly:
            mount += ",readonly"
        return run_command([
            "docker", "run", "--rm", "--read-only", "--user", user,
            "--mount", mount, "--entrypoint", "/bin/sh", self.image, "-ec", script,
        ])

    def read_secrets(self):
        return {
            name: self.volume_command(f"cat /volume/installation/{file}", readonly=True).stdout.strip()
            for name, file in SECRET_FILES.items()
        }

    def assert_no_secret_logs(self, output, values):
        self.assertTrue(all(value not in output for value in values.values()), "A secret appeared in logs")

    def test_first_start_database_authentication_and_restart(self):
        self.start_database()
        original = self.read_secrets()
        self.assertTrue(len(original["AGENVAS_DB_PASSWORD"]) == 64, "Database password length differs")
        self.assertTrue(len(base64.b64decode(original["AGENVAS_CREDENTIAL_MASTER_KEY"], validate=True)) == 32,
                        "Master key does not decode to 32 bytes")
        login = run_command(self.compose + ["exec", "-T", "postgres", "sh", "-ec",
            'export PGPASSWORD=$(cat "$POSTGRES_PASSWORD_FILE"); '
            'psql -h postgres -U agenvas -d agenvas -Atc "select 1"'])
        # The official entrypoint unsets POSTGRES_PASSWORD_FILE in its process only;
        # docker exec still receives the original container configuration.
        self.assertTrue(login.stdout.strip() == "1", "Generated database password could not authenticate")
        bad_login = run_command(self.compose + ["exec", "-T", "postgres", "sh", "-ec",
            'PGPASSWORD=synthetic-test-wrong-password psql -h postgres -U agenvas -d agenvas -Atc "select 1"'],
            expect_success=False)
        self.assertTrue(bad_login.returncode != 0, "Database accepted an incorrect password")
        permissions = self.volume_command(
            "stat -c '%a:%u:%g' /volume /volume/installation /volume/installation/*", readonly=True)
        self.assertEqual(permissions.stdout.splitlines(), ["700:100:101", "700:100:101"] + ["600:100:101"] * 2)
        self.assert_nonroot_server_can_read()
        run_command(self.compose + ["up", "-d", "--force-recreate", "--wait", "--wait-timeout", "90"])
        self.assertTrue(self.read_secrets() == original, "Recreation changed persisted secrets")
        self.assert_no_secret_logs(run_command(self.compose + ["logs", "--no-color"]).stdout, original)

    def assert_nonroot_server_can_read(self):
        command = ["docker", "run", "--rm", "--read-only", "--user", "100:101",
            "--security-opt", "no-new-privileges:true",
            "--mount", f"type=volume,source={self.project}_credentials-data,target=/run/agenvas/credentials,readonly",
            "--mount", f"type=bind,source={REPO / 'deploy/docker/server-entrypoint.sh'},target=/server-entrypoint.sh,readonly"]
        for name, file in SECRET_FILES.items():
            command.extend(["-e", f"{name}_FILE={SECRET_ROOT}/{file}"])
        checks = 'test "$$" -eq 1 || { echo "Entrypoint failed to exec as PID 1" >&2; exit 1; }; '
        for name, file in SECRET_FILES.items():
            checks += f'test "${name}" = "$(cat {SECRET_ROOT}/{file})" || {{ echo "File import failed for {name}" >&2; exit 1; }}; '
        checks += 'if touch /run/agenvas/credentials/installation/.readonly-probe 2>/dev/null; then echo "Secret mount accepted a write" >&2; exit 1; fi'
        run_command(command + ["--entrypoint", "/bin/sh", self.image,
            "/server-entrypoint.sh", "/bin/sh", "-ec", checks])

    def test_independent_installations_generate_different_secrets(self):
        self.generate()
        original = self.read_secrets()
        # Remove only this test's volumes, then prove a new installation differs.
        run_command(self.compose + ["down", "--volumes"])
        self.generate()
        current = self.read_secrets()
        self.assertTrue(all(current[name] != original[name] for name in SECRET_FILES),
                        "Independent installations shared a secret")

    def test_import_and_subsequent_starts_preserve_supplied_values(self):
        result = self.generate(SYNTHETIC_SECRETS)
        self.assertTrue(self.read_secrets() == SYNTHETIC_SECRETS, "Imported values changed")
        self.generate()
        self.assertTrue(self.read_secrets() == SYNTHETIC_SECRETS, "Restart changed imported values")
        self.assert_no_secret_logs(result.stdout + result.stderr, SYNTHETIC_SECRETS)

    def test_existing_database_requires_original_secrets(self):
        # Simulate an already initialized data volume without modifying a live database.
        self.volume_command("printf '17\n' > /volume/PG_VERSION", source="postgres-data")
        result = self.generate(expect_success=False)
        self.assertTrue(result.returncode != 0, "Existing database received invented secrets")
        self.assertIn("Existing database has no installation secrets", result.stderr)
        self.volume_command("test ! -e /volume/installation", readonly=True)
        self.generate(SYNTHETIC_SECRETS)
        self.assertTrue(self.read_secrets() == SYNTHETIC_SECRETS, "Original secrets were not imported")

    def test_incomplete_bundle_is_rejected_without_regeneration(self):
        self.generate()
        original = self.read_secrets()
        self.volume_command("rm /volume/installation/credential-master-key")
        result = self.generate(expect_success=False)
        self.assertTrue(result.returncode != 0, "Partial bundle was silently regenerated")
        self.assertIn("Incomplete installation secrets", result.stderr)
        self.volume_command("test ! -e /volume/installation/credential-master-key", readonly=True)
        retained = self.volume_command("cat /volume/installation/database-password", readonly=True).stdout.strip()
        self.assertTrue(retained == original["AGENVAS_DB_PASSWORD"], "Retained secret changed")

    def test_changed_override_is_rejected_without_overwriting(self):
        self.generate()
        original = self.read_secrets()
        result = self.generate({"AGENVAS_DB_PASSWORD": SYNTHETIC_SECRETS["AGENVAS_DB_PASSWORD"]},
                               expect_success=False)
        self.assertTrue(result.returncode != 0, "Conflicting override was accepted")
        self.assertTrue(self.read_secrets() == original, "Conflicting override overwrote secrets")
        self.assert_no_secret_logs(result.stdout + result.stderr, original)

    def test_invalid_imports_do_not_publish_a_bundle(self):
        for field, value in [("AGENVAS_DB_PASSWORD", "x" * 513),
                             ("AGENVAS_CREDENTIAL_MASTER_KEY", "synthetic-invalid-base64"),
                             ("AGENVAS_DB_PASSWORD", "synthetic\nnewline")]:
            with self.subTest(field=field):
                overrides = dict(SYNTHETIC_SECRETS)
                overrides[field] = value
                result = self.generate(overrides, expect_success=False)
                self.assertTrue(result.returncode != 0, "Invalid import was accepted")
                self.volume_command("test ! -e /volume/installation", readonly=True)
                self.assert_no_secret_logs(result.stdout + result.stderr, overrides)

    def test_server_entrypoint_rejects_missing_and_conflicting_file_settings(self):
        entrypoint = str(REPO / "deploy/docker/server-entrypoint.sh")
        environment = {key: value for key, value in os.environ.items() if not key.startswith("AGENVAS_")}
        secret_file = Path(self.directory.name) / "synthetic-secret"
        secret_file.write_text(SYNTHETIC_SECRETS["AGENVAS_DB_PASSWORD"])
        secret_file.chmod(0o600)
        for file, value in [(str(secret_file) + ".missing", ""),
                            (str(secret_file), SYNTHETIC_SECRETS["AGENVAS_DB_PASSWORD"])]:
            environment.update(AGENVAS_DB_PASSWORD_FILE=file, AGENVAS_DB_PASSWORD=value)
            result = run_command(["sh", entrypoint, "true"], env=environment, expect_success=False)
            self.assertTrue(result.returncode != 0, "Invalid server secret configuration was accepted")
            self.assert_no_secret_logs(result.stdout + result.stderr, SYNTHETIC_SECRETS)
        environment.pop("AGENVAS_DB_PASSWORD_FILE")
        result = run_command(["sh", entrypoint, "sh", "-ec", 'test -n "$AGENVAS_DB_PASSWORD"'], env=environment)
        self.assertEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
