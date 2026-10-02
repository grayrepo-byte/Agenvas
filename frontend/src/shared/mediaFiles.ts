export const MEDIA_FILE_ACCEPT = {
  IMAGE: "image/png,image/jpeg,image/webp",
  AUDIO: "audio/mpeg,audio/wav,audio/ogg",
  VIDEO: "video/mp4",
} as const;

const AUDIO_FILE_EXTENSION = /\.(mp3|wav|ogg)$/i;

/** Routes a local upload; the server still validates the actual media bytes. */
export function isAudioFile(file: Pick<File, "type" | "name">): boolean {
  return file.type.startsWith("audio/") || AUDIO_FILE_EXTENSION.test(file.name);
}
