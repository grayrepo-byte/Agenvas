import importlib.util
from contextlib import redirect_stderr
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
REPO = "example/agenvas"
SHA = "a" * 40
URL = f"https://github.com/{REPO}/releases/tag/v1.2.3"


def existing(tag="v1.2.3", body="Hand-written notes", draft=False, prerelease=False):
    return {"tag_name": tag, "body": body, "draft": draft,
            "prerelease": prerelease, "html_url": URL}


class NotesTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.git("init", "-q")
        self.git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
                 "commit", "--allow-empty", "-qm", "Initial fixture")
        self.git("tag", "v1.2.2")
        self.git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
                 "commit", "--allow-empty", "-qm", "fix: preserve a synthetic draft")
        self.git("tag", "v1.2.3")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def test_previous_git_tag_sets_the_range_even_without_previous_github_releases(self):
        self.assertEqual(release.previous_tag(self.root, "v1.2.3"), "v1.2.2")
        with patch.object(release, "api", return_value={"body": "## What's Changed\nSynthetic PR\n"}) as api:
            notes = release.release_notes(self.root, REPO, "v1.2.3", SHA, "example")
        args = api.call_args.args
        self.assertIn("previous_tag_name=v1.2.2", args)
        self.assertIn("tag_name=v1.2.3", args)
        self.assertIn(f"target_commitish={SHA}", args)
        self.assertIn("fix: preserve a synthetic draft", notes)
        self.assertNotIn("Initial fixture", notes)
        self.assertIn("docker.io/example/agenvas-server:1.2.3", notes)
        self.assertIn("docker.io/example/agenvas-web:1.2.3", notes)
        self.assertIn(f"https://github.com/{REPO}/commit/{SHA}", notes)
        self.assertIn("Synthetic PR", notes)

    def test_first_release_has_changes_without_a_previous_tag(self):
        self.git("tag", "-d", "v1.2.2")
        with patch.object(release, "api", return_value={"body": "First release"}) as api:
            notes = release.release_notes(self.root, REPO, "v1.2.3", SHA, "example")
        self.assertFalse(any(arg.startswith("previous_tag_name=") for arg in api.call_args.args))
        self.assertIn("Initial fixture", notes)

    def test_unrelated_newer_tag_is_not_used_as_the_previous_version(self):
        self.git("checkout", "--orphan", "other")
        self.git("-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
                 "commit", "--allow-empty", "-qm", "Unrelated fixture")
        self.git("tag", "v9.0.0")
        self.assertEqual(release.previous_tag(self.root, "v1.2.3"), "v1.2.2")

    def test_stable_notes_include_the_full_range_instead_of_only_release_candidate_changes(self):
        self.git("tag", "v1.2.3-rc.1")
        self.assertEqual(release.previous_tag(self.root, "v1.2.3"), "v1.2.2")

    def test_release_candidate_order_uses_numeric_semver_identifiers(self):
        for tag in ["v1.2.3-rc.2", "v1.2.3-rc.9", "v1.2.3-rc.10"]:
            self.git("tag", tag)
        self.assertEqual(release.previous_tag(self.root, "v1.2.3-rc.10"), "v1.2.3-rc.9")

    def test_prerelease_image_names_keep_suffixes_and_drop_build_metadata(self):
        self.git("tag", "v1.2.3-rc.1+build.7")
        with patch.object(release, "api", return_value={"body": "Prerelease"}):
            notes = release.release_notes(self.root, REPO, "v1.2.3-rc.1+build.7", SHA, "example")
        self.assertIn("agenvas-web:1.2.3-rc.1\n", notes)
        self.assertNotIn(":1.2.3-rc.1+build", notes)


class PublicationTest(unittest.TestCase):
    def invoke(self, tag="v1.2.3", releases=()):
        commands = []
        def capture(*args):
            commands.append(args)
            notes_path = Path(args[args.index("--notes-file") + 1])
            self.assertEqual(notes_path.read_text(), "Synthetic complete notes\n")
            return URL
        with patch.object(release, "published_releases", return_value=releases), \
                patch.object(release, "release_notes", return_value="Synthetic complete notes\n"), \
                patch.object(release, "run", side_effect=capture):
            self.assertEqual(release.publish(Path("."), REPO, tag, SHA, "example"), URL)
        return commands

    def test_stable_release_creates_notes_for_an_existing_remote_tag(self):
        commands = self.invoke()
        self.assertEqual(len(commands), 1)
        self.assertEqual(commands[0][:4], ("gh", "release", "create", "v1.2.3"))
        self.assertIn("--verify-tag", commands[0])
        self.assertIn("--title", commands[0])
        self.assertNotIn("--prerelease", commands[0])
        self.assertNotIn("--latest=false", commands[0])

    def test_prereleases_never_become_latest(self):
        command = self.invoke(tag="v1.2.3-rc.1")[0]
        self.assertIn("--prerelease", command)
        self.assertIn("--latest=false", command)

    def test_backfilling_an_older_stable_version_preserves_latest(self):
        command = self.invoke(releases=[existing(tag="v1.10.0")])[0]
        self.assertIn("--latest=false", command)

    def test_retries_preserve_existing_notes_without_generating_or_writing_anything(self):
        with patch.object(release, "published_releases", return_value=[existing()]), \
                patch.object(release, "release_notes") as notes, patch.object(release, "run") as run:
            self.assertEqual(release.publish(Path("."), REPO, "v1.2.3", SHA, "example"), URL)
            notes.assert_not_called()
            run.assert_not_called()

    def test_only_empty_published_notes_are_filled_without_changing_release_status(self):
        command = self.invoke(releases=[existing(body="  ")])[0]
        self.assertEqual(command[:4], ("gh", "release", "edit", "v1.2.3"))
        self.assertNotIn("--latest", command)
        self.assertNotIn("--prerelease", command)
        self.assertNotIn("--draft", command)

    def test_existing_drafts_are_preserved(self):
        with patch.object(release, "published_releases", return_value=[existing(body="", draft=True)]), \
                patch.object(release, "run") as run, self.assertRaisesRegex(ValueError, "draft release"):
            release.publish(Path("."), REPO, "v1.2.3", SHA, "example")
        run.assert_not_called()

    def test_listing_failure_cannot_be_mistaken_for_a_missing_release(self):
        with patch.object(release, "api", side_effect=subprocess.CalledProcessError(1, ["gh", "api"])), \
                patch.object(release, "run") as run, self.assertRaises(subprocess.CalledProcessError):
            release.publish(Path("."), REPO, "v1.2.3", SHA, "example")
        run.assert_not_called()

    def test_release_lookup_paginates_old_versions(self):
        pages = [[existing(tag="v2.0.0")], [existing()]]
        with patch.object(release, "api", return_value=pages) as api:
            self.assertEqual(len(release.published_releases(REPO)), 2)
        self.assertEqual(api.call_args.args[:2], ("--paginate", "--slurp"))


class TagSafetyTest(unittest.TestCase):
    def test_malformed_and_shell_like_tags_are_rejected(self):
        for tag in ["main", "v1.2", "v01.2.3", "v1.2.3-01", "v1.2.3;echo", "v1.2.3\nother"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.version(tag)
        self.assertEqual(release.version("v1.2.3-rc.1"), ((1, 2, 3), True))

    def test_lightweight_and_annotated_tags_resolve_to_the_source_commit(self):
        with patch.object(release, "api", return_value={"object": {"type": "commit", "sha": SHA}}):
            self.assertEqual(release.remote_commit(REPO, "v1.2.3"), SHA)
        with patch.object(release, "api", side_effect=[
                {"object": {"type": "tag", "sha": "b" * 40}},
                {"object": {"type": "commit", "sha": SHA}}]):
            self.assertEqual(release.remote_commit(REPO, "v1.2.3"), SHA)

    def test_moved_remote_tag_blocks_publication(self):
        with patch.object(sys, "argv", ["release.py", "publish", "v1.2.3", "--repo", REPO]), \
                patch.dict(release.os.environ, {"GITHUB_SHA": SHA}), \
                patch.object(release, "run", return_value=SHA), \
                patch.object(release, "remote_commit", return_value="b" * 40), \
                patch.object(release, "publish") as publish, redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as exited:
            release.main()
        self.assertEqual(exited.exception.code, 1)
        publish.assert_not_called()


if __name__ == "__main__":
    unittest.main()
