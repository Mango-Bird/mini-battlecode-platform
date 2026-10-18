export type BotMetadata = { botId: string; name: string; createdAt: string; updatedAt: string; latestVersion: number; sourceSha256: string; validationStatus: string; executionEligibility: string; versions?: { version: number; sourceSha256: string }[] };
export type BotVersion = { version: number; source: string; sourceSha256: string; createdAt: string; validationStatus: string; executionEligibility: string };
export type BotDetail = { metadata: BotMetadata; versions: BotVersion[] };
export const BOT_TEMPLATE = `function chooseMove(observation) {
  // bot-1 · ott-board-9x9-v1 · synchronous function
  const move = observation.legalMoves[0];
  return { pieceId: move.pieceId, to: { row: move.to.row, col: move.to.col } };
}
`;
import { safeStaging, playerToolsEnabled } from './runtime';
export class SessionExpiredError extends Error {}
export function notifyAuthChange() {
  // Only a change signal, never identity/credentials/source, is shared across tabs.
  localStorage.setItem('battlecode-auth-change', crypto.randomUUID());
}
export async function localApi<T>(path: string, method = 'GET', body?: object, signal?: AbortSignal): Promise<T> {
  if (!playerToolsEnabled) throw new Error('Workspace chỉ bật trong local development hoặc safe staging.');
  const base = safeStaging ? window.location.origin : import.meta.env.VITE_BOT_API_URL || 'http://127.0.0.1:8090';
  const url = new URL(base);
  if (!safeStaging && (url.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(url.hostname) || url.username || url.password))
    throw new Error('Submission API phải là HTTP loopback.');
  if (safeStaging && (url.protocol !== 'https:' || path.includes('..') || path.includes('?') && !/^match-history\?page=\d+$/.test(path))) throw new Error('Invalid staging API request.');
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), safeStaging ? 60000 : 5000);
  const cancel = () => abort.abort(); signal?.addEventListener('abort', cancel, { once: true }); if (signal?.aborted) abort.abort();
  try {
    const prefix = safeStaging ? '/api/staging/' : '/api/local/';
    let csrfToken: string | undefined;
    if (safeStaging && method !== 'GET') {
      const bootstrap = await fetch(base + prefix + 'auth/csrf', { credentials: 'include', signal: abort.signal });
      if (!bootstrap.ok) throw new Error('Không lấy được CSRF token. Hãy thử lại.');
      csrfToken = (await bootstrap.json()).csrfToken;
      if (!csrfToken) throw new Error('Missing CSRF token');
    }
    const response = await fetch(base + prefix + path, {
      method, signal: abort.signal, credentials: 'include',
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (response.status === 401) {
      if (!path.startsWith('auth/')) window.dispatchEvent(new Event('battlecode-session-expired'));
      throw new SessionExpiredError('Phiên đã hết hạn hoặc chưa đăng nhập. Vui lòng đăng nhập lại.');
    }
    if (response.status === 403) throw new Error('Bạn không có quyền truy cập tài nguyên này.');
    if (!response.ok) throw new Error((await response.json()).error || `HTTP ${response.status}`);
    return await response.json() as T;
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel); }
}
export function submissionApi<T>(path = '', method = 'GET', body?: object, signal?: AbortSignal): Promise<T> {
  return localApi<T>('submissions' + path, method, body, signal);
}
