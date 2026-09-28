ALTER TABLE media_capability DROP CONSTRAINT ck_media_capability_max_concurrent;
ALTER TABLE media_capability DROP COLUMN max_concurrent;

DROP TABLE provider_dispatch_gate;
