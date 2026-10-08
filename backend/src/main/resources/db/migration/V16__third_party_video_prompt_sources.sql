ALTER TABLE public.third_party_prompt_source DROP CONSTRAINT third_party_prompt_source_format_check;
ALTER TABLE public.third_party_prompt_source ADD CONSTRAINT third_party_prompt_source_format_check
    CHECK (format IN ('NATIVE_JSON', 'GITHUB_MARKDOWN', 'DAVID_JSON', 'BEATAPI_JSON', 'IMAGE_PROMPT_GALLERY_JSON'));
COMMENT ON CONSTRAINT third_party_prompt_source_format_check ON public.third_party_prompt_source IS '仅接受已注册的图片或视频适配器格式';

INSERT INTO public.third_party_prompt_source (id, name, target_kind, format, url, model) VALUES
('youmind-seedance-2-0', 'YouMind / Seedance 2.0', 'VIDEO', 'GITHUB_MARKDOWN', 'https://raw.githubusercontent.com/YouMind-OpenLab/awesome-seedance-2-prompts/main/README.md', 'seedance-2-0'),
('beatapi-minimax-h3', 'BeatAPI / MiniMax H3', 'VIDEO', 'BEATAPI_JSON', 'https://raw.githubusercontent.com/BeatAPI/awesome-minimax-h3-prompts/main/prompts/catalog.json', 'minimax-h3'),
('ipg-seedance-2-0', 'Image Prompt Gallery / Seedance 2.0', 'VIDEO', 'IMAGE_PROMPT_GALLERY_JSON', 'https://imagepromptgallery.com/api/public/prompts?domain=video&model=seedance-2-0&mediaType=video', 'seedance-2-0'),
('ipg-seedance-2-5', 'Image Prompt Gallery / Seedance 2.5', 'VIDEO', 'IMAGE_PROMPT_GALLERY_JSON', 'https://imagepromptgallery.com/api/public/prompts?domain=video&model=seedance-2-5&mediaType=video', 'seedance-2-5'),
('ipg-minimax-h3', 'Image Prompt Gallery / MiniMax H3', 'VIDEO', 'IMAGE_PROMPT_GALLERY_JSON', 'https://imagepromptgallery.com/api/public/prompts?domain=video&model=minimax-h3&mediaType=video', 'minimax-h3');
