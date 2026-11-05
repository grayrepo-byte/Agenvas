# Container health and image publication

## Web health checks

The supplied Nginx configuration listens on IPv4 port `8080`. Every Compose entry point probes `http://127.0.0.1:8080/` inside the Web container so the probe uses the same address family. `localhost` can resolve to IPv6 `::1`; a client that chooses that address without falling back can report a healthy IPv4-only server as unavailable.

This checks that Nginx can serve the application. The separate server and PostgreSQL checks still govern their readiness; the Web probe does not replace them.

Run the configuration regression with:

```sh
python3 deploy/test-web-health.py
bash deploy/check-compose.sh
```

An optional runtime regression uses an existing local image, with no image pull, external network, host ports or persistent volumes. It removes its own temporary container afterward:

```sh
AGENVAS_TEST_WEB_IMAGE=agenvas-web:local python3 deploy/test-web-health.py
```

The local Docker verification confirmed successful IPv4 probes against real Nginx. The reported Windows/WSL `localhost` failure was not reproduced on that host: `localhost` also succeeded, while explicit `::1` was unavailable. The change removes address-family ambiguity; Windows/WSL acceptance remains a separate check.

## First administrator setup

The default deployment publishes Web on `0.0.0.0:8088`. Before administrator setup is complete, a client that can reach that installation can submit the first setup request. Complete setup on a controlled network before exposing the service publicly, as described in the README. The permanent installation marker and single-winner setup transaction prevent subsequent initialization; they do not identify which network client should become the first administrator.

## Published image size

Read-only inspection of the public Linux/amd64 manifests found the following layer payload totals. These count registry layer descriptors, excluding the small manifest/configuration objects; they are not unpacked disk usage.

| Image | 0.0.7 layer bytes | 0.0.8 layer bytes |
| --- | ---: | ---: |
| `grayrepo/agenvas-server` | 596,786,426 | 1,188,313,600 |
| `grayrepo/agenvas-web` | 30,314,720 | 70,470,656 |

All inspected `0.0.7` layers use `application/vnd.docker.image.rootfs.diff.tar.gzip`. All inspected `0.0.8` layers use `application/vnd.oci.image.layer.v1.tar`, which is uncompressed. The Dockerfiles and backend dependency declaration did not change between these source tags. The image configuration also confirms identical uncompressed hashes for the first five server base layers and first eight Web base layers across the two versions. The manifest evidence establishes a publication compression regression; it does not by itself prove byte-for-byte equality of every release layer or identify which runner/image-store change triggered it.

The gated publication job now copies the retained, validated Docker archive with explicit gzip compression and a Docker schema 2 manifest using [Skopeo copy](https://github.com/containers/skopeo/blob/main/docs/skopeo-copy.1.md). It does not rebuild the image. Before final multi-architecture tags can be assembled, a check requires every published layer to declare gzip compression and the image configuration digest to match the original scanned image. Recompression changes transport layer digests while the image configuration retains the root filesystem's uncompressed layer hashes.

Local verification covers the manifest validator, its command-line rejection path, existing CI gate tests and workflow syntax. The Skopeo registry copy and a complete hosted release have not been run locally; no existing release tag or image was republished. The next authorized release must verify the resulting per-architecture manifests and download sizes.
