# Third-party prompt sources

Agenvas stores a catalogue of public image and video prompts separately from your projects and personal templates. The built-in source definitions are local database records. They do not require a network request to create.

## Enable synchronization in settings

Scheduled prompt downloads are **off by default**, including for existing installations upgrading to this setting. After signing in as administrator, open **System settings → System templates → Third-party templates → Manage sources**, turn on **Automatically sync third-party prompts**, and save. The same global setting controls image and video sources. Saving the setting records consent; it does not fetch a catalogue within that request.

The scheduler then checks enabled sources once a minute, refreshes each successful source after 24 hours, and retries a failed source after one hour. A source's existing enabled flag remains a separate control. Turning off automatic synchronization prevents any later scheduled source claim; a download accepted before the switch was saved may finish. The acceptance transaction takes a shared lock on the consent row and releases it before network access, while disabling uses the same row's update lock. There is no process-level cache of consent.

Cached prompts, imported templates and source definitions are retained when automatic synchronization is off. An authenticated administrator may still request a manual synchronization of an enabled source; the button explicitly initiates an external download. Disabling an individual source continues to fence its in-flight publication. Recovery mode prevents both manual synchronization and scheduled writes.

The deployment variable `AGENVAS_PROMPT_SYNC_ENABLED` is an independent scheduling limit, not administrator consent. Its existing default is `true`, which only permits the UI setting to take effect. `false` stops background scheduling even when the UI switch is enabled, and the UI explains that limit. For a Docker Compose installation, the operator can use this override alongside the main file:

```yaml
services:
  server:
    environment:
      AGENVAS_PROMPT_SYNC_ENABLED: "false"
```

Apply both files to the existing Compose project, for example:

```sh
docker compose -f docker-compose.yml -f compose.prompt-sync.yaml up -d --wait
```

For a standalone JVM, export `AGENVAS_PROMPT_SYNC_ENABLED=false` before launch. A variable in a host shell or `.env` is not automatically passed into a Compose container. Re-enabling this deployment limit never changes the saved administrator choice.

## What the server contacts

The ten built-in sources use public HTTPS GETs:

| Destination | Catalogue data |
| --- | --- |
| `raw.githubusercontent.com` | Seven sources: ZeroLu, ImgEdify, YouMind GPT Image 2, YouMind Nano Banana Pro, David GPT Image 2, YouMind Seedance 2.0 and BeatAPI MiniMax H3. YouMind Seedance also reads the repository's public `video-urls.json` manifest. |
| `imagepromptgallery.com` | Three public video catalogues: Seedance 2.0, Seedance 2.5 and MiniMax H3. This API may require multiple paginated GETs. |

Administrators may add a custom public HTTPS JSON source. Requests send an `Agenvas-Prompt-Sync/1` user agent and the requested public source path/query. These catalogue downloads do not upload your project content, saved model credentials or private prompts. The remote service can see the server's network address and request metadata. Source URLs containing credentials, private/local DNS destinations, redirects and oversized responses are rejected.

Browsing a cached prompt or preview may separately load its third-party image/video URL in the browser. Explicitly importing a template with references can download its public media files into project storage. These actions are separate from periodic catalogue synchronization.

## When a source fails

One failed source does not prevent startup, local editing or other sources from synchronizing. Its existing cache remains readable and its last successful synchronization time remains intact. New failures distinguish unsuccessful HTTP responses (`THIRD_PARTY_SOURCE_HTTP_ERROR`), failed downloads (`THIRD_PARTY_SOURCE_DOWNLOAD_FAILED`), responses over the byte limit (`THIRD_PARTY_SOURCE_TOO_LARGE`) and rejected source data or addresses (`THIRD_PARTY_SOURCE_INVALID_DATA`). The download category deliberately does not guess whether DNS, network access, TLS or a destination restriction caused the failure. Unexpected failures, historical records and unknown status codes keep the generic `THIRD_PARTY_SYNC_FAILED` presentation until another attempt provides a known category.

Source management shows these localized categories and explains that the one-hour retry requires administrator consent, an enabled source and deployment scheduling permission. An explicit manual synchronization remains available for an enabled source while automatic synchronization is off. The normal server log records only the source ID, safe category and exception class without upstream response bodies, URLs containing secrets or exception prose. Check the source status and network reachability before a manual retry; an empty catalogue does not prove that no prompts exist upstream. These categories describe future attempts; they do not establish the cause of the historical failures in the external report.

The external v0.0.8 report observed failures for all three Image Prompt Gallery sources while the seven GitHub sources succeeded. A bounded check on 2026-11-04 received HTTP 200 JSON from all three catalogue endpoints and identified a current adapter mismatch: the compact list publishes its prompt inside the public launch-link prefill, uses nested media/input fields, and provides `offset`, `limit` and `total` rather than an explicit next link. The adapter now reads that inline payload locally, verifies its source/case identity, preserves the catalogue model when the launch link offers a fallback model, and follows offset pagination on the configured endpoint. It never visits the launch-link destination, executes its contents or treats output previews as generation inputs.

A current single-item public response was parsed and validated; its 608-character prefill prompt exactly matched the public case page's prompt and declared prompt length. Synthetic regression tests cover compact records, input/preview separation, invalid prefills and pagination. Full live catalogue synchronization and media imports were not performed. The historical report lacks an HTTP status/response, so the current contract mismatch is independently confirmed without claiming it proves the historical failure had the same cause.

## Upgrade behavior

V46 adds one persistent global setting, initialized to disabled on both new and existing installations. Earlier implicit background synchronization does not count as an administrator choice. Administrators who want periodic refreshes must enable the setting once after upgrading. Existing source enabled flags, cached prompts, imported content and in-flight leases remain unchanged; there is no deletion or retroactive cancellation.

The setting uses version-based compare-and-set. A stale settings page cannot overwrite another administrator's newer choice; a conflict keeps the local draft and requires an explicit reload. The API is administrator-only, CSRF-protected for writes, and disabled for writes in recovery mode. Deploy the backend, migrations, generated API types and frontend together.
