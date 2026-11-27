ALTER TABLE public.project
    DROP CONSTRAINT ck_project_purpose,
    ADD CONSTRAINT ck_project_purpose CHECK (purpose IN ('CANVAS', 'IMAGE_STUDIO', 'VIDEO_STUDIO')),
    ADD CONSTRAINT uq_project_owner_purpose_identity UNIQUE (owner_id, purpose, id);
COMMENT ON COLUMN public.project.purpose IS '权限与存储作用域用途：CANVAS 为用户项目，IMAGE_STUDIO 和 VIDEO_STUDIO 为独立工作台内部作用域';
COMMENT ON CONSTRAINT ck_project_purpose ON public.project IS '限定用户画布项目与独立生图、视频工作台作用域，不改变既有项目用途';
COMMENT ON CONSTRAINT uq_project_owner_purpose_identity ON public.project IS 'Composite project identity lets Studio commands verify ownership and scope type together';
COMMENT ON INDEX public.uq_project_owner_purpose_identity IS 'Supports the foreign key enforcing owner and purpose of a Studio command project';

CREATE UNIQUE INDEX uq_project_video_studio_owner ON public.project(owner_id) WHERE purpose = 'VIDEO_STUDIO';
COMMENT ON INDEX public.uq_project_video_studio_owner IS '每个所有者最多一个独立视频工作台作用域，串行化首次进入时的并发创建';

CREATE TABLE public.video_studio_generation (
    id uuid NOT NULL,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    -- A checked discriminator lets one foreign key enforce both ownership and scope type.
    project_purpose varchar(32) NOT NULL DEFAULT 'VIDEO_STUDIO',
    task_id uuid NOT NULL,
    command_key varchar(160) NOT NULL,
    request_hash char(64) NOT NULL,
    request_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT video_studio_generation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_video_studio_generation_owner_command UNIQUE (owner_id, command_key),
    CONSTRAINT uq_video_studio_generation_task UNIQUE (task_id),
    CONSTRAINT fk_video_studio_generation_project FOREIGN KEY (owner_id, project_purpose, project_id)
        REFERENCES public.project(owner_id, purpose, id),
    CONSTRAINT fk_video_studio_generation_task FOREIGN KEY (project_id, task_id)
        REFERENCES public.task(project_id, id),
    CONSTRAINT ck_video_studio_generation_purpose CHECK (project_purpose = 'VIDEO_STUDIO'),
    CONSTRAINT ck_video_studio_generation_command CHECK (length(btrim(command_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_video_studio_generation_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_video_studio_generation_request CHECK (
        COALESCE(jsonb_typeof(request_json) = 'object' AND request_json->'schemaVersion' = '1'::jsonb, false)
    )
);
COMMENT ON TABLE public.video_studio_generation IS 'Owned video Studio command and frozen request, independent of canvas nodes; Task owns execution status';
COMMENT ON COLUMN public.video_studio_generation.id IS 'Stable generation identity';
COMMENT ON COLUMN public.video_studio_generation.owner_id IS 'Authenticated command owner, also the project owner';
COMMENT ON COLUMN public.video_studio_generation.project_id IS 'Dedicated internal video Studio storage and execution scope';
COMMENT ON COLUMN public.video_studio_generation.project_purpose IS 'Fixed discriminator for the owned video Studio project foreign key';
COMMENT ON COLUMN public.video_studio_generation.task_id IS 'The one durable Task identity and execution status source';
COMMENT ON COLUMN public.video_studio_generation.command_key IS 'Owner-scoped client idempotency key';
COMMENT ON COLUMN public.video_studio_generation.request_hash IS 'Canonical request digest for conflicting replays';
COMMENT ON COLUMN public.video_studio_generation.request_json IS 'Versioned frozen capability, prompt, parameters and exact frame references';
COMMENT ON COLUMN public.video_studio_generation.created_at IS 'Acceptance time used for stable history pagination';
COMMENT ON CONSTRAINT video_studio_generation_pkey ON public.video_studio_generation IS 'Unique generation identity';
COMMENT ON CONSTRAINT uq_video_studio_generation_owner_command ON public.video_studio_generation IS 'Atomic owner-scoped command deduplication';
COMMENT ON CONSTRAINT uq_video_studio_generation_task ON public.video_studio_generation IS 'A Task belongs to at most one video Studio generation';
COMMENT ON CONSTRAINT fk_video_studio_generation_project ON public.video_studio_generation IS 'A command must belong to its owner''s video Studio project';
COMMENT ON CONSTRAINT fk_video_studio_generation_task ON public.video_studio_generation IS 'A command Task cannot cross the project scope';
COMMENT ON CONSTRAINT ck_video_studio_generation_purpose ON public.video_studio_generation IS 'Disallows substituting another project purpose';
COMMENT ON CONSTRAINT ck_video_studio_generation_command ON public.video_studio_generation IS 'Nonblank bounded idempotency key';
COMMENT ON CONSTRAINT ck_video_studio_generation_hash ON public.video_studio_generation IS 'SHA-256 command digest';
COMMENT ON CONSTRAINT ck_video_studio_generation_request ON public.video_studio_generation IS 'Frozen video Studio request schema version 1';
COMMENT ON INDEX public.video_studio_generation_pkey IS 'Primary generation lookup';
COMMENT ON INDEX public.uq_video_studio_generation_owner_command IS 'Owner-scoped idempotent replay lookup';
COMMENT ON INDEX public.uq_video_studio_generation_task IS 'Unique command Task lookup';

CREATE INDEX ix_video_studio_generation_owner_history
    ON public.video_studio_generation(owner_id, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_video_studio_generation_owner_history IS 'Bounded owner history using acceptance time and identity keyset';
