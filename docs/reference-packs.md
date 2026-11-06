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
layout; this adapter does not silently substitute those archives for the recorded
source URLs.

### Image rights are separate

The upstream [code license](https://github.com/hongye007/open-pose-costume/blob/main/LICENSE)
excludes source catalog data, third-party translations and image material from
its MIT grant. Its [source notice](https://github.com/hongye007/open-pose-costume/blob/main/SOURCES.md)
records the maintainer's confirmation of additional archive permission, explicitly
without independently verifying it or granting downstream redistribution rights.
The [custom-image terms](https://github.com/hongye007/open-pose-costume/blob/main/public/custom/LICENSE.txt)
also impose separate noncommercial and distribution restrictions.

Consequently, this adapter is metadata-only: no automatic image hotlinks, preview
proxy, thumbnail download, image archive or project import. Entries offer an
external source link. An administrator checking a generic image-permission option
does not override this format's restriction. This preserves a useful local source
cache and search without treating the existence of a public archive as a license.

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
media decoder, image-size and pixel limits. Catalog metadata cannot authorize
arbitrary network endpoints, execute code, set model credentials or run generation.

## Applying a single reference

For an authorized pack, select one real variant, then explicitly use it as a
reference or place it on the canvas. Application creates an independent image
version in the target project. It preserves other prompt content and media inputs,
checks the target capability and its image capacity, and does not invoke a model.
An unsupported video mode does not silently receive a first frame or switch modes.

Imports freeze the source snapshot, chosen variant and checksum. The command key
is reused after a lost response; reusing it with different input is a conflict.
Source synchronization or disabling does not rewrite an imported version or a
previously accepted task. Clearing directory caches must not delete project media.

Local metadata remains searchable when GitHub is unavailable. Authorized images
already imported into a project follow that project's configured media storage;
unfetched remote images still need connectivity. This is not a promise that a
browser, database and remote storage deployment can all operate without a network.
