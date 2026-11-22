ALTER TABLE public.video_studio_generation
    DROP CONSTRAINT ck_video_studio_generation_request,
    ADD CONSTRAINT ck_video_studio_generation_request CHECK (
        COALESCE(jsonb_typeof(request_json) = 'object' AND (
            request_json->'schemaVersion' = '1'::jsonb OR (
                request_json->'schemaVersion' = '2'::jsonb
                AND jsonb_typeof(request_json->'references') = 'array'
                AND jsonb_typeof(request_json->'dynamicValues') = 'object'
            )
        ), false)
    );

COMMENT ON CONSTRAINT ck_video_studio_generation_request ON public.video_studio_generation
    IS 'Supports original frame/text requests and v2 frozen ordered references and creator parameters';
COMMENT ON COLUMN public.video_studio_generation.request_json
    IS 'Versioned frozen capability, prompt, creator parameters and exact media references';
