# Reference catalog sources

The reference catalog stores searchable metadata separately from image files.
Administrators configure a GitHub repository and manually synchronize it, following
the same source-management pattern as the third-party prompt catalog. No source is
enabled or contacted automatically on installation.

## Source configuration and local cache

Choose the source format, repository owner/name, a branch, tag or exact commit,
and a JSON path. Synchronization first resolves a mutable ref to a Git commit,
downloads that commit's JSON, validates it and publishes a new immutable local
snapshot. The previous successful snapshot remains available when the upstream
request or validation fails. A commit hash identifies content; it does not prove
image permission.

Search, pagination, group/view selection and filters use the locally stored
metadata. Refreshing the catalog reads that cache; the separate manual sync action
contacts GitHub. Upstream images are not fetched by metadata synchronization.
An exact 40-character commit can be used when GitHub's ref-resolution API is
temporarily unavailable or rate limited. Error messages retain the previous cache
and do not expose upstream response bodies.

Source changes use an expected version. Source administration requires the ADMIN
role; browsing requires a logged-in account. Project import additionally checks the
project owner and active state. Recovery mode permits reads and blocks writes.

## Open Pose & Costume format

For the user-selected repository, configure:

```text
format: OPEN_POSE_COSTUME
owner: hongye007
repository: open-pose-costume
ref: main (or a reviewed fixed commit)
manifestPath: public/data/catalog.json
```

This adapter consumes the existing catalog; it does not execute the repository's
build scripts or scrape its upstream galleries. The structure is documented in
the upstream [types](https://github.com/hongye007/open-pose-costume/blob/main/src/types.ts)
and [catalog builder](https://github.com/hongye007/open-pose-costume/blob/main/scripts/build-data.mjs).

| Field | Meaning |
| --- | --- |
| `items`, `stats` | Catalog records and upstream informational totals |
| `id`, `kind`, `code` | Stable record identity, `pose`/`costume`/`character`, displayed code |
| `title`, `keywords`, `series` | Chinese/English text objects |
| `category` | Stable `id` plus Chinese/English labels |
| `tags` | Stable tag IDs plus Chinese/English labels |
| `collection`, `extended` | Custom collection marker and extended-category flag |
| `source` | Original source URL, or the custom-source marker |
| `variants` | Actual available views; IDs, angle and localized label |
| Variant `thumbnail`, `image`, `original` | Remote URLs or custom relative paths |
| Optional `depth`, `provenance` | Upstream depth references and production provenance |

Agenvas indexes pose and costume records. Upstream character records do not become
accounts, project characters or character-library profiles. Missing angles remain
missing. Provenance hashes can describe an input used to produce an image; they
are not assumed to be checksums of the downloadable output image.

Search covers IDs/codes, bilingual titles/keywords, category/series and tags.
Filters preserve upstream category, subject tag, visual style, base/custom
collection, extended classification and available angle. The UI itself supports
four languages; source descriptions retain their original Chinese/English data.

Original metadata points mostly to the upstream galleries. Custom files use
relative `custom/...` paths under `public/`. The repository's `pose/covers`,
`pose/originals`, `costume/covers` and `costume/originals` archives are a different
layout. The private-cache operation below explicitly maps eligible base pose and
photographic costume variants to their fixed-commit original PNG archive paths;
it does not rewrite all source URLs or guess anime/depth/custom paths.

### Image rights are separate

The upstream [code license](https://github.com/hongye007/open-pose-costume/blob/main/LICENSE)
excludes source catalog data, third-party translations and image material from
its MIT grant. Its [source notice](https://github.com/hongye007/open-pose-costume/blob/main/SOURCES.md)
records the maintainer's confirmation of additional archive permission, explicitly
without independently verifying it or granting downstream redistribution rights.
The [custom-image terms](https://github.com/hongye007/open-pose-costume/blob/main/public/custom/LICENSE.txt)
also impose separate noncommercial and distribution restrictions.

The original [pose](https://ai-pose-gallery.pages.dev/) and
[costume](https://ai-costume-gallery.pages.dev/) galleries allow downloading
individual original PNGs as creative references, including commercial work, while
separately restricting bulk download and redistribution. These are different
operations. A user can explicitly cache one eligible base pose or photographic
costume view privately, preview it locally and import an independent project copy.
This is a limited implementation of individual reference use, not a claim that
every file or whole-library download is permitted. Anime, depth and custom entries
retain their external source links and specific unavailability explanations.

Metadata synchronization and opening the catalog do not download images. This
format has no whole-library prefetch, automatic image hotlinks, default bundled
images or public mirror. The generic distribution-approval checkbox cannot enable
those operations for this format. An account's private cache index and preview
require that account's authorization; another account cannot read its private
image IDs. The physical checksum-addressed bytes may be deduplicated on the server.

Do not bundle the upstream JSON or translations into Agenvas releases or tests.
Test fixtures use invented records with the same shape. The configured download
is a local runtime cache, not an assertion that the catalog is open-content data.

## Authorized Agenvas reference packs

The `AGENVAS_REFERENCE_PACK` format supports images for which the administrator has
reviewed sufficient permission to provide previews and project copies. The pack
declares its asset license separately, and each image has an exact SHA-256,
byte count and content type. No permission is inferred from a code-only license.

The minimal structure is:

```json
{
  "schemaVersion": 1,
  "license": "CC0-1.0",
  "entries": [{
    "id": "synthetic-standing",
    "kind": "POSE",
    "title": "Synthetic standing study",
    "category": "standing",
    "tags": ["test"],
    "facets": {},
    "variants": [{
      "id": "synthetic-standing-front",
      "label": "Front",
      "view": "front",
      "path": "images/standing-front.png",
      "sha256": "REPLACE_WITH_THE_FILE_SHA256",
      "byteSize": 1234,
      "contentType": "image/png"
    }]
  }]
}
```

The example is structural; the placeholder hash and byte count are deliberately
not valid import data. The [synthetic pack](../examples/reference-pack/README.md)
contains checksum-complete original geometric drawings and a reproducible generator.
It is not installed as a default external source and publishing it remains an
explicit operator action.

Only repository-relative safe paths are accepted for these image files. Downloads
use the fixed commit, checked public GitHub destinations and bounded requests.
Files must match their declared hash, size and content type, and pass the existing
media decoder, image-size and pixel limits. Selected private archive images use
the fixed Git tree's blob identity and size before recording the verified PNG's
observed SHA-256. A provenance hash is never substituted for a file checksum.
Catalog metadata cannot authorize
arbitrary network endpoints, execute code, set model credentials or run generation.

## Image file cache and cleanup

Authorized pack previews and imports reuse a persistent content-addressed file
cache. Administrators can start a prefetch job for a fixed pack snapshot, inspect
actual file/byte progress, cancel, and retry only failed files. Command keys preserve
the accepted job after a lost response. Jobs and failure records survive process
restart; an interrupted worker lease is reclaimed with fencing. Successful files
are not downloaded again. Changing a source cannot redirect an existing job.

The default physical quota is 512 MiB. Configure `agenvas.reference-catalog.cache.root`
to choose a cache directory (otherwise it lives under the configured local storage
root), and `agenvas.reference-catalog.cache.quota-bytes` to set the quota. This cache
requires a persistent writable filesystem even when project media use object storage.
Quota exhaustion is reported explicitly; it does not silently evict protected files.
Writes are atomic and guarded across concurrent processes sharing this filesystem.

The administrator cache panel shows actual physical bytes/files, available quota,
and files protected by active private indexes. Shared cleanup preserves those
files. Every account also has a private-cache manager listing all its cached views,
including older snapshots, and can explicitly remove its own records. Owner usage
is logical usage, so it need not equal the deduplicated physical server total.
Clearing either cache does not delete already imported project media. A later
explicit recache remains possible when the source and its usage policy permit it.

Cached bytes are revalidated before use. Recovery mode can read verified cache
hits but cannot initialize directories, download misses, acquire, clear or import.
Source authorization is checked again for each access; caching never creates a
permission bypass. See [public-download networking](public-download-networking.md)
for supported safe proxy and DNS configuration.

## Applying a single reference

For an authorized pack or an eligible privately cached view, select one real
variant, then explicitly use it as a
reference or place it on the canvas. Application creates an independent image
version in the target project. It preserves other prompt content and media inputs,
checks the target capability and its image capacity, and does not invoke a model.
An unsupported video mode does not silently receive a first frame or switch modes.

Imports freeze the source snapshot, chosen variant and checksum. The command key
is reused after a lost response; reusing it with different input is a conflict.
Source synchronization or disabling does not rewrite an imported version or a
previously accepted task. Clearing directory caches must not delete project media.

Local metadata remains searchable when GitHub is unavailable. Verified cached
images can be previewed and imported without reaching GitHub again; unfetched or
removed images need connectivity. Images already imported into a project follow
that project's configured media storage. This is not a promise that a
browser, database and remote storage deployment can all operate without a network.
