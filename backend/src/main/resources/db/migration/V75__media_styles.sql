-- Administrator presets are global settings; uploaded previews are decoded/re-encoded bytes,
-- never URLs or references to a project Asset. Task inputs freeze their own exact style snapshot.
CREATE TABLE media_style (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    category varchar(40) NOT NULL,
    prompt_suffix text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    builtin_key varchar(40),
    thumbnail_bytes bytea,
    thumbnail_content_type varchar(32),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_media_style_name CHECK (length(trim(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_media_style_category CHECK (length(trim(category)) BETWEEN 1 AND 40),
    CONSTRAINT ck_media_style_prompt CHECK (length(trim(prompt_suffix)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_media_style_version CHECK (version >= 0),
    CONSTRAINT ck_media_style_preview CHECK (
        (thumbnail_bytes IS NULL AND thumbnail_content_type IS NULL) OR
        (thumbnail_bytes IS NOT NULL AND thumbnail_content_type IS NOT NULL AND octet_length(thumbnail_bytes) BETWEEN 1 AND 1048576 AND thumbnail_content_type = 'image/png')),
    CONSTRAINT uq_media_style_builtin UNIQUE (builtin_key)
);

ALTER TABLE media_draft ADD COLUMN style_id uuid;
ALTER TABLE media_draft ADD CONSTRAINT fk_media_draft_style FOREIGN KEY (style_id) REFERENCES media_style(id);
COMMENT ON COLUMN media_draft.style_id IS 'Optional image/video visual style; user prompt remains unchanged. Disabled choices remain explicit.';

INSERT INTO media_style (id, name, category, prompt_suffix, builtin_key) VALUES
('00000000-0000-4000-8000-000000000301', '写实摄影', '写实', 'Photorealistic photography, natural textures and realistic materials, balanced natural lighting, lifelike detail, refined color grading.', 'photographic'),
('00000000-0000-4000-8000-000000000302', '电影质感', '写实', 'Cinematic visual storytelling, expressive film lighting, carefully composed frames, rich but restrained color grading, subtle film grain, realistic materials.', 'cinematic'),
('00000000-0000-4000-8000-000000000303', '日系动漫', '插画', 'Japanese anime illustration, clean expressive linework, layered cel shading, delicate luminous colors, detailed painted backgrounds, coherent character design.', 'anime'),
('00000000-0000-4000-8000-000000000304', '3D 动画', '3D', 'High-quality stylized 3D animation, appealing rounded forms, polished physically based materials, soft global illumination, playful expressive details.', 'three-dimensional'),
('00000000-0000-4000-8000-000000000305', '水彩插画', '绘画', 'Watercolor illustration on textured paper, translucent washes, soft organic edges, delicate pigment blooms, gentle harmonious colors, hand-painted detail.', 'watercolor'),
('00000000-0000-4000-8000-000000000306', '国风水墨', '绘画', 'Traditional Chinese ink-wash painting, expressive ink brushwork, elegant restrained color accents, rice-paper texture, poetic negative space, atmospheric layered washes.', 'ink-wash'),
('00000000-0000-4000-8000-000000000307', '赛博朋克', '幻想', 'Cyberpunk visual aesthetic, luminous neon accents, futuristic urban design, rich electric blue and magenta color contrast, atmospheric haze, detailed metallic materials.', 'cyberpunk'),
('00000000-0000-4000-8000-000000000308', '黏土定格', '3D', 'Handcrafted clay stop-motion aesthetic, tactile sculpted clay surfaces, charming miniature sets, softly rounded shapes, visible handmade imperfections, warm studio lighting.', 'clay');
