# ADR 0080: Simplify source loading and load reference images automatically

Accepted on 2026-11-07 at the user's request. Library pose and costume cards load
their first private image as they approach the viewport when the source permits
private caching; opening a view also acquires it automatically. This replaces
ADR 0076's requirement for a separate explicit cache action when browsing.
Metadata synchronization continues to fetch metadata only, and browsing does not
start whole-library prefetch.

The user also requested replacing the source-management form with selecting a
source and loading it. The dialog supplies the Open Pose Costume preset, reuses
matching existing sources, adds or enables the selected source when needed, and
synchronizes it in one action. Success closes the dialog and selects the loaded
source in the catalog. Failures preserve the choice and offer retry, refreshing
source versions first to recover a lost add acknowledgement. Existing custom
sources remain selectable; technical configuration and distribution approval
remain available through the existing administrator API. Cache maintenance moves
to system settings. Choosing Load does not grant image-distribution permission.

Reuse the existing authenticated private-image acquisition and persistent file
cache. Cards and previews share requests by account, source, immutable snapshot,
entry and view, with at most four concurrent downloads. Existing cached copies
are reused. Failed acquisitions require explicit retry with the same command key;
retained uncached records, including cleared copies, require explicit recaching.
Leaving the catalog cancels queued work before download starts. Existing source
eligibility, integrity checks, account isolation, recovery guards and explicit
project import remain in force. No API or database migration is needed.
