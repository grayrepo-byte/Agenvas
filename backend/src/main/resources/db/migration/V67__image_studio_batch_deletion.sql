ALTER TABLE public.image_studio_batch
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN delete_files boolean,
    ADD CONSTRAINT ck_image_studio_batch_deletion CHECK ((deleted_at IS NULL) = (delete_files IS NULL));

COMMENT ON COLUMN public.image_studio_batch.deleted_at IS 'Removal time from Studio history; immutable command and task membership are retained';
COMMENT ON COLUMN public.image_studio_batch.delete_files IS 'Explicit original and thumbnail purge choice; repeated deletion must match';
COMMENT ON CONSTRAINT ck_image_studio_batch_deletion ON public.image_studio_batch IS 'A deleted batch retains its original deletion choice for safe replay';

ALTER TABLE public.asset
    ADD COLUMN studio_purge_after timestamptz,
    ADD COLUMN studio_purged_at timestamptz,
    ADD CONSTRAINT ck_asset_studio_purge CHECK (
        (studio_purge_after IS NULL OR media_kind = 'IMAGE')
        AND (studio_purged_at IS NULL OR studio_purge_after IS NOT NULL)
    );

COMMENT ON COLUMN public.asset.studio_purge_after IS 'Studio result file removal intent and earliest cleanup attempt; non-null denies serving the file';
COMMENT ON COLUMN public.asset.studio_purged_at IS 'Confirmed removal of the original and thumbnail; asset metadata remains for audit';
COMMENT ON CONSTRAINT ck_asset_studio_purge ON public.asset IS 'Studio file cleanup is recorded only for image assets with a durable purge intent';

CREATE INDEX ix_asset_studio_purge_due ON public.asset(studio_purge_after, id)
    WHERE studio_purge_after IS NOT NULL AND studio_purged_at IS NULL;
COMMENT ON INDEX public.ix_asset_studio_purge_due IS 'Bounded scans of due Studio file cleanup, excluding completed intents';
