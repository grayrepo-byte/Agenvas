# Reference catalog sources

The reference catalog stores searchable metadata separately from image files.
Administrators choose **Omilic (recommended)** or **GitHub** and select **Load**.
Omilic reads `https://omilic.com/data/catalog.json`. GitHub reads the configured
Open Pose Costume JSON from raw content. Initial loading adds the source and
fetches its complete JSON once. Later loads open the saved directory without
contacting either upstream. Choose **Refresh catalog** to fetch new JSON. Identical
JSON and normalized entries retain the current snapshot and image indexes. A
failed refresh preserves the saved directory; sources stay separate when switched.
No source is enabled or contacted automatically on installation.

## Source configuration and local cache

Custom source formats, repository parameters and image-distribution approval remain
available through the administrator API. Technical fields and cache-maintenance
controls are absent from the source chooser; cache usage, prefetch and shared
cleanup remain in System settings → System templates.

`sourceType=OMILIC` selects website delivery; `GITHUB` selects the repository.
Omitting it on creation keeps GitHub behavior for existing API callers. Website and
mutable-ref Open Pose Costume snapshots store an exact JSON SHA-256 and no claimed
Git commit. They do not resolve a commit through the GitHub API or walk Git trees
before downloading each image. Authorized reference packs retain their existing
fixed-commit and declared-file-hash verification.

Search, pagination and filters use the saved JSON directory. Metadata loading
never downloads all image files. Source settings use CAS; failed requests preserve
the last successful snapshot and do not expose upstream response bodies.

Source changes use an expected version. Source administration requires the ADMIN
role; browsing requires a logged-in account. Project import additionally checks the
project owner and active state. Recovery mode permits reads and blocks writes.

## Open Pose & Costume format

The default Omilic preset uses `sourceType: OMILIC` and `manifestPath: data/catalog.json`.
For the GitHub alternative, configure:

```text
sourceType: GITHUB
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

Omilic resolves relative image paths against its website root. Absolute original
URLs remain the upstream gallery URLs declared in JSON. Custom files use
relative `custom/...` paths under `public/`. The repository's `pose/covers`,
`pose/originals`, `costume/covers` and `costume/originals` archives are a different
layout. The private-cache operation below explicitly maps eligible base pose and
photographic costume variants to their fixed-commit original PNG archive paths;
custom images use their declared paths rather than guessing paths.

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
operations. Eligible base pose and photographic costume cards automatically cache
their first view privately as they approach the viewport. Opening or selecting a
view also loads it automatically for preview and explicit project import.
This is a limited implementation of individual reference use, not a claim that
every file or whole-library download is permitted. The selected catalog’s custom
PNG references support individual private caching. Unsupported base anime and
depth entries retain specific unavailability explanations. Website loading and
private reference use do not grant redistribution rights.

Metadata synchronization does not download images. Browsing loads only card
previews near the viewport and selected views, reusing existing private copies.
Cards and previews share requests with at most four concurrent downloads. Failures
require explicit retry with the same command key. Retained uncached records,
including copies cleared by the user, require explicit recaching. Queued downloads
are canceled when their cards and previews unmount. See [ADR 0080](adr/0080-automatic-reference-image-loading.md).
This format has no whole-library prefetch, automatic image hotlinks, default bundled
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
use the fixed commit, GitHub source paths and bounded requests.
Files must match their declared hash, size and content type, and pass the existing
media decoder, image-size and pixel limits. Selected private archive images use
the fixed Git tree's blob identity and size before recording the verified PNG's
observed SHA-256. Website and mutable-ref JSON sources instead freeze the first
validated image’s observed SHA-256, content identity, size and dimensions. The
selected URL comes from its immutable JSON snapshot; later changed bytes cannot
overwrite an accepted image. A provenance hash is never substituted for a file checksum.
Catalog metadata cannot execute code, set model credentials or run generation.
Image transport remains credential-free and subject to response and decoding limits.

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
for explicit proxy and DNS configuration; address allowlists have been removed.

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
