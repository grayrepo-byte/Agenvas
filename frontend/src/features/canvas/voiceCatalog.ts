import type { MessageKey } from "../../shared/i18n";

// Public preset files from the official voice library. Audition never uses a user's Key.
const OFFICIAL_SAMPLE_BASE_URL = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/";
function sampleUrl(file: string) { return `${OFFICIAL_SAMPLE_BASE_URL}${file}`; }

/** Curated Seed Audio-compatible TTS 2.0 voices.
 * IDs: https://docs.volcengine.com/docs/DoubaoVoice/Tonelist-1?lang=zh
 * Samples: https://console.volcengine.com/speech/new/voices
 * Only verified public files are listed. Vivi remains selectable; its sample URL is unverified.
 */
export const VOICES = [
  { name: "Vivi 2.0", id: "zh_female_vv_uranus_bigtts", language: "zh", scene: "general", previewUrl: null },
  { name: "小何 2.0", id: "zh_female_xiaohe_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_female_xiaohe_uranus_bigtts.mp3") },
  { name: "云舟 2.0", id: "zh_male_m191_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_male_m191_uranus_bigtts.mp3") },
  { name: "小天 2.0", id: "zh_male_taocheng_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_male_taocheng_uranus_bigtts.mp3") },
  { name: "魅力苏菲 2.0", id: "zh_female_sophie_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("Sophie.mp3") },
  { name: "清新女声 2.0", id: "zh_female_qingxinnvsheng_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_female_qingxinnvsheng_uranus_bigtts.mp3") },
  { name: "知性灿灿 2.0", id: "zh_female_cancan_uranus_bigtts", language: "zh", scene: "roleplay", previewUrl: sampleUrl("zh_female_cancan_uranus_bigtts.mp3") },
  { name: "撒娇学妹 2.0", id: "zh_female_sajiaoxuemei_uranus_bigtts", language: "zh", scene: "roleplay", previewUrl: sampleUrl("zh_female_sajiaoxuemei_uranus_bigtts.mp3") },
  { name: "温柔小雅 2.0", id: "zh_female_wenrouxiaoya_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_female_wenrouxiaoya_uranus_bigtts.mp3") },
  { name: "广告解说 2.0", id: "zh_male_guanggaojieshuo_uranus_bigtts", language: "zh", scene: "general", previewUrl: sampleUrl("zh_male_guanggaojieshuo_uranus_bigtts.mp3") },
  { name: "儒雅逸辰 2.0", id: "zh_male_ruyayichen_uranus_bigtts", language: "zh", scene: "videoDubbing", previewUrl: sampleUrl("zh_male_ruyayichen_uranus_bigtts.mp3") },
  { name: "少儿故事 2.0", id: "zh_female_shaoergushi_uranus_bigtts", language: "zh", scene: "audiobook", previewUrl: sampleUrl("zh_female_shaoergushi_uranus_bigtts.mp3") },
  { name: "悬疑解说 2.0", id: "zh_male_xuanyijieshuo_uranus_bigtts", language: "zh", scene: "audiobook", previewUrl: sampleUrl("zh_male_xuanyijieshuo_uranus_bigtts.mp3") },
  { name: "儿童绘本 2.0", id: "zh_female_xiaoxue_uranus_bigtts", language: "zh", scene: "audiobook", previewUrl: sampleUrl("zh_female_xiaoxue_uranus_bigtts.mp3") },
  { name: "Tina老师 2.0", id: "zh_female_yingyujiaoxue_uranus_bigtts", language: "zh", scene: "education", previewUrl: sampleUrl("zh_female_yingyujiaoxue_uranus_bigtts.mp3") },
  { name: "暖阳女声 2.0", id: "zh_female_kefunvsheng_uranus_bigtts", language: "zh", scene: "customerService", previewUrl: sampleUrl("zh_female_kefunvsheng_uranus_bigtts.mp3") },
  { name: "Tim", id: "en_male_tim_uranus_bigtts", language: "en", scene: "general", previewUrl: sampleUrl("en_male_tim_uranus_bigtts.mp3") },
  { name: "Dacey", id: "en_female_dacey_uranus_bigtts", language: "en", scene: "general", previewUrl: sampleUrl("en_female_dacey_uranus_bigtts.mp3") },
  { name: "Stokie", id: "en_female_stokie_uranus_bigtts", language: "en", scene: "general", previewUrl: sampleUrl("en_female_stokie_uranus_bigtts.mp3") },
] as const;

/** Filter values are stable identities; only their UI labels are localized. */
export const VOICE_LANGUAGE_LABEL_KEYS = {
  zh: "common.chinese",
  en: "audio.voices.english",
} satisfies Record<typeof VOICES[number]["language"], MessageKey>;

export const VOICE_SCENE_LABEL_KEYS = {
  general: "audio.voices.general",
  roleplay: "audio.voices.roleplay",
  videoDubbing: "audio.voices.videoDubbing",
  audiobook: "audio.voices.audiobook",
  education: "audio.voices.education",
  customerService: "audio.voices.customerService",
} satisfies Record<typeof VOICES[number]["scene"], MessageKey>;
