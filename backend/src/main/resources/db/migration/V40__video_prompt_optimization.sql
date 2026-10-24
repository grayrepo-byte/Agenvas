-- Video prompt optimization is a text-only Task; existing media drafts and results are untouched.
INSERT INTO public.prompt_definition (id, key, kind, name, description, content, built_in)
VALUES ('00000000-0000-4000-8000-000000000408', 'video.optimize-prompt', 'FUNCTION', '视频提示词优化',
        '优化视频草稿提示词，可选择固定版本的 Skill 文字方法；结果由用户确认回填。',
        'Rewrite the supplied video-generation prompt into a clear, actionable prompt. Preserve its intent, subjects, dialogue, language and reference tokens. Clarify motion, performance, shot timing, camera movement, composition, light and continuity without inventing facts about unseen media. Apply the selected Skill as a creative writing method when supplied. Return only the finished reusable video prompt.', true);
