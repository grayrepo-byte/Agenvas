import { t } from "../../shared/i18n";
import { CreatorNumberInput } from "../../shared/ui/CreatorNumberInput";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Progress } from "../../shared/ui/primitives/progress";
import { Slider } from "../../shared/ui/primitives/slider";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { DIRECTOR_CAMERA_TRANSITIONS, DIRECTOR_MOTION_LIMITS, type DirectorKeyframe, type DirectorMotion } from "./directorScene";
import { resizeDirectorMotion } from "./directorMotion";

interface Props {
  motion: DirectorMotion;
  time: number;
  selectedId: string;
  playing: boolean;
  disabled: boolean;
  ready: boolean;
  onChange: (motion: DirectorMotion) => void;
  onSeek: (time: number) => void;
  onSelect: (frame: DirectorKeyframe) => void;
  onRecord: () => void;
  onUpdateCamera: () => void;
  onPlay: () => void;
  onPause: () => void;
  onExportFrame: () => void;
  onExportVideo: () => void;
  onCancelVideo: () => void;
  exportingVideo: boolean;
  videoProgress: number;
  videoNotice: string | null;
}

/** Keyframes double as named reusable camera positions; each stores a complete look-at pose. */
export function DirectorMotionPanel(props: Props) {
  const { motion, disabled, playing } = props;
  const selected = motion.keyframes.find((frame) => frame.id === props.selectedId);
  const patch = (update: Partial<DirectorKeyframe>) => {
    const frames = motion.keyframes.map((frame) => frame.id === selected?.id ? { ...frame, ...update } : frame)
      .sort((a, b) => a.timeSeconds - b.timeSeconds);
    props.onChange({ ...motion, keyframes: frames });
  };
  const timeOccupied = motion.keyframes.some((frame) => Math.abs(frame.timeSeconds - props.time) < 0.001);
  return <FieldGroup className="director-motion-panel" data-motion-time={props.time} data-motion-playing={playing}>
    <Field><FieldLabel htmlFor="director-motion-duration">{t("director.motion.duration")}</FieldLabel><CreatorNumberInput id="director-motion-duration" aria-label={t("director.motion.duration")}
      value={motion.durationSeconds} min={DIRECTOR_MOTION_LIMITS.minDuration} max={DIRECTOR_MOTION_LIMITS.maxDuration} step={1}
      disabled={disabled || playing} onValueChange={(value) => {
        if (value !== undefined && Number.isFinite(value) && value >= DIRECTOR_MOTION_LIMITS.minDuration && value <= DIRECTOR_MOTION_LIMITS.maxDuration)
          props.onChange(resizeDirectorMotion(motion, value));
      }} /></Field>
    <Field><FieldLabel>{t("director.motion.playhead", { time: props.time.toFixed(2) })}</FieldLabel>
      <Slider value={[props.time]} min={0} max={motion.durationSeconds} step={0.01} disabled={disabled || !motion.keyframes.length}
        thumbLabels={[t("director.motion.seek")]} onValueChange={([value]) => { if (value !== undefined) props.onSeek(value); }} />
    </Field>
    <div className="director-motion-actions">
      <Button variant="ghost" size="sm" disabled={disabled || !props.ready || motion.keyframes.length < 2} onClick={playing ? props.onPause : props.onPlay}>
        {t(playing ? "director.motion.pause" : "director.motion.play")}</Button>
      <Button variant="ghost" size="sm" disabled={disabled || !motion.keyframes.length} onClick={() => props.onSeek(0)}>{t("director.motion.rewind")}</Button>
      <Button variant="outline" size="sm" disabled={disabled || playing || timeOccupied || motion.keyframes.length >= DIRECTOR_MOTION_LIMITS.maxKeyframes}
        onClick={props.onRecord}>{t("director.motion.record")}</Button>
    </div>
    <ToggleGroup type="single" value={props.selectedId} onValueChange={(id) => {
      const frame = motion.keyframes.find((item) => item.id === (id || props.selectedId)); if (frame) props.onSelect(frame);
    }} disabled={disabled || playing} variant="outline" spacing={1} className="director-motion-keys" aria-label={t("director.motion.cameras")}>
      {motion.keyframes.map((frame) => <ToggleGroupItem key={frame.id} value={frame.id} className="director-motion-key" aria-label={`${frame.name} (${frame.timeSeconds.toFixed(2)}s)`}>
        <span>{frame.name}</span><small>{frame.timeSeconds.toFixed(2)}s</small>
      </ToggleGroupItem>)}
    </ToggleGroup>
    {!motion.keyframes.length ? <p className="director-save-hint">{t("director.motion.empty")}</p> : null}
    {selected ? <>
      <Field><FieldLabel htmlFor="director-keyframe-name">{t("director.motion.name")}</FieldLabel>
        <Input id="director-keyframe-name" value={selected.name} maxLength={80} disabled={disabled || playing}
          onChange={(event) => { if (event.target.value.trim()) patch({ name: event.target.value }); }} /></Field>
      <Field><FieldLabel htmlFor="director-keyframe-time">{t("director.motion.time")}</FieldLabel><CreatorNumberInput id="director-keyframe-time" aria-label={t("director.motion.time")} value={selected.timeSeconds}
        min={0} max={motion.durationSeconds} step={0.01} disabled={disabled || playing} onValueChange={(value) => {
          if (value !== undefined && Number.isFinite(value) && value >= 0 && value <= motion.durationSeconds
            && !motion.keyframes.some((frame) => frame.id !== selected.id && Math.abs(frame.timeSeconds - value) < 0.001)) patch({ timeSeconds: value });
        }} /></Field>
      <Field><FieldLabel htmlFor="director-keyframe-transition">{t("director.motion.transition")}</FieldLabel>
        <Select id="director-keyframe-transition" value={selected.transition} disabled={disabled || playing} onChange={(event) => {
          const transition = DIRECTOR_CAMERA_TRANSITIONS.find((value) => value === event.target.value); if (transition) patch({ transition });
        }}>{DIRECTOR_CAMERA_TRANSITIONS.map((value) => <option key={value} value={value}>{t(({ LINEAR: "director.motion.linear", SMOOTH: "director.motion.smooth", CUT: "director.motion.cut" } as const)[value])}</option>)}</Select>
      </Field>
      <div className="director-motion-actions">
        <Button variant="ghost" size="sm" disabled={disabled || playing} onClick={props.onUpdateCamera}>{t("director.motion.update")}</Button>
        <Button variant="outline" size="sm" disabled={disabled || playing} onClick={() => props.onChange({ ...motion,
          keyframes: motion.keyframes.filter((frame) => frame.id !== selected.id) })}>{t("director.motion.remove")}</Button>
      </div>
    </> : null}
    <Button variant="ghost" size="sm" disabled={disabled || playing || !props.ready} onClick={props.onExportFrame}>{t("director.motion.exportFrame")}</Button>
    {props.exportingVideo ? <div className="director-video-export">
      <p role="status">{t("director.motion.videoProgress", { percent: props.videoProgress })}</p>
      <Progress aria-label={t("director.motion.videoProgressLabel")} value={props.videoProgress} max={100} />
      <Button variant="ghost" size="sm" onClick={props.onCancelVideo}>{t("director.motion.cancelVideo")}</Button>
    </div> : <Button variant="outline" size="sm" disabled={disabled || playing || !props.ready || motion.keyframes.length < 2}
      onClick={props.onExportVideo}>{t("director.motion.exportVideo")}</Button>}
    {props.videoNotice ? <p role="status">{props.videoNotice}</p> : null}
    <p className="director-save-hint">{t("director.motion.videoHint")}</p>
    <p className="director-save-hint">{t("director.motion.referenceHint")}</p>
  </FieldGroup>;
}
