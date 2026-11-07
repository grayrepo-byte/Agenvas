# ADR 0076: Persistent reference images and safe proxy compatibility

> 地址拦截、地址白名单及回环准入规则已由 [ADR 0079](0079-remove-outbound-address-checks.md) 替代；以下保留历史决策背景。

> 浏览时必须手动缓存单图的规则已由 [ADR 0080](0080-automatic-reference-image-loading.md) 替代，改为可视卡片及选中视角自动按需加载。

Status: accepted by the user's explicit image-cache requirement and decision to
retain SSRF protection. Amends ADR 0075's metadata-only treatment of all selected
source images.

Metadata-only search does not satisfy repeated image use. Separate immutable
metadata snapshots, rebuildable image files, owner-private selected-image indexes,
and project-owned media. Store files by verified SHA-256 on a persistent filesystem
with atomic writes, cross-process locks, bounded decoding and quota. Shared packs
retain declared file hashes; selected original archive PNGs verify the fixed Git
blob and size before recording the observed SHA-256. A caller metadata conflict
cannot evict already valid bytes. Imports create independent project copies.

Authorized packs support durable administrator prefetch jobs, actual file/byte
progress, failed-file retry, cancellation and worker fencing. Private single-image
operations remain explicit and owner-authorized, with replay keys and immutable
accepted identities. Clear/acquire operations serialize by accepted identity;
shared cleanup protects active private indexes and all cleanup preserves project
copies. Every account can clear its old snapshot caches. Recovery reads cache hits
without network or filesystem initialization and prohibits writes.

The original galleries distinguish individual creative-reference downloads from
bulk download and image redistribution. Implement private selected base pose/photo
costume reference use for the specified archive; do not infer a whole-library grant
from code MIT, public archive availability or a maintainer's additional permission.
Anime/depth/custom policy and archive coverage remain distinct. Nothing is bundled
or automatically downloaded by installation, metadata sync or browsing.

The existing public address guard correctly rejects Clash Fake-IP answers. Preserve
that rule and introduce an explicit deployment-only public-download transport.
Default direct/system DNS is unchanged. Optional TLS-verified DNS-JSON DoH uses
public literal bootstrap addresses and validates rooted bounded CNAME chains and
both address families. Direct, HTTP/HTTPS CONNECT and SOCKS5 route the exact
validated numeric origin while retaining the original hostname for TLS/SNI/Host.
Proxy credentials, OS/PAC/environment auto-discovery, fallback DNS, redirects and
implicit retry are unsupported. The configured proxy is an operator trust boundary;
it does not allow source metadata to choose a proxy or arbitrary destination.
Provider submission transport and global endpoint rules are unchanged.

These choices require local persistent cache storage and deployment configuration
for networks with Fake-IP; they avoid granting a network exception to a proxy
brand or special-use address block. Stable sanitized errors distinguish DNS,
proxy, TLS and rate limiting. See [source/cache operations](../reference-packs.md)
and [deployment networking](../public-download-networking.md).

V53 adds cache jobs; V54 adds private images and accepted commands. Both are
additive to V52. V50/V51 remain reserved for the parallel account character library;
integration must regenerate shared contracts and jOOQ from combined migrations.
No deployment, system proxy change, public image mirror or remote push is included.
