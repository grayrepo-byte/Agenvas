# Synthetic reference pack

This is original geometric test data, not a production pose or fashion dataset.
It includes two pose groups (one has five views, one has a single view) and one
coat reference. The small drawings demonstrate grouping, view selection, file
validation and project import without third-party media.

Regenerate with `python3 examples/reference-pack/generate.py` from the repository
root. The generator uses only Python's standard library and embeds no metadata.
Each manifest variant includes its exact PNG hash, byte count and MIME type.

To test remote synchronization, publish the contents of this directory at the root of a repository you own,
select the Agenvas reference-pack source format, use the corresponding manifest
path and explicitly review the asset permission. Nothing is published by Agenvas
or by the generator. Use a fixed commit when checking reproducible behavior.

See [the reference-source guide](../../docs/reference-packs.md) for the supported
formats and the separate metadata/image rights boundaries.
