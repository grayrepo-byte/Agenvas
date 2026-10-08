import { Check, PaintBrush } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { mediaStylesQueryOptions } from "../../shared/mediaStyles";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { MediaStylePreview } from "../../shared/ui/MediaStylePreview";
import { EmptyState, Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

export function MediaStylePicker({ selected, onSelect, onClose }: {
  selected: string | null; onSelect: (styleId: string | null) => void; onClose: () => void;
}) {
  useLocale();
  const styles = useQuery(mediaStylesQueryOptions());
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const available = styles.data?.filter((style) => style.enabled) ?? [];
  const categories = [...new Set(available.map((style) => style.category))];
  const needle = search.trim().toLocaleLowerCase();
  const filtered = available.filter((style) => (!category || style.category === category)
    && `${style.name} ${style.category}`.toLocaleLowerCase().includes(needle));
  const choose = (styleId: string | null) => { onSelect(styleId); onClose(); };
  return <Dialog title={t("styles.choose")} description={t("styles.pickerHint")} className="media-style-picker"
    onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    <div className="media-style-picker-controls">
      <Field><FieldLabel htmlFor="media-style-search">{t("styles.search")}</FieldLabel><Input id="media-style-search"
        type="search" value={search} placeholder={t("styles.searchPlaceholder")} onChange={(event) => setSearch(event.target.value)} /></Field>
      <Field className="media-style-category-filter"><FieldLabel htmlFor="media-style-category">{t("styles.category")}</FieldLabel>
        <Select id="media-style-category" value={category} onChange={(event) => setCategory(event.target.value)}>
          <option value="">{t("styles.allCategories")}</option>{categories.map((value) => <option key={value} value={value}>{value}</option>)}
        </Select></Field>
    </div>
    <Button type="button" variant={selected ? "outline" : "secondary"} className="media-style-none" aria-pressed={!selected}
      onClick={() => choose(null)}><PaintBrush data-icon="inline-start" />{t("styles.none")}{!selected ? <Check data-icon="inline-end" /> : null}</Button>
    {styles.isPending ? <LoadingState compact label={t("styles.loading")} /> : null}
    {styles.error ? <Notice tone="danger" title={t("styles.loadFailed")}><p>{styles.error.message}</p>
      <Button type="button" variant="outline" disabled={styles.isFetching} onClick={() => void styles.refetch()}>{t("common.retry")}</Button></Notice> : null}
    {styles.isSuccess && !filtered.length ? <EmptyState icon={<PaintBrush />} title={t("styles.empty")}
      description={available.length ? t("styles.noMatches") : t("styles.emptyHint")} /> : null}
    <div className="media-style-grid">
      {filtered.map((style) => <Button key={style.id} type="button" variant={style.id === selected ? "secondary" : "outline"}
        className="media-style-choice" aria-label={style.name} aria-pressed={style.id === selected} onClick={() => choose(style.id)}>
        <MediaStylePreview style={style} /><span className="media-style-choice-label"><span>{style.name}</span>
          {style.id === selected ? <Check aria-hidden /> : null}</span>
      </Button>)}
    </div>
  </Dialog>;
}
