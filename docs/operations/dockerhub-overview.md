# Maintaining Docker Hub Overviews

The [Docker Hub Overview workflow](../../.github/workflows/dockerhub-overview.yml) publishes two independently maintained English documents:

- [Server Overview](../dockerhub/server.md) → `grayrepo/agenvas-server`.
- [Web Overview](../dockerhub/web.md) → `grayrepo/agenvas-web`.

The workflow also sets each repository's short description. Keep these documents aligned with the root `docker-compose.yml`, the runtime images, and the tag rules in `.github/workflows/ci.yml`. Use absolute links because Docker Hub does not resolve repository-relative Markdown links the same way GitHub does.

## Triggers and isolation

A push to `main` that changes either Overview, the Overview workflow/tests, the root Compose file, or the image CI workflow triggers synchronization. Pull requests touching these paths validate inputs without receiving Docker Hub credentials. Fork repositories, tag pushes, and manual runs on other branches do not synchronize.

To retry an update without building or publishing images, open **Actions → Docker Hub Overview → Run workflow**, select **main**, and run it. The workflow must first exist on the repository's default branch to appear in this menu. A documentation-only update does not need a release tag, image build, or successful image publication. The existing `CI` workflow may still run its own checks on a main push; this workflow does not wait for them or change their gates.

The sync job only updates Docker Hub metadata. It does not build images, create image tags, publish a GitHub Release, deploy services, or change repository visibility. Each image has its own matrix entry with `fail-fast: false`, so a failed server update does not hide the web result. A fresh manual run on main applies the current main documents. GitHub's **Re-run jobs** uses the original run's commit and can overwrite newer descriptions with older text; prefer a fresh manual main run when retrying after later changes.

## Credentials and permissions

The sync job uses the existing repository Actions Secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`. It fails with their names if either is missing and does not print values. The username must have permission to update both `grayrepo` repositories. For repositories owned by an organization, the action requires that user to have repository Admin permissions.

The pinned [peter-evans/dockerhub-description v5 action](https://github.com/peter-evans/dockerhub-description/tree/1b9a80c056b620d92cedb9d9b5a223409c68ddfa) documents **Read, Write, Delete** permissions for a Docker Hub personal access token used to update descriptions. A token that successfully pushes images is not proof that it can update repository descriptions. Secret existence also does not verify its owner, expiration, or scope. Review the upstream action's current authentication guidance before changing credential type.

Do not broaden or replace credentials automatically. If the existing token lacks permission, a repository administrator must explicitly authorize and configure an appropriate credential through Docker Hub and GitHub's secret settings. Never place a token in the repository, an Issue, a workflow input, or a log. The workflow itself neither creates credentials nor changes their permissions.

## Verification

Local checks that do not contact Docker Hub:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
actionlint .github/workflows/ci.yml .github/workflows/dockerhub-overview.yml
docker compose --env-file /dev/null -f docker-compose.yml config --quiet
```

Credential preflight tests use synthetic values and execute the workflow's shell check. They prove missing credentials fail without echoing values; they do not authenticate to Docker Hub. Compose validation parses the installation file without starting containers.

After the change is merged and the workflow runs, verify both matrix jobs succeeded, then inspect the actual **Overview** and short description on:

- <https://hub.docker.com/r/grayrepo/agenvas-server>
- <https://hub.docker.com/r/grayrepo/agenvas-web>

A green local check, successful image push, or successful update of only one repository is not proof that both Overviews synchronized. For authentication or authorization failures, check the account, token expiration, repository access, and description-update scope. Retry only after correcting the cause; no image release is needed.
