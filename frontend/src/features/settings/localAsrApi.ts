import { readJson, writeJson } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';
import { t } from '../../shared/i18n';
export type LocalAsrSettings = components['schemas']['LocalAsrSettings'];
const path = '/api/v1/settings/local-asr';
export const getLocalAsr = (signal?: AbortSignal) => readJson<LocalAsrSettings>(path, t('localAsr.error'), signal);
export const saveLocalAsr = (enabled: boolean, expectedVersion: number) => writeJson<LocalAsrSettings>(path,
  { method: 'PUT', body: JSON.stringify({ enabled, expectedVersion }) }, t('localAsr.error'));
export const downloadLocalAsr = (expectedVersion: number) => writeJson<LocalAsrSettings>(`${path}/download`,
  { method: 'POST', body: JSON.stringify({ expectedVersion }) }, t('localAsr.error'));
export const cancelLocalAsr = (operationId: string) => writeJson<LocalAsrSettings>(`${path}/cancel`,
  { method: 'POST', body: JSON.stringify({ operationId }) }, t('localAsr.error'));
export const checkLocalAsr = (expectedVersion: number) => writeJson<LocalAsrSettings>(`${path}/check`,
  { method: 'POST', body: JSON.stringify({ expectedVersion }) }, t('localAsr.error'));
