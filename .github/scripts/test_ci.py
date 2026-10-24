import importlib.util
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ci", SCRIPT_DIR / "ci.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def workflow_jobs(workflow):
    # Job order is cosmetic; only another job at this indentation ends its body.
    jobs = re.split(r"(?m)^jobs:[ \t]*(?:#[^\n]*)?\n", workflow, maxsplit=1)[1]
    parts = re.split(r"\n  ([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(?:#[^\n]*)?\n", "\n" + jobs)
    return dict(zip(parts[1::2], parts[2::2]))


def job_needs(job):
    # Read only this field, accepting GitHub's scalar, flow-list and block-list forms.
    field = re.search(r"(?m)^    needs:[ \t]*(\[[^]]*\]|[^\n]*)", job)
    if field is None:
        return set()
    value = re.sub(r"(?m)#[^\n]*", "", field.group(1)).strip()
    if not value:
        block = re.match(r"\n((?:      (?:- |#)[^\n]*(?:\n|$)|[ \t]*\n)*)", job[field.end():]).group(1)
        values = re.findall(r"(?m)^      - (.+)$", block)
    else:
        values = value.strip("[]").split(",")
    names = {value.split("#", 1)[0].strip().strip("\"'") for value in values}
    if not all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", name) for name in names):
        raise ValueError("Unsupported workflow dependency syntax")
    return names


def job_run_commands(job):
    # This job uses ordinary scalar or literal-block run steps, not a YAML interpreter.
    for step in re.finditer(r"(?m)^(?:      - |        )run: ([^\n]+)\n((?:          [^\n]*\n|\n)*)", job):
        command, block = step.groups()
        yield textwrap.dedent(block) if command.split("#", 1)[0].strip() in {"|", "|-", "|+"} else command


class PlanningTest(unittest.TestCase):
    def test_only_explicit_documentation_paths_can_skip_code_checks(self):
        self.assertTrue(ci.docs_only(["README.md", "docs/adr/decision.md", "LICENSE"]))
        for readme in ["README.en.md", "README.zh-CN.md", "README.ja.md", "README.ru.md"]:
            with self.subTest(readme=readme):
                self.assertEqual(ci.plan("pull_request", "refs/pull/1/merge", [readme])["code_changed"], "false")
        for paths in [[], ["frontend/README.md"], [".github/workflows/ci.yml"],
                      ["README.md", "docker-compose.yml"], ["contracts/openapi.yaml"]]:
            with self.subTest(paths=paths):
                self.assertFalse(ci.docs_only(paths))

    def test_docs_pr_skips_code_but_main_tag_and_dispatch_never_do(self):
        self.assertEqual(ci.plan("pull_request", "refs/pull/1/merge", ["README.md"])["code_changed"], "false")
        for event, ref in [("push", "refs/heads/main"), ("push", "refs/tags/v1.2.3"),
                           ("workflow_dispatch", "refs/heads/main")]:
            with self.subTest(event=event, ref=ref):
                self.assertEqual(ci.plan(event, ref, ["README.md"])["code_changed"], "true")

    def test_image_matrix_is_two_for_pr_four_for_main_and_six_for_release(self):
        cases = [("pull_request", "refs/pull/1/merge", 2), ("push", "refs/heads/main", 4),
                 ("push", "refs/tags/v1.2.3-rc.1", 6),
                 ("workflow_dispatch", "refs/tags/v1.2.3", 4)]
        for event, ref, count in cases:
            with self.subTest(event=event, ref=ref):
                matrix = ci.image_matrix(event, ref)["include"]
                self.assertEqual(len(matrix), count)
                self.assertEqual(len({(item["service"], item["architecture"]) for item in matrix}), count)
                self.assertTrue(all(item["runner"].endswith("-arm") == (item["architecture"] == "arm64") for item in matrix))
        self.assertFalse(any(item["service"] == "postgres" for item in ci.image_matrix("push", "refs/heads/main")["include"]))

    def test_git_diff_includes_deleted_code_and_both_sides_of_a_rename(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
            git("init", "-q")
            (root / "app.java").write_text("class App {}")
            (root / "old.java").write_text("class Old {}")
            git("add", ".")
            git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid", "commit", "-qm", "Fixture")
            base = git("rev-parse", "HEAD")
            (root / "app.java").rename(root / "README.md")
            (root / "old.java").unlink()
            git("add", "-A")
            git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid", "commit", "-qm", "Rename fixture")
            paths = ci.changed_paths({"pull_request": {"base": {"sha": base}}}, root)
            self.assertEqual(set(paths), {"app.java", "old.java", "README.md"})
            self.assertFalse(ci.docs_only(paths))


class ShardingTest(unittest.TestCase):
    def test_matches_failsafe_patterns_and_is_exhaustive_without_duplicates(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for filename in ["ITFirst.java", "LastIT.java", "NewITCase.java", "OrdinaryTest.java", "ITOther.java"]:
                path = root / "new/package" / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            classes = ci.integration_classes(root)
            self.assertEqual(len(classes), 4)
            selected = [name for index in range(4) for name in ci.integration_shard(root, index, 4)]
            self.assertCountEqual(selected, classes)
            self.assertEqual(len(selected), len(set(selected)))

    def test_repository_classes_are_all_assigned_once(self):
        root = SCRIPT_DIR.parents[1] / "backend/src/test/java"
        classes = ci.integration_classes(root)
        shards = [ci.integration_shard(root, index, 4) for index in range(4)]
        self.assertGreater(len(classes), 0)
        self.assertCountEqual([name for shard in shards for name in shard], classes)
        self.assertLessEqual(max(map(len, shards)) - min(map(len, shards)), 1)

    def test_empty_and_invalid_shards_fail_instead_of_skipping(self):
        with tempfile.TemporaryDirectory() as folder:
            for index, count in [(0, 4), (-1, 4), (4, 4), (0, 0)]:
                with self.subTest(index=index, count=count), self.assertRaises(ValueError):
                    ci.integration_shard(Path(folder), index, count)


class RuntimeDependenciesTest(unittest.TestCase):
    def test_backend_unit_installs_media_tools_before_running_tests(self):
        workflow = (SCRIPT_DIR.parents[1] / ".github/workflows/ci.yml").read_text()
        unit_job = workflow_jobs(workflow)["backend-unit"]
        # These tests launch /usr/bin/ffmpeg and /usr/bin/ffprobe; Ubuntu's
        # ffmpeg package supplies both binaries before Surefire starts. Exercise
        # the commands with isolated tools, allowing harmless shell/apt flag edits.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            scripts = {
                # Only common flag-only sudo/apt options are needed here; these
                # stand-ins deliberately do not parse arbitrary command syntax.
                "sudo": 'while [ "$#" -gt 0 ]; do\n'
                        '  case "$1" in\n'
                        '    -n|--non-interactive|-E) shift ;;\n'
                        '    --) shift; break ;;\n'
                        '    *) break ;;\n'
                        '  esac\ndone\nexec "$@"\n',
                "apt-get": 'while [ "$#" -gt 0 ]; do\n'
                           '  case "$1" in -*) shift ;; *) break ;; esac\n'
                           'done\n'
                           'if [ "${1:-}" = install ]; then\n'
                           '  shift\n'
                           '  for package in "$@"; do\n'
                           '    if [ "$package" = ffmpeg ]; then touch "$MEDIA_TOOLS_READY"; fi\n'
                           '  done\nfi\n',
                "mvnw": 'test -f "$MEDIA_TOOLS_READY" || { echo "Media tools missing before Maven" >&2; exit 1; }\n'
                        'touch "$MAVEN_STARTED"\n',
            }
            for name, script in scripts.items():
                path = root / name
                path.write_text("#!/bin/sh\nset -eu\n" + script)
                path.chmod(0o700)
            environment = {"PATH": f"{root}:/usr/bin:/bin",
                           "MEDIA_TOOLS_READY": str(root / "media-ready"),
                           "MAVEN_STARTED": str(root / "maven-started")}
            for command in job_run_commands(unit_job):
                result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", command],
                                        cwd=root, env=environment, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((root / "maven-started").is_file(), "The workflow must run Maven after installing media tools")


class GateTest(unittest.TestCase):
    def test_publishing_credentials_are_only_used_after_the_gate_on_tag_pushes(self):
        workflow = (SCRIPT_DIR.parents[1] / ".github/workflows/ci.yml").read_text()
        sections = workflow_jobs(workflow)
        credential_jobs = {job for job, body in sections.items() if "secrets.DOCKERHUB_" in body}
        self.assertEqual(credential_jobs, {"push-images", "publish-dockerhub"})
        self.assertEqual(job_needs(sections["push-images"]), {"ci-gate"})
        self.assertEqual(job_needs(sections["publish-dockerhub"]), {"push-images"})
        for job in credential_jobs:
            self.assertIn("if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')", sections[job])

    def test_workflow_gate_waits_for_every_validation_job(self):
        workflow = (SCRIPT_DIR.parents[1] / ".github/workflows/ci.yml").read_text()
        sections = workflow_jobs(workflow)
        jobs = set(sections)
        dependencies = job_needs(sections["ci-gate"])
        self.assertEqual(dependencies, jobs - {"ci-gate", "push-images", "publish-dockerhub", "github-release"})
        self.assertEqual(dependencies, set(ci.CODE_JOBS + ci.ALWAYS_JOBS))

    def test_release_write_permission_is_gated_by_successful_tag_image_publication(self):
        workflow = (SCRIPT_DIR.parents[1] / ".github/workflows/ci.yml").read_text()
        release_job = workflow_jobs(workflow)["github-release"]
        self.assertEqual(job_needs(release_job), {"ci-gate", "publish-dockerhub"})
        self.assertIn("if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')", release_job)
        self.assertNotIn("always()", release_job)
        self.assertEqual(workflow.count("contents: write"), 1)
        self.assertIn("permissions:\n      contents: write", release_job)
        self.assertIn("fetch-depth: 0", release_job)
        self.assertIn('release.py publish "$RELEASE_TAG"', release_job)
        self.assertNotIn("secrets.DOCKERHUB_", release_job)

    def needs(self, code_changed):
        expected = "success" if code_changed else "skipped"
        needs = {job: {"result": expected} for job in ci.CODE_JOBS}
        needs.update({job: {"result": "success"} for job in ci.ALWAYS_JOBS})
        needs["changes"]["outputs"] = {"code_changed": str(code_changed).lower()}
        return needs

    def test_full_success_and_planned_docs_skips_pass(self):
        self.assertEqual(ci.gate_errors(self.needs(True)), [])
        self.assertEqual(ci.gate_errors(self.needs(False)), [])

    def test_every_failed_cancelled_missing_or_unexpectedly_skipped_job_fails(self):
        for job in ci.CODE_JOBS + ci.ALWAYS_JOBS:
            for result in ["failure", "cancelled", "skipped", None]:
                with self.subTest(job=job, result=result):
                    needs = self.needs(True)
                    needs[job]["result"] = result
                    self.assertTrue(ci.gate_errors(needs))

    def test_secret_scan_must_pass_for_documentation_prs_too(self):
        needs = self.needs(False)
        needs["security-source"]["result"] = "failure"
        self.assertTrue(ci.gate_errors(needs))

    def test_missing_or_malformed_plan_cannot_pass(self):
        for value in [None, "", "False", "maybe"]:
            with self.subTest(value=value):
                needs = self.needs(False)
                needs["changes"]["outputs"]["code_changed"] = value
                self.assertTrue(ci.gate_errors(needs))


if __name__ == "__main__":
    unittest.main()
