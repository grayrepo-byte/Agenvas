-- Refresh only the exact factory character prompt. Administrator customizations and
-- historical task snapshots are deliberately untouched; names are never rewritten.
UPDATE public.prompt_definition
SET content = $content$Create a character design sheet in a landscape 4:3 composition with four clearly separated parts: a front-facing head-and-shoulders close-up on the left, and a full-body character turnaround on the right showing, from left to right, front view, exact 90-degree side profile, and back view. Keep the three full-body views at the same scale with an aligned foot baseline, without cropping heads or feet. Depict the same character in all four parts, consistently preserving facial identity, hairstyle, skin tone, clothing, color palette, body proportions, accessories, and visible details. Leave enough clear space between the parts. Follow the visual style of the reference image or the user description, with explicit user style instructions taking priority. Render crisp, high-definition detail against a simple, unobtrusive background. Do not add extra characters, labels, or watermarks.$content$,
    description = CASE
        WHEN description = $original_description$基于来源图片生成同一角色的正面半身大头照与全身站立三视图（正面、侧面、背面），采用 16:9 白底柔光角色设定集风格；主体说明追加到正文末尾，新任务冻结当前配置。$original_description$
        THEN $description$基于参考图与用户描述生成横版 4:3 角色设定图：左侧头肩特写，右侧依次为正面、标准 90 度侧面、背面全身；保持同一角色的外貌、服装与细节一致，风格跟随参考图或用户描述。主体说明追加到正文末尾，新任务冻结当前配置。$description$
        ELSE description
    END,
    version = version + 1,
    updated_at = now()
WHERE key = 'image.three-view.character'
  AND content = $original_content$Create a character design sheet from the provided image, featuring a front-facing half-body close-up portrait and a full-body character turnaround with three standing views (front, side, and back). Maintain the same character consistently across all views. Use a 16:9 aspect ratio, a clean pure white background, studio-quality soft lighting, high resolution, and cinematic visual quality.$original_content$;
