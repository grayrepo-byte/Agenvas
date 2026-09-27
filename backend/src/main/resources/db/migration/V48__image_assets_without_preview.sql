-- Images no longer get a derived preview file: the canvas loads the archived original, and its
-- node long edge is bounded by the canvas layout command rather than by a downscaled copy.
-- thumbnail_* now only carries the extracted video cover frame, so the V21 comments are stale.
COMMENT ON COLUMN asset.thumbnail_key IS
    'Private, bounded PNG video cover frame extracted before a VIDEO Asset becomes READY; NULL for IMAGE.';
COMMENT ON COLUMN asset.thumbnail_byte_size IS
    'Video cover frame byte size; NULL for IMAGE assets.';
COMMENT ON COLUMN asset.thumbnail_sha256 IS
    'Video cover frame SHA-256 digest; NULL for IMAGE assets.';
