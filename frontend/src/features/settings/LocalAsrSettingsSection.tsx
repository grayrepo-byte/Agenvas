import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { ApiError } from '../../shared/api/client';
import { t, useLocale } from '../../shared/i18n';
import { Notice, Panel, StatusBadge } from '../../shared/ui/PagePrimitives';
import { Button } from '../../shared/ui/primitives/button';
import { Switch } from '../../shared/ui/primitives/switch';
import { Progress } from '../../shared/ui/primitives/progress';
import * as api from './localAsrApi';

const KEY = ['settings', 'local-asr'] as const;
const active = (state?: api.LocalAsrSettings['state']) => state === 'QUEUED' || state === 'DOWNLOADING' || state === 'VERIFYING';
const stateKey = { MISSING: 'localAsr.missing', QUEUED: 'localAsr.queued', DOWNLOADING: 'localAsr.downloading', VERIFYING: 'localAsr.verifying',
  READY: 'localAsr.ready', FAILED: 'localAsr.failed', CANCELED: 'localAsr.canceled' } as const;
const readinessKey = { DISABLED: 'localAsr.disabled', ENGINE_UNAVAILABLE: 'localAsr.engineMissing', MODEL_MISSING: 'localAsr.modelMissing',
  RESOURCE_LIMITED: 'localAsr.resourceLimited', AVAILABLE: 'localAsr.available' } as const;
function latest(current: api.LocalAsrSettings | undefined, incoming: api.LocalAsrSettings) {
  if (!current || incoming.version > current.version) return incoming;
  if (incoming.version < current.version) return current;
  const rank = { MISSING: 0, QUEUED: 1, DOWNLOADING: 2, VERIFYING: 3, READY: 4, FAILED: 4, CANCELED: 4 };
  if (current.mode === 'MANAGED' && incoming.mode === 'MANAGED' && current.operationId !== null && current.operationId === incoming.operationId
    && (current.downloadedBytes > incoming.downloadedBytes || rank[current.state] > rank[incoming.state])) return current;
  return incoming;
}

/** Enabling only persists consent; downloading is always a separate explicit administrator action. */
export function LocalAsrSettingsSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const id = useId(), client = useQueryClient();
  const settings = useQuery({ queryKey: KEY, queryFn: async ({ signal }) => {
    const incoming = await api.getLocalAsr(signal);
    return latest(client.getQueryData<api.LocalAsrSettings>(KEY), incoming);
  }, enabled, retry: false,
    refetchInterval: (query) => active(query.state.data?.state) ? 1000 : false });
  const [draft, setDraft] = useState<boolean | null>(null);
  const command = useMutation({ mutationFn: (action: () => Promise<api.LocalAsrSettings>) => action(),
    onMutate: () => client.cancelQueries({ queryKey: KEY }), onSuccess: (value) => {
    client.setQueryData<api.LocalAsrSettings>(KEY, (current) => latest(current, value)); setDraft(null); void client.invalidateQueries({ queryKey: ['subtitle-capabilities'] });
  } });
  const data = settings.data, checked = draft ?? data?.enabled ?? false;
  const error = command.error ?? settings.error;
  const forbidden = error instanceof ApiError && (error.status === 401 || error.status === 403);
  const conflict = error instanceof ApiError && error.status === 409;
  const busy = command.isPending || !data || settings.isError || forbidden;
  const dirty = !!data && checked !== data.enabled;
  const percent = data && data.totalBytes > 0 ? Math.min(100, Math.floor(data.downloadedBytes * 100 / data.totalBytes)) : 0;
  const run = (action: () => Promise<api.LocalAsrSettings>) => command.mutate(action);
  return <section id="local-asr"><Panel title={t('localAsr.title')} description={t('localAsr.description')}
    actions={<StatusBadge tone={data?.readiness === 'AVAILABLE' ? 'success' : 'neutral'}>{data ? t(readinessKey[data.readiness]) : t('localAsr.loading')}</StatusBadge>}>
    <div className="ui-stack">
      <p>{t('localAsr.consent')}</p>
      {error && <Notice tone="danger" title={t(conflict ? 'localAsr.conflict' : forbidden ? 'localAsr.forbidden' : 'localAsr.error')}>
        <p>{error instanceof Error ? error.message : t('localAsr.error')}</p>
        {!forbidden && <Button type="button" variant="outline" disabled={command.isPending || settings.isFetching}
          onClick={() => { command.reset(); void settings.refetch(); }}>{t('common.retry')}</Button>}
      </Notice>}
      <div className="ui-form-actions"><Switch id={id} checked={checked} disabled={busy} onCheckedChange={setDraft} />
        <label htmlFor={id}>{t('localAsr.enable')}</label>
        <Button type="button" disabled={busy || !dirty || conflict} onClick={() => data && run(() => api.saveLocalAsr(checked, data.version))}>{t('localAsr.save')}</Button>
      </div>
      {data && <>
        <p>{data.mode === 'MANUAL' ? t('localAsr.manual') : t('localAsr.model', { model: data.modelId, size: (data.totalBytes / 1024 / 1024).toFixed(1), engine: data.engineVersion })}</p>
        {data.mode === 'MANAGED' && <Notice tone="warning"><p>{t('localAsr.quality')}</p><p>{t('localAsr.resources')}</p></Notice>}
        {data.mode === 'MANAGED' && <><p><a href={data.source} target="_blank" rel="noreferrer">{t('localAsr.source')}</a> · {data.license}</p>
        <p className="ui-muted" style={{ overflowWrap: 'anywhere' }}>{t('localAsr.checksum')}: {data.sha256}</p></>}
        <p role="status">{t(data.mode === 'MANUAL' ? data.state === 'READY' ? 'localAsr.manualReady' : 'localAsr.manualMissing' : stateKey[data.state])}{data.errorCode ? ` · ${data.errorCode}` : ''}</p>
        {active(data.state) && <><Progress aria-label={t('localAsr.progress')} value={percent} /><p>{percent}% · {(data.downloadedBytes / 1024 / 1024).toFixed(1)} / {(data.totalBytes / 1024 / 1024).toFixed(1)} MiB</p></>}
        <div className="ui-form-actions">
          {data.mode === 'MANAGED' && !active(data.state) && data.state !== 'READY' && <Button type="button" disabled={busy || !data.enabled || dirty}
            onClick={() => run(() => api.downloadLocalAsr(data.version))}>{t(data.state === 'FAILED' || data.state === 'CANCELED' ? 'localAsr.retryDownload' : 'localAsr.download')}</Button>}
          {active(data.state) && data.operationId && <Button type="button" variant="outline" disabled={busy}
            onClick={() => run(() => api.cancelLocalAsr(data.operationId!))}>{t('localAsr.cancel')}</Button>}
          <Button type="button" variant="outline" disabled={busy || active(data.state) || dirty}
            onClick={() => run(() => api.checkLocalAsr(data.version))}>{t('localAsr.check')}</Button>
        </div>
        <p>{t('localAsr.retained')}</p>
      </>}
    </div>
  </Panel></section>;
}
