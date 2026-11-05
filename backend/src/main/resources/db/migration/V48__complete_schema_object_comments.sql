-- Complete catalog documentation without changing already-applied migrations.
COMMENT ON CONSTRAINT call_log_operation_check ON public.call_log IS 'Allowed model and media call operations, including administrator diagnostics';
COMMENT ON COLUMN public.third_party_prompt_sync_settings.id IS 'Singleton identity for administrator prompt synchronization settings';
COMMENT ON COLUMN public.third_party_prompt_sync_settings.enabled IS 'Explicit administrator consent to scheduled third-party prompt downloads';
COMMENT ON COLUMN public.third_party_prompt_sync_settings.version IS 'Optimistic locking version for administrator consent updates';
COMMENT ON INDEX public.third_party_prompt_sync_settings_pkey IS 'Prompt synchronization settings singleton primary key lookup';
COMMENT ON CONSTRAINT third_party_prompt_sync_settings_pkey ON public.third_party_prompt_sync_settings IS 'Unique singleton identity for prompt synchronization settings';
COMMENT ON CONSTRAINT third_party_prompt_sync_settings_id_check ON public.third_party_prompt_sync_settings IS 'Only the singleton identity is valid';
COMMENT ON CONSTRAINT third_party_prompt_sync_settings_version_check ON public.third_party_prompt_sync_settings IS 'Consent settings versions cannot be negative';
