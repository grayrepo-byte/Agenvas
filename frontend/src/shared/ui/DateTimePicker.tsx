import { CalendarBlank } from "@/shared/ui/icons";
import { format } from "date-fns";
import { useState } from "react";
import { enUS, ja, ru, zhCN } from "react-day-picker/locale";
import { getFormatLocale, t, useLocale } from "../i18n";
import { Button } from "./primitives/button";
import { Calendar } from "./primitives/calendar";
import { Input } from "./primitives/input";
import { Popover, PopoverContent, PopoverTrigger } from "./primitives/popover";

const CALENDAR_LOCALES = { en: enUS, zh: zhCN, ru, ja };
const TIME_PATTERN = "([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]";

/** Calendar days and time drafts stay local; only the owning form converts to UTC. */
export function DateTimePicker({ id, name, label, defaultValue, invalid = false, describedBy }: {
  id: string; name: string; label: string; defaultValue?: string; invalid?: boolean; describedBy?: string;
}) {
  const locale = useLocale();
  const initial = defaultValue ? new Date(defaultValue) : undefined;
  const validInitial = initial && !Number.isNaN(initial.getTime()) ? initial : undefined;
  const [date, setDate] = useState<Date | undefined>(validInitial);
  const [time, setTime] = useState(validInitial ? format(validInitial, "HH:mm:ss") : "00:00:00");
  const [open, setOpen] = useState(false);

  return <div className="flex min-w-0 gap-2">
    <input type="hidden" name={name} value={date ? `${format(date, "yyyy-MM-dd")}T${time}` : ""} />
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button id={id} type="button" variant="outline" className="min-w-0 flex-1 justify-start"
          aria-label={label} aria-invalid={invalid || undefined} aria-describedby={describedBy}>
          <CalendarBlank data-icon="inline-start" aria-hidden />
          <span className="truncate">{date ? date.toLocaleDateString(getFormatLocale()) : t("ui.dateTime.chooseDate")}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto p-0" aria-label={label}>
        <Calendar mode="single" locale={CALENDAR_LOCALES[locale]} selected={date} defaultMonth={date} autoFocus
          labels={{ labelPrevious: () => t("ui.dateTime.previousMonth"), labelNext: () => t("ui.dateTime.nextMonth") }}
          onSelect={(next) => { setDate(next); setOpen(false); }} />
        <div className="flex justify-end px-3 pb-3">
          <Button type="button" variant="ghost" size="sm" disabled={!date}
            onClick={() => { setDate(undefined); setTime("00:00:00"); setOpen(false); }}>{t("ui.dateTime.clear")}</Button>
        </div>
      </PopoverContent>
    </Popover>
    <Input className="w-28 shrink-0" aria-label={t("ui.dateTime.timeLabel", { "0": label })}
      type="text" placeholder="HH:mm:ss" pattern={TIME_PATTERN} required={Boolean(date)}
      disabled={!date} value={time} onChange={(event) => setTime(event.target.value)}
      aria-invalid={invalid || undefined} aria-describedby={describedBy} />
  </div>;
}
