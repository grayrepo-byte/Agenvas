# ADR 0081: Prefer the website catalog and load JSON directly

Accepted on 2026-11-07. The user identified Omilic's deployed catalog and a custom
pose image, and requested website-first loading with GitHub as an explicit option.
A complete catalog JSON already contains the directory; loading a saved directory
must not synchronize it again or walk GitHub trees for each new image.

Default to Omilic's `/data/catalog.json`; keep GitHub as a selectable source.
Open Pose Costume on GitHub reads its JSON from raw content at the configured ref,
without a commit-resolution API call for mutable refs. An existing local directory
opens immediately. Only initial loading and **Refresh catalog** fetch JSON. An
unchanged JSON and normalized entry set retain the existing snapshot and image
indexes. Sources have separate persisted identities; switching does not overwrite
earlier snapshots or project copies.

Reopening Library prefers an enabled saved Omilic directory. Resolve that default
before the first directory request, so an intermediate All-sources request cannot
start legacy GitHub acquisitions. Users can still choose All or GitHub explicitly;
installations without a saved website directory retain their previous sources.

Omilic resolves relative original-image paths against its site origin and keeps
absolute original URLs from the JSON. GitHub custom images use their declared
`public/custom/...` paths; legacy base references retain their known archive paths.
Custom PNG references from this selected catalog support private caching. Anime
and other unsupported base collections keep their existing restrictions. Website
and mutable-ref snapshots retain the JSON SHA-256 with no claimed Git commit.
Their first validated download freezes observed SHA-256, content identity, byte
size and dimensions. Reacquisition cannot overwrite that identity; changed bytes
are rejected. Existing pinned-commit selections keep their Git proof checks.
No upstream provenance hash is treated as an image checksum or a distribution grant.

V56 adds `source_type` with `GITHUB` as the default for existing sources, permits
null snapshot commits and widens private image locations for snapshot-derived
URLs. Creation accepts optional `sourceType`; source responses expose it. Deploy
the migration, Java implementation and generated clients together; older builds
cannot interpret website sources. Image decoding, byte/pixel limits, account
authorization, independent project imports and recovery guards remain in force.
