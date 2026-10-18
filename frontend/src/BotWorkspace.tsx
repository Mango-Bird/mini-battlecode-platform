import { playerToolsEnabled } from './runtime';
import { productLabel } from './product-labels';
import { useEffect, useRef, useState } from 'react';
import { BOT_TEMPLATE, submissionApi, type BotDetail, type BotMetadata } from './local-submissions';
import './bot-workspace.css';
import { useSession } from './session';
import { keepDraft, readDraft } from './drafts';
import { setNavigationBlocked } from './navigation';
import { Badge, PageHeader, Toast, ErrorState } from './ui';

export default function BotWorkspace({ initialBotId }: { initialBotId?: string }) {
  const ownerId = useSession()?.user?.id;
  const restored = useRef(ownerId ? readDraft(ownerId) : undefined);
  const [bots, setBots] = useState<BotMetadata[]>([]), [selected, setSelected] = useState<BotDetail | null>(null);
  const [name, setName] = useState('My Bot'), [source, setSource] = useState(BOT_TEMPLATE);
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [viewVersion, setViewVersion] = useState(0);
  const [draftCached, setDraftCached] = useState(true);
  const alive = useRef(true), lock = useRef(false), abort = useRef(new AbortController());
  const pending = useRef<{ key: string; requestId: string } | null>(null);
  const dirty = selected ? name !== selected.metadata.name || source !== selected.versions.at(-1)!.source : source !== BOT_TEMPLATE || name !== 'My Bot';
  const sourceBytes = new TextEncoder().encode(source).length;
  useEffect(() => {
    if (!ownerId) return;
    if (restored.current) { const draft = restored.current; setSelected(draft.selected); setName(draft.name); setSource(draft.source); setNotice('Đã khôi phục bản nháp riêng của tài khoản này từ bộ nhớ tab.'); }
  }, [ownerId]);
  useEffect(() => {
    if (ownerId) setDraftCached(keepDraft(ownerId, dirty ? { selected, name, source } : null));
    setNavigationBlocked(dirty);
    return () => setNavigationBlocked(false);
  }, [ownerId, dirty, selected, name, source]);
  async function list() {
    const result = await submissionApi<{ submissions: BotMetadata[] }>('', 'GET', undefined, abort.current.signal);
    if (alive.current) setBots(result.submissions);
  }
  async function task(work: () => Promise<void>) {
    if (lock.current) return; lock.current = true; setLoading(true); setError('');
    try { await work(); } catch (e) { if (alive.current) setError((e as Error).message); }
    finally { lock.current = false; if (alive.current) setLoading(false); }
  }
  useEffect(() => {
    alive.current = true; abort.current = new AbortController();
    if (playerToolsEnabled) void task(async () => { await list(); if (initialBotId && !restored.current) adopt(await submissionApi<BotDetail>('/' + encodeURIComponent(initialBotId), 'GET', undefined, abort.current.signal)); });
    return () => { alive.current = false; abort.current.abort(); };
  }, []);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  function adopt(detail: BotDetail | null) {
    if (!alive.current) return;
    setSelected(detail); setName(detail?.metadata.name ?? 'My Bot'); setSource(detail?.versions.at(-1)?.source ?? BOT_TEMPLATE); setViewVersion(0); setNotice('');
  }
  function discard() { return !dirty || window.confirm('Code chưa lưu. Bỏ thay đổi?'); }
  function requestId(key: string) {
    if (pending.current?.key !== key) pending.current = { key, requestId: crypto.randomUUID() };
    return pending.current.requestId;
  }
  async function save(duplicate = false) {
    const id = selected?.metadata.botId;
    const body = { name: duplicate ? name + ' copy' : name, source, ...(id && !duplicate ? { expectedVersion: selected.metadata.latestVersion } : {}) };
    const path = id && !duplicate ? '/' + id : '', method = path ? 'PUT' : 'POST';
    const key = JSON.stringify([path, method, body]);
    const detail = await submissionApi<BotDetail>(path, method, { ...body, requestId: requestId(key) }, abort.current.signal);
    pending.current = null; adopt(detail); await list();
  }
  async function remove() {
    if (!selected || !window.confirm('Xóa bot và mọi version khỏi danh sách?')) return;
    const path = '/' + selected.metadata.botId, expectedVersion = selected.metadata.latestVersion;
    await submissionApi(path, 'DELETE', { expectedVersion, requestId: requestId('DELETE:' + path + ':' + expectedVersion) }, abort.current.signal);
    pending.current = null; adopt(null); await list();
  }
  async function validate() {
    if (!selected) return;
    const path = '/' + selected.metadata.botId + '/validate', expectedVersion = selected.metadata.latestVersion;
    const result = await submissionApi<{ validationStatus: string; staticValidation: string; runtimeVerification: string; reason: string }>(
      path, 'POST', { expectedVersion, requestId: requestId(path + ':' + expectedVersion) }, abort.current.signal);
    pending.current = null;
    if (alive.current) setNotice(`${productLabel(result.validationStatus)} · Static: ${productLabel(result.staticValidation)} · Runtime: ${productLabel(result.runtimeVerification)}. ${result.reason}`);
  }
  if (!playerToolsEnabled) return <main className="bot-workspace"><h1>Bot Workspace chỉ dành cho local development</h1><a href="/">Multiplayer</a></main>;
  const historical = selected && viewVersion > 0 ? selected.versions.find(v => v.version === viewVersion) : undefined;
  return <main className="bot-workspace"><PageHeader eyebrow="DEVELOPER WORKSPACE" title="Bot Editor"  actions={(loading || dirty) ? <Badge>{loading ? 'Loading…' : 'Chưa lưu'}</Badge> : undefined} />
    {error && <ErrorState>{error} Nếu version đã đổi, tải lại bot trước khi save.</ErrorState>}
    {notice && <Toast>{notice}</Toast>}
    <div className="workspace-layout"><aside><button disabled={loading} onClick={() => { if (discard()) adopt(null); }}>Create Bot</button>
      <button disabled={loading} onClick={() => void task(list)}>Refresh</button>
      <ul>{bots.map(bot => <li key={bot.botId}><button disabled={loading} onClick={() => {
        if (discard()) void task(async () => adopt(await submissionApi<BotDetail>('/' + bot.botId, 'GET', undefined, abort.current.signal)));
      }}>{bot.name} · v{bot.latestVersion}<small>{productLabel(bot.executionEligibility)}</small></button></li>)}</ul></aside>
      <section><label>Bot name<input value={name} maxLength={80} disabled={loading || !!historical} onChange={e => setName(e.target.value)} /></label>
        <div className="workspace-actions"><button disabled={loading || !!historical || !name.trim() || sourceBytes > 32768} onClick={() => void task(() => save())}>Save Bot</button>
          <button disabled={loading || !selected || !!historical || sourceBytes > 32768} onClick={() => void task(() => save(true))}>Duplicate Bot</button>
          <button disabled={loading || !selected} onClick={() => void task(remove)}>Delete Bot</button>
          <button disabled={loading || !selected || dirty || !!historical} onClick={() => void task(validate)}>Validate saved version</button></div>

        {selected && <><details><summary>Bot details</summary><p className="mono">ID: {selected.metadata.botId}</p><p className="source-hash">SHA-256: {historical?.sourceSha256 ?? selected.metadata.sourceSha256}</p>
          <p>{productLabel(selected.metadata.validationStatus)} · {productLabel(selected.metadata.executionEligibility)}</p></details>
          <label>Version<select value={viewVersion} disabled={loading} onChange={e => setViewVersion(Number(e.target.value))}><option value={0}>Latest draft</option>
            {selected.versions.map(v => <option value={v.version} key={v.version}>v{v.version} (read-only)</option>)}</select></label></>}
        <label>JavaScript source<textarea spellCheck={false} value={historical?.source ?? source} readOnly={!!historical} disabled={loading}
          onChange={e => setSource(e.target.value)} aria-label="JavaScript source" /></label>
        <p>{new TextEncoder().encode(historical?.source ?? source).length} / 32768 UTF-8 bytes{dirty ? ' · Có thay đổi chưa lưu. Bản nháp chỉ ở bộ nhớ tab; reload sẽ mất.' : ''}</p>
        {sourceBytes > 32768 && <ErrorState>Source vượt 32KiB. Không thể save hoặc giữ bản nháp vượt giới hạn; hãy sao chép ra file trước khi chuyển trang.</ErrorState>}
        {!draftCached && sourceBytes <= 32768 && <ErrorState>Bộ nhớ bản nháp đã đầy. Save hoặc sao chép source ra file trước khi chuyển trang.</ErrorState>}
        <p>{selected?.metadata.executionEligibility === 'EXECUTABLE' ? 'Ready to compete.' : 'Not ready to compete: lưu bot và kiểm tra khả năng thực thi.'} Source accepted không chứng minh cú pháp hợp lệ hoặc code an toàn. Code chưa lưu không được thi đấu.</p>
      </section></div>
    <details><summary>Bot API documentation — bot-1 / ott-board-9x9-v1</summary>
      <p>Hàm đồng bộ chooseMove(observation), mỗi lượt độc lập. Observation: playerId A/B, turn bắt đầu 1, boardSize 9, board, legalMoves, remainingTimeMs. Tọa độ row/col từ 0 đến 8.</p>
      <pre>{'legalMoves: [{ pieceId: "A-R1", to: { row: 1, col: 1 } }]\nBotAction: { pieceId: "A-R1", to: { row: 1, col: 1 } }'}</pre>
      <p>Chọn một legalMove và trả đúng pieceId/to; không extra fields, Promise, filesystem, network, subprocess, dynamic import hoặc thư viện ngoài. Browser chỉ sửa source; chỉ saved version đủ eligibility mới được gửi tới secure worker.</p>
    </details>
  </main>;
}
