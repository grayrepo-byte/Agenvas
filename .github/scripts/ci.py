#!/usr/bin/env python3
"""Plan CI work, partition Failsafe classes, and reject incomplete CI gates."""

import argparse
import json
import os
from pathlib import Path
import subprocess


CODE_JOBS = (
    "frontend", "backend-unit", "backend-integration", "jooq-codegen",
    "compose-config", "security-images",
)
ALWAYS_JOBS = ("changes", "security-source")
DOC_FILES = {"README.md", "README.en.md", "LICENSE", "CHANGELOG.md"}


def docs_only(paths):
    """Use an explicit allowlist: unknown files must receive full validation."""
    return bool(paths) and all(path in DOC_FILES or path.startswith("docs/") for path in paths)


def image_matrix(event_name, ref):
    architectures = ["amd64"] if event_name == "pull_request" else ["amd64", "arm64"]
    release = event_name == "push" and ref.startswith("refs/tags/v")
    services = ["server", "web", "postgres"] if release else ["server", "web"]
    return {"include": [
        {
            "architecture": architecture,
            "runner": "ubuntu-24.04" if architecture == "amd64" else "ubuntu-24.04-arm",
            "service": service,
            "image": "postgres:17.11-alpine" if service == "postgres" else f"agenvas-{service}:ci",
            "dockerfile": "" if service == "postgres" else
                f"deploy/docker/{'frontend' if service == 'web' else 'server'}.Dockerfile",
        }
        for architecture in architectures for service in services
    ]}


def plan(event_name, ref, paths):
    # Main, release, and manual runs always validate the complete repository.
    code_changed = event_name != "pull_request" or not docs_only(paths)
    return {"code_changed": str(code_changed).lower(), "images": image_matrix(event_name, ref)}


def integration_classes(source_root):
    """Match all three default Failsafe patterns, including newly added packages."""
    classes = []
    for path in source_root.rglob("*.java"):
        name = path.stem
        if name.startswith("IT") or name.endswith(("IT", "ITCase")):
            classes.append(".".join(path.relative_to(source_root).with_suffix("").parts))
    return sorted(classes)


def integration_shard(source_root, index, count):
    if count < 1 or index < 0 or index >= count:
        raise ValueError("Invalid integration shard index/count")
    # Every discovered class belongs to exactly one shard; databases remain isolated per class.
    selected = integration_classes(source_root)[index::count]
    if not selected:
        raise ValueError("Empty integration shard: refusing to silently skip integration tests")
    return selected


def gate_errors(needs):
    """A skipped job is valid only for an explicitly planned documentation-only PR."""
    errors = []
    for job in ALWAYS_JOBS:
        result = needs.get(job, {}).get("result")
        if result != "success":
            errors.append(f"{job}: expected success, got {result}")
    code_changed = needs.get("changes", {}).get("outputs", {}).get("code_changed")
    if code_changed not in {"true", "false"}:
        errors.append("changes: missing or invalid code_changed output")
    expected = "skipped" if code_changed == "false" else "success"
    for job in CODE_JOBS:
        result = needs.get(job, {}).get("result")
        if result != expected:
            errors.append(f"{job}: expected {expected}, got {result}")
    return errors


def changed_paths(event, repo):
    # Checkout uses the PR merge commit; comparing it to the base includes additions,
    # deletions and both sides of renames without placing PR-controlled text in shell code.
    result = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "-z", event["pull_request"]["base"]["sha"], "HEAD"],
        cwd=repo, check=True, capture_output=True,
    )
    return result.stdout.decode().split("\0")[:-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("plan")
    shard = commands.add_parser("shard")
    shard.add_argument("index", type=int)
    shard.add_argument("count", type=int)
    commands.add_parser("gate")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    if args.command == "plan":
        event_name = os.environ["GITHUB_EVENT_NAME"]
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        paths = changed_paths(event, repo) if event_name == "pull_request" else []
        outputs = plan(event_name, os.environ["GITHUB_REF"], paths)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            for key, value in outputs.items():
                encoded = json.dumps(value, separators=(",", ":")) if isinstance(value, dict) else value
                output.write(f"{key}={encoded}\n")
        print(f"Full code validation: {outputs['code_changed']}")
    elif args.command == "shard":
        print(",".join(integration_shard(repo / "backend/src/test/java", args.index, args.count)))
    else:
        errors = gate_errors(json.loads(os.environ["CI_NEEDS"]))
        if errors:
            parser.exit(1, "CI gate failed:\n" + "\n".join(errors) + "\n")
        print("CI gate passed.")


if __name__ == "__main__":
    main()
