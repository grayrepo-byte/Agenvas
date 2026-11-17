package dev.agenvas.referencecatalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReferenceCatalogCacheJob(UUID id, UUID ownerId, String sourceId, UUID snapshotId, String commandKey,
        String payloadHash,
        State state, List<Item> items, UUID leaseToken, Instant leaseUntil, Instant createdAt, Instant updatedAt) {
    public enum State {
        QUEUED, RUNNING, SUCCEEDED, PARTIAL_FAILED, CANCELLED
    }
    public enum ItemState {
        QUEUED, READY, FAILED
    }
    public record Item(String entryId, String variantId, String sha256, long byteSize, ItemState state, String error) {
        public Item ready() {
            return new Item(entryId, variantId, sha256, byteSize, ItemState.READY, null);
        }
        public Item failed(String code) {
            return new Item(entryId, variantId, sha256, byteSize, ItemState.FAILED, code);
        }
        public Item retry() {
            return state == ItemState.FAILED
                    ? new Item(entryId, variantId, sha256, byteSize, ItemState.QUEUED, null)
                    : this;
        }
    }
    public record Progress(int schemaVersion, List<Item> items) {
    }
}
