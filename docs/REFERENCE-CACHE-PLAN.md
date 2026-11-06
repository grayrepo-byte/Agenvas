# Reference image cache and safe proxy follow-up

Base: local reference catalog commit `b5486b8`. The user explicitly requires image
files to be cached and reused, including offline use, progress, retries, disk usage
and cleanup that preserves project copies. They also confirmed Clash and retained
SSRF protection; no system proxy, DNS or VPN setting may be changed.

## Decisions

- Metadata cache, rebuildable image cache, account-private selected references and
  immutable project media are separate lifetimes.
- Explicit authorized reference packs support on-demand file cache and administrator
  prefetch jobs. Jobs fix the snapshot and record actual progress/failures; retries
  do not repeat successful downloads.
- Original gallery terms allow individual reference PNG downloads but restrict bulk
  download. Local private cache is not automatically public redistribution. Add
  owner-scoped single-image caching for verified base pose/photo-costume archive
  paths, without enabling bulk caching, default bundles or public mirrors.
- The code-only MIT license and maintainer archive statement do not establish a
  downstream whole-library offline cache grant. Anime/depth/custom have different
  source/path/usage constraints and need explicit operation-specific messages.
- Shared cache cleanup protects active private indexes and project copies. A user
  may remove their own private cache; logical ownership and physical deduplication
  are distinguished in usage statistics.
- Default networking remains direct and strict. Proxy/secure-DNS compatibility is
  an explicit server deployment choice. Every origin address remains public-only,
  and the selected numeric address must be the actual proxy tunnel destination.
  TLS/SNI/Host verification stays bound to the original hostname.
- No remote repository creation, push, deployment or production database use.

## Work and checks

- [x] Audio/non-image surfaces hide the unusable catalog source, with focused red/green tests.
- [x] Persistent content-addressed cache, integrity validation, quota, restart/offline reuse and concurrency checks.
- [x] V53 durable prefetch lifecycle, progress, retry/cancel/fencing and protected cleanup.
- [x] V54 owner-private selected-image cache, immutable identity, cross-account access checks, offline import and owner cleanup.
- [x] Four-language cache/private controls, generated API contracts and browser regression.
- [x] Explicit safe public-download transport: direct/system DNS, opt-in trusted DoH,
      supported HTTP/HTTPS/SOCKS proxy routes and actionable safe error categories.
- [x] Native/container/Fake-IP compatibility evidence, no blanket network exemptions.
- [x] Actual fixed GitHub metadata and exactly one user-selected reference image in
      isolated opt-in live validation; no upstream image fixture committed.
- [x] Independent review, focused verification, credential/media checks and local commit.

## Evidence collected so far

- Native and default Docker resolve GitHub hosts to benchmark/Fake-IP address blocks,
  rejected before HTTP by the unchanged `allowsPublicDownload` check.
- System/env/JVM explicit proxy settings were off; Clash/Mihomo processes run. A
  readable generated configuration reports TUN and Fake-IP; direct active service
  configuration is OS-protected and was not force-read.
- The final product client fetched the exact pinned GitHub JSON (5,420,749 bytes,
  expected digest) through native HTTP/SOCKS5/direct+DoH and Docker host-alias
  SOCKS5/direct+DoH. The default Fake-IP route still rejects before HTTP.
- The opt-in real Spring/MockMvc + PostgreSQL integration synchronized 3,144 records
  and cached exactly one selected original PNG (273,032 bytes). After closing its
  own proxy bridge, preview, replay and project import succeeded with zero new
  connections and zero provider tasks. Shared clear protected it; owner clear
  preserved the project's independently archived identical bytes. The image and
  database were temporary and are not committed.
- Focused backend cache/catalog tests: 50 unit checks, 19 PostgreSQL checks, and
  1 separate actual selected-image integration passed. Transport/source-client
  regression: 32 checks passed. Independent review fixes cover private clear/acquire
  serialization, rooted DoH CNAME validation and preservation of checksum-valid
  cached bytes when a caller supplies conflicting file metadata.
- Frontend follow-up: 94 related tests and 5 editor-specific checks passed; the final
  catalog/network-message run passed 48 tests. Earlier editor/workflow regression
  passed 192 checks. Final typecheck, complete ESLint, four-language (3,878 keys)
  and theme checks passed. Four browser viewport sizes covered source settings,
  exact-view application and owner cleanup of an older snapshot using synthetic
  fixtures and production CSS. The Vite build retains its existing chunk-size warning.

## Remaining scope

- No full suite, real model generation, cloud-media crash recovery or combined
  character-library regression was run. Merge must reconcile shared asset/recovery
  services, editor/library entries, locales, contracts and generated code; V50/V51
  and ADR 0074 remain owned by the parallel character-library work.
- A generic remote authorized pack was not published for live prefetch verification.
  Its pipeline uses the original synthetic pack, actual PostgreSQL and filesystem;
  the live source proof covers exactly one selected private image.
- No automatic OS/PAC/environment proxy discovery or authenticated proxy support.
  Real HTTPS proxies, IPv6-only routes and Linux host-proxy topology remain
  unverified. Normal public DNS uses defaults; Fake-IP requires explicit settings.
- The selected upstream source is not approved for whole-library prefetch, default
  bundling or public redistribution; individual private reference use is separate.
