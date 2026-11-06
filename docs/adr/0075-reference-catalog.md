# ADR 0075: Configurable pose and costume reference catalog

Status: accepted for implementation by the user's explicit development request.

The user selected `hongye007/open-pose-costume` and asked for the existing
third-party prompt-source pattern: configure a source, pull it and cache it locally,
then search and choose poses or other filters.

Introduce a separate reference-catalog domain rather than extending the four
personal asset categories or creating pose/costume canvas subjects. Pose groups
have actual view variants; costume records preserve category and tags. Source
administration and manual synchronization are explicit, versioned administrator
operations. Metadata snapshots are local, immutable and tied to a Git commit and
digest; a failed synchronization retains the last successful snapshot.

The selected source format preserves its bilingual metadata and stable filter IDs.
Its code license does not grant image rights, and its additional archive permission
does not establish downstream redistribution. No upstream dataset or image is
bundled. The original implementation was metadata/link-only. The user's explicit
image-cache requirement and the distinction between individual private reference
use and bulk redistribution amend that decision in [ADR 0076](0076-reference-image-cache-and-networking.md).

A separate authorized reference-pack format supports self-owned or independently
licensed images with exact checksums and reviewed permission. Its imports create
independent project-owned media through the existing application services, with
idempotent commands and normal media validation. References preserve prompt and
existing inputs; use follows actual provider modes and slots. A pose/depth image
is not a universal pose-control capability, and a costume image is not a guaranteed
outfit-replacement protocol. Selection/import never invokes paid generation.

This costs a separate catalog, adapter and persistence slice, but keeps source
updates and authorization outside project media lifetimes. It also avoids coupling
this feature to the parallel account character-library implementation. Reserve
V50/V51 for that work and add V52 for this feature; merge and regenerate shared
contracts/jOOQ before deployment.

The source format and operational behavior are described in
[reference-packs.md](../reference-packs.md). No repository creation, upstream image
mirroring, push, deployment or production migration is authorized by this change.
