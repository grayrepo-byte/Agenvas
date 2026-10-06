#!/usr/bin/env python3
"""Generate version notes and publish GitHub Releases after image publication succeeds."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


VERSION_TAG = re.compile(
    r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)


def version(tag):
    match = VERSION_TAG.fullmatch(tag)
    if not match or any(part.isdigit() and len(part) > 1 and part.startswith("0")
                        for part in (match.group(4) or "").split(".")):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH with an optional SemVer suffix")
    return tuple(int(match.group(index)) for index in (1, 2, 3)), bool(match.group(4))


def run(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def api(*args):
    return json.loads(run("gh", "api", *args))


def tag_order(tag):
    core, prerelease = version(tag)
    identifiers = VERSION_TAG.fullmatch(tag).group(4) or ""
    parts = tuple((0, int(part)) if part.isdigit() else (1, part)
                  for part in identifiers.split(".") if part)
    return core, not prerelease, parts


def remote_commit(repo, tag):
    obj = api(f"repos/{repo}/git/ref/tags/{tag}")["object"]
    # Annotated tags can point to another tag. Bound traversal and require a commit.
    for _ in range(8):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
    raise ValueError("Release tag must resolve to a commit")


def previous_tag(root, tag):
    tags = run("git", "tag", "--merged", tag, "--list", "v[0-9]*", cwd=root)
    current_order = tag_order(tag)
    _, prerelease = version(tag)
    candidates = []
    for candidate in tags.splitlines():
        try:
            _, candidate_prerelease = version(candidate)
            order = tag_order(candidate)
        except ValueError:
            continue
        # Stable users need the complete change range since the last stable version,
        # even if release candidates already published some of those changes.
        if order < current_order and (prerelease or not candidate_prerelease):
            candidates.append(candidate)
    return max(candidates, key=tag_order, default=None)


def release_notes(root, repo, tag, commit, namespace):
    previous = previous_tag(root, tag)
    args = ["--method", "POST", f"repos/{repo}/releases/generate-notes",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={commit}"]
    if previous:
        args += ["-f", f"previous_tag_name={previous}"]
    generated = api(*args)["body"]
    revision = f"{previous}..{tag}" if previous else tag
    changes = run("git", "log", "--no-merges", "--format=- %s (%h)", revision, cwd=root)
    # docker/metadata-action's {{version}} omits SemVer build metadata.
    image_version = tag[1:].split("+", 1)[0]
    body = (
        f"## 部署镜像\n\n"
        f"支持 `linux/amd64` 和 `linux/arm64`。\n\n"
        f"```text\n"
        f"docker.io/{namespace}/agenvas-server:{image_version}\n"
        f"docker.io/{namespace}/agenvas-web:{image_version}\n"
        f"```\n\n"
        f"源码提交：[`{commit[:7]}`](https://github.com/{repo}/commit/{commit})。\n\n"
    )
    if changes:
        body += f"## 更新内容\n\n{changes}\n\n"
    return body + generated.strip() + "\n"


def published_releases(repo):
    # Paginate so retries/backfills can find an older release without a fixed limit.
    pages = api("--paginate", "--slurp", f"repos/{repo}/releases")
    return [release for page in pages for release in page]


def publish(root, repo, tag, commit, namespace):
    releases = published_releases(repo)
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing:
        if existing["draft"]:
            raise ValueError("An existing draft release requires manual publication; it is preserved")
        if (existing.get("body") or "").strip():
            return existing["html_url"]  # Keep hand-written notes and make retries idempotent.
    notes = release_notes(root, repo, tag, commit, namespace)
    with tempfile.TemporaryDirectory(prefix="agenvas-release-") as folder:
        path = Path(folder) / "notes.md"
        path.write_text(notes, encoding="utf-8")
        if existing:
            run("gh", "release", "edit", tag, "--repo", repo, "--notes-file", str(path))
            return existing["html_url"]
        current, prerelease = version(tag)
        newer = False
        for release in releases:
            if release["draft"] or release["prerelease"]:
                continue
            try:
                candidate, candidate_prerelease = version(release["tag_name"])
            except ValueError:
                continue
            newer = newer or (not candidate_prerelease and candidate > current)
        args = ["gh", "release", "create", tag, "--repo", repo, "--verify-tag",
                "--title", f"Agenvas {tag}", "--notes-file", str(path)]
        if prerelease:
            args.append("--prerelease")
        if prerelease or newer:
            args.append("--latest=false")
        return run(*args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["notes", "publish"])
    parser.add_argument("tag")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--image-namespace", default=os.environ.get("IMAGE_NAMESPACE", "grayrepo"))
    parser.add_argument("--expected-sha", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--output", type=Path, help="Write notes to a reviewable Markdown file")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        version(args.tag)
        if not args.repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
            raise ValueError("Specify the GitHub owner/repository with --repo")
        if not re.fullmatch(r"[a-z0-9]+(?:[._-][a-z0-9]+)*", args.image_namespace):
            raise ValueError("Invalid Docker Hub namespace")
        commit = run("git", "rev-parse", "--verify", f"refs/tags/{args.tag}^{{commit}}", cwd=root)
        if (args.expected_sha and args.expected_sha != commit) or remote_commit(args.repo, args.tag) != commit:
            raise ValueError("Release tag no longer matches the validated commit")
        if args.command == "notes":
            notes = release_notes(root, args.repo, args.tag, commit, args.image_namespace)
            if args.output:
                args.output.write_text(notes, encoding="utf-8")
                print(f"Release notes written to {args.output}")
            else:
                print(notes)
        else:
            url = publish(root, args.repo, args.tag, commit, args.image_namespace)
            print(f"GitHub Release: {url}")
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
                    summary.write(f"GitHub Release: {url}\n")
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release operation failed: {error}\n")


if __name__ == "__main__":
    main()
