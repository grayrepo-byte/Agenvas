ALTER TABLE task DROP CONSTRAINT ck_task_kind;
ALTER TABLE task ADD CONSTRAINT ck_task_kind CHECK (kind IN ('AGENT_TURN', 'TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'AUDIO_GENERATION', 'SUBTITLE_TRANSCRIPTION'));
COMMENT ON CONSTRAINT ck_task_kind ON task IS '受支持的持久任务类型，包含无外部费用的本地语音识别';
ALTER TABLE task DROP CONSTRAINT ck_task_run_scope;
ALTER TABLE task ADD CONSTRAINT ck_task_run_scope CHECK (run_id IS NOT NULL OR kind IN ('TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'AUDIO_GENERATION', 'SUBTITLE_TRANSCRIPTION'));
COMMENT ON CONSTRAINT ck_task_run_scope ON task IS 'Agent 回合必须属于 Run，用户直连生成和本地字幕识别可独立执行';

CREATE TABLE subtitle_draft (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    source_duration_ms integer NOT NULL,
    language varchar(8) NOT NULL,
    cues_json jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    last_task_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_subtitle_draft_source UNIQUE (project_id, source_version_id),
    CONSTRAINT fk_subtitle_draft_source FOREIGN KEY (project_id, source_version_id) REFERENCES artifact_version(project_id, id),
    CONSTRAINT fk_subtitle_draft_task FOREIGN KEY (project_id, last_task_id) REFERENCES task(project_id, id),
    CONSTRAINT ck_subtitle_draft_duration CHECK (source_duration_ms BETWEEN 1 AND 600000),
    CONSTRAINT ck_subtitle_draft_language CHECK (language ~ '^(auto|[a-z]{2,3})$'),
    CONSTRAINT ck_subtitle_draft_version CHECK (version >= 0),
    CONSTRAINT ck_subtitle_draft_cues CHECK (jsonb_typeof(cues_json) = 'object' AND cues_json->>'schemaVersion' = '1'
        AND jsonb_typeof(cues_json->'cues') = 'array' AND jsonb_array_length(cues_json->'cues') <= 5000)
);
COMMENT ON TABLE subtitle_draft IS '固定媒体版本的可校正字幕草稿；识别原始结果保存在不可变的任务终态输出中';
COMMENT ON COLUMN subtitle_draft.id IS '字幕草稿稳定身份';
COMMENT ON COLUMN subtitle_draft.project_id IS '所属项目和授权作用域';
COMMENT ON COLUMN subtitle_draft.source_version_id IS '固定的不可变音视频内容版本，不跟随节点结果选择';
COMMENT ON COLUMN subtitle_draft.source_duration_ms IS '来源实际时长和字幕校正的毫秒边界';
COMMENT ON COLUMN subtitle_draft.language IS '识别请求语言；auto 不表示已经确认实际语言';
COMMENT ON COLUMN subtitle_draft.cues_json IS 'schemaVersion=1 的纯文本字幕及来源时间列表，不存媒体字节或模型推理';
COMMENT ON COLUMN subtitle_draft.version IS 'CAS 版本；识别完成不得覆盖受理后发生的人工校正';
COMMENT ON COLUMN subtitle_draft.last_task_id IS '最近一次用户显式发起的识别任务，可读取其失败、取消和独立识别结果';
COMMENT ON COLUMN subtitle_draft.created_at IS '草稿创建时间';
COMMENT ON COLUMN subtitle_draft.updated_at IS '最近一次保存或识别结果应用时间';
COMMENT ON CONSTRAINT subtitle_draft_pkey ON subtitle_draft IS '字幕草稿主键';
COMMENT ON CONSTRAINT uq_subtitle_draft_source ON subtitle_draft IS '同一项目的同一固定来源版本复用一份校正草稿';
COMMENT ON CONSTRAINT fk_subtitle_draft_source ON subtitle_draft IS '来源版本必须存在于同一项目';
COMMENT ON CONSTRAINT fk_subtitle_draft_task ON subtitle_draft IS '识别任务必须属于同一项目';
COMMENT ON CONSTRAINT ck_subtitle_draft_duration ON subtitle_draft IS '沿用媒体来源最长十分钟的实际时长边界';
COMMENT ON CONSTRAINT ck_subtitle_draft_language ON subtitle_draft IS '语言码或显式自动检测请求';
COMMENT ON CONSTRAINT ck_subtitle_draft_version ON subtitle_draft IS '并发控制版本不允许负数';
COMMENT ON CONSTRAINT ck_subtitle_draft_cues ON subtitle_draft IS '字幕 JSON 版本、结构和数量的存储边界';
COMMENT ON INDEX subtitle_draft_pkey IS '支撑字幕草稿身份读取';
COMMENT ON INDEX uq_subtitle_draft_source IS '支撑按项目和固定来源重新打开字幕草稿';
