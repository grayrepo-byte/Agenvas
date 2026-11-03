-- Logical deletion retains accepted executions, immutable content and shared media references.
ALTER TABLE project DROP CONSTRAINT ck_project_status;
ALTER TABLE project ADD CONSTRAINT ck_project_status
    CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'));
COMMENT ON CONSTRAINT ck_project_status ON project IS '项目生命周期：活动、归档或逻辑删除；删除不清理任务与审计';

ALTER TABLE project DROP CONSTRAINT ck_project_archive_time;
ALTER TABLE project ADD CONSTRAINT ck_project_archive_time CHECK (
    (status = 'ACTIVE' AND archived_at IS NULL)
    OR (status IN ('ARCHIVED', 'DELETED') AND archived_at IS NOT NULL)
);
COMMENT ON CONSTRAINT ck_project_archive_time ON project IS '活动项目没有归档时间，归档及逻辑删除项目保留原归档时间';
