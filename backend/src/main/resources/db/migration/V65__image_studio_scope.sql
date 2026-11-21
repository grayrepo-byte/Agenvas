ALTER TABLE public.project ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'CANVAS';
ALTER TABLE public.project ADD CONSTRAINT ck_project_purpose CHECK (purpose IN ('CANVAS', 'IMAGE_STUDIO'));
COMMENT ON COLUMN public.project.purpose IS '权限与存储作用域用途：CANVAS 为用户项目，IMAGE_STUDIO 为独立生图工作台内部作用域';
COMMENT ON CONSTRAINT ck_project_purpose ON public.project IS '限定用户画布项目与独立生图工作台作用域，不改变既有项目用途';

CREATE UNIQUE INDEX uq_project_image_studio_owner ON public.project(owner_id) WHERE purpose = 'IMAGE_STUDIO';
COMMENT ON INDEX public.uq_project_image_studio_owner IS '每个所有者最多一个独立生图工作台作用域，串行化首次进入时的并发创建';
