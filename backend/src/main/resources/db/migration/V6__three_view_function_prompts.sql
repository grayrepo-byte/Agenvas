-- Seed editable, deletion-protected view prompts, including the revised character design sheet.
-- This data-only migration leaves the prompt schema and existing administrator edits untouched.
INSERT INTO public.prompt_definition (id, key, kind, name, description, content, built_in)
VALUES
    ('00000000-0000-4000-8000-000000000403', 'image.three-view.character', 'FUNCTION', '角色三视图',
     '基于来源图片生成同一角色的正面半身大头照与全身站立三视图（正面、侧面、背面），采用 16:9 白底柔光角色设定集风格；主体说明追加到正文末尾，新任务冻结当前配置。',
     'Create a character design sheet from the provided image, featuring a front-facing half-body close-up portrait and a full-body character turnaround with three standing views (front, side, and back). Maintain the same character consistently across all views. Use a 16:9 aspect ratio, a clean pure white background, studio-quality soft lighting, high resolution, and cinematic visual quality.', true),
    ('00000000-0000-4000-8000-000000000404', 'image.three-view.face', 'FUNCTION', '脸部三视图',
     '基于来源图片生成同一头肩的正面、四分之三角度和侧面；主体说明追加到正文末尾，新任务冻结当前配置。',
     'Create one clean professional facial turnaround sheet from the provided image. Show the same head and shoulders at equal scale in straight front, three-quarter, and exact side profile views. Preserve facial identity, skull and face proportions, skin tone, hairstyle, makeup, expression, and accessories. Use a simple neutral background, even lighting, aligned eye level, clear separation between views, and no labels.', true),
    ('00000000-0000-4000-8000-000000000405', 'image.three-view.prop', 'FUNCTION', '道具三视图',
     '基于来源图片生成同一道具的正面、侧面和背面正交视图；主体说明追加到正文末尾，新任务冻结当前配置。',
     'Create one clean professional prop turnaround sheet from the provided image. Show the exact same object at equal scale in straight front, exact side, and straight back orthographic views. Preserve geometry, construction, materials, textures, colors, wear, and functional details. Use a simple neutral background, even lighting, clear separation between views, and do not add hands, people, labels, or unrelated objects.', true),
    ('00000000-0000-4000-8000-000000000406', 'image.three-view.scene-grid', 'FUNCTION', '场景宫格图',
     '基于来源图片生成同一场景的远景、反向、中景和关键细节 2×2 宫格；主体说明追加到正文末尾，新任务冻结当前配置。',
     'Create one coherent 2 by 2 environment reference grid from the provided scene. The four panels must show the same location as a wide establishing view, a reverse view, a medium view, and a key-detail view. Preserve the spatial layout, architecture, landmarks, materials, colors, time of day, weather, and lighting across all panels. Use clean equal gutters and do not add labels, characters, or unrelated objects unless they are already essential to the source scene.', true);
