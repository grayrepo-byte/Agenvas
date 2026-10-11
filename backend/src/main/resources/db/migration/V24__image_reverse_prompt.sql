-- Image analysis uses the existing text Task pipeline and an editable, deletion-protected prompt.
INSERT INTO public.prompt_definition (id, key, kind, name, description, content, built_in)
VALUES ('00000000-0000-4000-8000-000000000407', 'image.reverse-prompt', 'FUNCTION', '图片提示词反推',
        '根据当前选用的图片版本推导可用于图片生成的提示词；每次执行固定当前正文，不覆盖媒体草稿。',
        'Analyze the provided image and write one detailed image-generation prompt that recreates its visible appearance. Describe the main subject, pose or action, clothing and materials, environment, composition, camera angle and framing, lighting, colors, mood, and visual style. Prioritize distinctive visible details and avoid unsupported guesses. Return only the reusable prompt, without explanations, headings, code fences, or claims that you recovered the original prompt.', true);
