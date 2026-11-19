-- Studio owns its immutable command ledger; Task remains the execution status authority.
CREATE TABLE public.image_studio_batch (
    id uuid NOT NULL,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    command_key varchar(160) NOT NULL,
    request_hash char(64) NOT NULL,
    request_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT image_studio_batch_pkey PRIMARY KEY (id),
    CONSTRAINT uq_image_studio_batch_owner_command UNIQUE (owner_id, command_key),
    CONSTRAINT uq_image_studio_batch_project_identity UNIQUE (project_id, id),
    CONSTRAINT fk_image_studio_batch_owner FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT fk_image_studio_batch_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_image_studio_batch_command CHECK (length(btrim(command_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_image_studio_batch_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_image_studio_batch_request CHECK (COALESCE(jsonb_typeof(request_json) = 'object' AND request_json->>'schemaVersion' = '1', false))
);
COMMENT ON TABLE public.image_studio_batch IS 'Owned immutable image Studio command and parameter snapshot, independent of canvas nodes';
COMMENT ON COLUMN public.image_studio_batch.id IS 'Stable batch identity';
COMMENT ON COLUMN public.image_studio_batch.owner_id IS 'Authenticated batch owner';
COMMENT ON COLUMN public.image_studio_batch.project_id IS 'Dedicated internal Studio storage and execution scope';
COMMENT ON COLUMN public.image_studio_batch.command_key IS 'Owner-scoped client idempotency key';
COMMENT ON COLUMN public.image_studio_batch.request_hash IS 'Canonical request digest for conflicting replays';
COMMENT ON COLUMN public.image_studio_batch.request_json IS 'Versioned frozen capability, prompt, parameters and exact references';
COMMENT ON COLUMN public.image_studio_batch.created_at IS 'Acceptance time used for stable history pagination';
COMMENT ON CONSTRAINT image_studio_batch_pkey ON public.image_studio_batch IS 'Unique batch identity';
COMMENT ON CONSTRAINT uq_image_studio_batch_owner_command ON public.image_studio_batch IS 'Atomic owner-scoped command deduplication';
COMMENT ON CONSTRAINT uq_image_studio_batch_project_identity ON public.image_studio_batch IS 'Composite identity for same-scope task membership';
COMMENT ON CONSTRAINT fk_image_studio_batch_owner ON public.image_studio_batch IS 'Batch owner must exist';
COMMENT ON CONSTRAINT fk_image_studio_batch_project ON public.image_studio_batch IS 'Storage scope must exist';
COMMENT ON CONSTRAINT ck_image_studio_batch_command ON public.image_studio_batch IS 'Nonblank bounded idempotency key';
COMMENT ON CONSTRAINT ck_image_studio_batch_hash ON public.image_studio_batch IS 'SHA-256 command digest';
COMMENT ON CONSTRAINT ck_image_studio_batch_request ON public.image_studio_batch IS 'Immutable Studio request schema';
COMMENT ON INDEX public.image_studio_batch_pkey IS 'Primary batch lookup';
COMMENT ON INDEX public.uq_image_studio_batch_owner_command IS 'Owner-scoped idempotent replay lookup';
COMMENT ON INDEX public.uq_image_studio_batch_project_identity IS 'Same-scope batch membership identity';

CREATE INDEX ix_image_studio_batch_owner_history ON public.image_studio_batch(owner_id, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_image_studio_batch_owner_history IS 'Bounded owner history using creation time and identity keyset';

CREATE TABLE public.image_studio_batch_task (
    batch_id uuid NOT NULL,
    project_id uuid NOT NULL,
    ordinal integer NOT NULL,
    task_id uuid NOT NULL,
    CONSTRAINT image_studio_batch_task_pkey PRIMARY KEY (batch_id, ordinal),
    CONSTRAINT uq_image_studio_batch_task_identity UNIQUE (task_id),
    CONSTRAINT fk_image_studio_batch_task_batch FOREIGN KEY (project_id, batch_id)
        REFERENCES public.image_studio_batch(project_id, id),
    CONSTRAINT fk_image_studio_batch_task_task FOREIGN KEY (project_id, task_id)
        REFERENCES public.task(project_id, id),
    CONSTRAINT ck_image_studio_batch_task_ordinal CHECK (ordinal BETWEEN 0 AND 3)
);
COMMENT ON TABLE public.image_studio_batch_task IS 'Ordered independent task membership committed atomically with Studio acceptance';
COMMENT ON COLUMN public.image_studio_batch_task.batch_id IS 'Immutable accepted batch';
COMMENT ON COLUMN public.image_studio_batch_task.project_id IS 'Same internal Studio authorization scope for batch and task';
COMMENT ON COLUMN public.image_studio_batch_task.ordinal IS 'Zero-based task order for requested ordinary generation count';
COMMENT ON COLUMN public.image_studio_batch_task.task_id IS 'Durable Task identity and execution status source';
COMMENT ON CONSTRAINT image_studio_batch_task_pkey ON public.image_studio_batch_task IS 'One task per requested batch position';
COMMENT ON CONSTRAINT uq_image_studio_batch_task_identity ON public.image_studio_batch_task IS 'A task belongs to exactly one Studio batch';
COMMENT ON CONSTRAINT fk_image_studio_batch_task_batch ON public.image_studio_batch_task IS 'Membership cannot cross the batch storage scope';
COMMENT ON CONSTRAINT fk_image_studio_batch_task_task ON public.image_studio_batch_task IS 'Membership cannot cross the task storage scope';
COMMENT ON CONSTRAINT ck_image_studio_batch_task_ordinal ON public.image_studio_batch_task IS 'Studio ordinary generation count supports at most four independent tasks';
COMMENT ON INDEX public.image_studio_batch_task_pkey IS 'Ordered task membership lookup';
COMMENT ON INDEX public.uq_image_studio_batch_task_identity IS 'Unique task membership';
