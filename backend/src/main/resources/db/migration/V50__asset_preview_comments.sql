-- V48 rewrote these comments for an archive that no longer kept image previews. Images keep a
-- bounded PNG preview again: the canvas card displays the archived original, and the preview is
-- retained for later list-style surfaces. Restore comments that describe both media kinds.
COMMENT ON COLUMN asset.thumbnail_key IS
    'Private, bounded PNG preview created before a new IMAGE Asset becomes READY; for VIDEO it is the extracted cover frame.';
COMMENT ON COLUMN asset.thumbnail_byte_size IS
    'Preview or cover frame byte size; NULL only for media archived before V21.';
COMMENT ON COLUMN asset.thumbnail_sha256 IS
    'Preview or cover frame SHA-256 digest.';
