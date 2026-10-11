-- A Seedream decomposition contains the base image plus up to sixteen layers.
alter table task drop constraint ck_task_provider_result_manifest;
alter table task add constraint ck_task_provider_result_manifest check (
    provider_result_manifest is null or coalesce(
        jsonb_typeof(provider_result_manifest) = 'object'
        and provider_result_manifest ->> 'schemaVersion' = '1'
        and jsonb_typeof(provider_result_manifest -> 'results') = 'array'
        and jsonb_array_length(provider_result_manifest -> 'results') between 1 and
            case when input_json ->> 'providerProtocol' = 'NATIVE_MEDIA' then 17 else 16 end,
        false)
);
comment on constraint ck_task_provider_result_manifest on task is
    'Private schema v1 result checkpoint: 1–17 results for native media, 1–16 for other protocols';
