# Security policy / 安全政策

Agenvas is still an unreleased MVP development build. No version is currently designated as a security-supported release. Security fixes are developed against the current main branch; this is not a promise of backports, response time, or production support.

| Version | Security support |
| --- | --- |
| `0.1.0-SNAPSHOT` and earlier development builds | Best-effort fixes on main; no supported release |

## Reporting a vulnerability / 漏洞报告

Do not post exploit steps, credentials, private media, API keys, or other sensitive evidence in a public issue or discussion. If this repository's GitHub Security tab offers **Report a vulnerability**, use that private reporting flow. GitHub documents that this button appears only when the repository owner has enabled private vulnerability reporting: [GitHub private reporting documentation](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository).

At the time this policy was written, a private reporting channel for this repository could not be verified. If the button is unavailable, do not include sensitive details in a public ticket. Ask the maintainers for a private contact using only a non-sensitive description. A verified private channel or dedicated security address must be added before a public release; until then, this remains a release blocker.

Please include the affected commit/version, deployment mode, expected and observed behavior, reproduction prerequisites, and potential impact once a private channel is available. Do not send live secrets or user media; use redacted examples. Maintainers should acknowledge receipt privately, coordinate a fix and disclosure timing, and avoid exposing reporters' private evidence.

## Deployment warning / 部署提示

The default Docker Compose configuration binds to loopback and uses HTTP for local evaluation only. Do not expose it directly to the internet. A production deployment requires HTTPS, secure cookies, administrator-controlled setup, private Provider endpoints, backups of both PostgreSQL and media plus encryption keys, and completion of the open security/recovery gates in [the development checklist](docs/DEVELOPMENT-CHECKLIST.md). Mock media and fake-HTTP Provider tests do not establish real-provider safety.
