import { productLabel } from './product-labels';
import { useEffect, useRef, useState } from 'react';
import './bot-arena.css';
import { localApi, type BotMetadata } from './local-submissions';
import { PageHeader } from './ui';

type Piece = { id: string; owner: string; type: string; position: { row: number; col: number } };
type Move = { number: number; side: string; pieceId: string; from: Piece['position']; to: Piece['position']; captured: Piece | null };
type Battle = { id: string; botA: string; botB: string; status: 'RUNNING' | 'FINISHED' | 'CANCELLED'; turn: number; currentSide: string; board: Piece[]; moves: Move[]; frames: Piece[][]; result: { outcome: string; winner: string | null; reason: string } | null };
type Phase = 'IDLE' | 'STARTING' | 'RUNNING' | 'FINISHED' | 'CANCELLED' | 'ERROR';
const symbols: Record<string, string> = { ROCK: '●', PAPER: '▤', SCISSORS: '✂' };
export function OttBoard({ pieces, move }: { pieces: Piece[]; move?: Move }) {
  return <div className="ott-board" role="grid" aria-label="OTT 9 × 9" aria-rowcount={9} aria-colcount={9}>{Array.from({ length: 9 }, (_, r) => <div role="row" className="ott-row" key={r}>{Array.from({ length: 9 }, (_, c) => {
    const i = r * 9 + c;
    const row = Math.floor(i / 9), col = i % 9;
    const piece = pieces.find(p => p.position.row === row && p.position.col === col);
    const marked = move && [move.from, move.to].some(p => p.row === row && p.col === col);
    return <div role="gridcell" key={i} className={`ott-cell ${marked ? 'last-move' : ''}`} aria-label={`${row + 1},${col + 1}${piece ? ' ' + piece.owner + ' ' + piece.type : ''}`}>
      {piece && <span className={`ott-piece side-${piece.owner}`} title={piece.id}><b>{symbols[piece.type]}</b><small>{piece.owner} · {piece.type}</small></span>}
    </div>;
  })}</div>)}</div>;
}
export default function BotArena({ initialBattleId }: { initialBattleId?: string }) {
  const [bots, setBots] = useState<string[]>([]), [initial, setInitial] = useState<Piece[]>([]);
  const [submissions, setSubmissions] = useState<BotMetadata[]>([]);
  const [execution, setExecution] = useState<{ executable: boolean; reason?: string }>({ executable: false });
  const [aVersion, setAVersion] = useState(1), [bVersion, setBVersion] = useState(1);
  const [a, setA] = useState('FirstLegalMoveBot'), [b, setB] = useState('CapturePriorityBot');
  const [phase, setPhase] = useState<Phase>('IDLE'), [error, setError] = useState('');
  const [battle, setBattle] = useState<Battle | null>(null), [cursor, setCursor] = useState(0);
  const [playing, setPlaying] = useState(false), [speed, setSpeed] = useState(400);
  const [history, setHistory] = useState<Pick<Battle, 'id' | 'botA' | 'botB' | 'status' | 'result'>[]>([]);
  const busy = useRef(false), alive = useRef(true), requests = useRef(new Set<AbortController>());
  const pollTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  async function api<T>(path: string, body?: object): Promise<T> {
    const abort = new AbortController(); requests.current.add(abort);
    try {
      return await localApi<T>(path, body ? 'POST' : 'GET', body, abort.signal);
    } finally { requests.current.delete(abort); }
  }
  useEffect(() => {
    alive.current = true;
    if (import.meta.env.DEV) void api<{ bots: string[]; initialBoard: Piece[]; submissions?: BotMetadata[]; execution?: { executable: boolean; reason?: string } }>('bots').then(data => {
      if (alive.current) { setBots(data.bots); setInitial(data.initialBoard); setSubmissions(data.submissions ?? []); setExecution(data.execution ?? { executable: false }); }
    }).catch(e => { if (alive.current) { setPhase('ERROR'); setError(String(e.message)); } });
    if (import.meta.env.DEV && initialBattleId) { busy.current = true; void poll(encodeURIComponent(initialBattleId), Date.now() + 600000); }
    return () => { alive.current = false; clearTimeout(pollTimer.current); requests.current.forEach(c => c.abort()); };
  }, []);
  function receive(next: Battle) {
    if (!alive.current) return;
    setBattle(next); setCursor(next.moves.length); setPhase(next.status);
    if (next.status !== 'RUNNING') busy.current = false;
  }
  async function poll(id: string, deadline: number) {
    try {
      if (Date.now() > deadline) throw new Error('Đã dừng theo dõi sau 10 phút. Trận backend không bị xử thua; hãy tải lại trạng thái.');
      const next = await api<Battle>('battles/' + id); receive(next);
      if (alive.current && next.status === 'RUNNING') pollTimer.current = setTimeout(() => void poll(id, deadline), 500);
    } catch (e) { if (alive.current) { busy.current = false; setPhase('ERROR'); setError((e as Error).message); } }
  }
  function selection(value: string, version: number) {
    if (bots.includes(value)) return value;
    const saved = submissions.find(bot => value === 'submission:' + bot.botId);
    const hash = saved?.versions?.find(v => v.version === version)?.sourceSha256
      ?? (version === saved?.latestVersion ? saved.sourceSha256 : undefined);
    if (!saved || saved.executionEligibility !== 'EXECUTABLE' || !execution.executable || !hash) return null;
    return { botId: saved.botId, version, sourceSha256: hash };
  }
  const chosenA = selection(a, aVersion), chosenB = selection(b, bVersion);
  function versions(value: string) {
    const saved = submissions.find(bot => value === 'submission:' + bot.botId);
    return saved?.versions ?? (saved ? [{ version: saved.latestVersion, sourceSha256: saved.sourceSha256 }] : []);
  }
  async function start() {
    if (busy.current) return;
    if (!chosenA || !chosenB) { setError('Not ready to compete: ' + (execution.reason ?? 'OS sandbox chưa được kiểm chứng.')); return; }
    busy.current = true; clearTimeout(pollTimer.current); setPlaying(false); setPhase('STARTING'); setError('');
    try {
      const next = await api<Battle>('battles', { botA: chosenA, botB: chosenB, requestId: crypto.randomUUID() });
      receive(next);
      if (alive.current && next.status === 'RUNNING') void poll(next.id, Date.now() + 600000);
    } catch (e) { if (alive.current) { busy.current = false; setPhase('ERROR'); setError((e as Error).message); } }
  }
  useEffect(() => {
    if (!playing || !battle || cursor >= battle.moves.length) { if (playing) setPlaying(false); return; }
    const timer = setTimeout(() => setCursor(n => n + 1), speed);
    return () => clearTimeout(timer);
  }, [playing, cursor, speed, battle]);
  if (!import.meta.env.DEV) return <main className="bot-arena"><h1>Bot Arena chỉ dành cho local development</h1><a href="/">Về Multiplayer</a></main>;
  const moves = battle?.moves ?? [], move = cursor > 0 ? moves[cursor - 1] : undefined;
  const pieces = battle?.frames[cursor] ?? initial;
  const running = phase === 'STARTING' || phase === 'RUNNING';
  return <main className="bot-arena">
    <PageHeader title="Bot Arena" actions={phase !== 'IDLE' ? <span role="status">{productLabel(phase)}</span> : undefined} />
    <section className="bot-select"><label>Bot A<select aria-label="Bot A" value={a} disabled={running} onChange={e => { setA(e.target.value); setAVersion(submissions.find(bot => 'submission:' + bot.botId === e.target.value)?.latestVersion ?? 1); }}>
      <optgroup label="Trusted fixtures">{bots.map(bot => <option key={bot}>{bot}</option>)}</optgroup><optgroup label="Saved user bots">{submissions.map(bot => <option key={bot.botId} value={'submission:' + bot.botId}>{bot.name} · v{bot.latestVersion} · {productLabel(bot.executionEligibility)}</option>)}</optgroup></select></label>
      {versions(a).length > 0 && <label>Version A<select aria-label="Version A" value={aVersion} disabled={running} onChange={e => setAVersion(Number(e.target.value))}>{versions(a).map(v => <option key={v.version} value={v.version}>v{v.version}</option>)}</select></label>}
      <span>VS</span><label>Bot B<select aria-label="Bot B" value={b} disabled={running} onChange={e => { setB(e.target.value); setBVersion(submissions.find(bot => 'submission:' + bot.botId === e.target.value)?.latestVersion ?? 1); }}>
      <optgroup label="Trusted fixtures">{bots.map(bot => <option key={bot}>{bot}</option>)}</optgroup><optgroup label="Saved user bots">{submissions.map(bot => <option key={bot.botId} value={'submission:' + bot.botId}>{bot.name} · v{bot.latestVersion} · {bot.executionEligibility === 'EXECUTABLE' ? 'EXECUTABLE' : 'NOT EXECUTABLE'}</option>)}</optgroup></select></label>
      {versions(b).length > 0 && <label>Version B<select aria-label="Version B" value={bVersion} disabled={running} onChange={e => setBVersion(Number(e.target.value))}>{versions(b).map(v => <option key={v.version} value={v.version}>v{v.version} · {v.sourceSha256.slice(0, 12)}</option>)}</select></label>}
      <button onClick={() => void start()} disabled={running || !chosenA || !chosenB}>Start Battle</button></section>
    {(!chosenA || !chosenB) && bots.length > 0 && <p>Not ready to compete: {execution.reason ?? 'OS sandbox chưa được kiểm chứng'}; chọn trusted fixtures để chạy trận thật.</p>}
    {error && <p role="alert">{error}</p>}
    {phase === 'ERROR' && battle && <button onClick={() => { busy.current = true; setError(''); setPhase('RUNNING'); void poll(battle.id, Date.now() + 600000); }}>Tải lại trạng thái</button>}
    <div className={`arena-layout ${!moves.length && !battle?.result ? 'board-only' : ''}`}><section><OttBoard pieces={pieces} move={move} />
      <p>● ROCK · ▤ PAPER · ✂ SCISSORS — <span className="legend-a">A</span> / <span className="legend-b">B</span></p>
      <p>{battle ? `Unranked · Turn ${battle.turn} · Side ${battle.currentSide} · Replay ${cursor}/${moves.length}` : 'Choose two bots to start an unranked battle.'}</p>
      {moves.length > 0 && <><div className="replay-controls"><button disabled={running || cursor === 0} onClick={() => { setPlaying(false); setCursor(0); }}>Reset</button>
        <button disabled={running || cursor === 0} onClick={() => { setPlaying(false); setCursor(n => n - 1); }}>Previous</button>
        <button disabled={running || !moves.length} onClick={() => { if (cursor === moves.length) setCursor(0); setPlaying(p => !p); }}>{playing ? 'Pause' : 'Play'}</button>
        <button disabled={running || cursor >= moves.length} onClick={() => { setPlaying(false); setCursor(n => n + 1); }}>Next</button>
        <label>Tốc độ<select value={speed} onChange={e => setSpeed(Number(e.target.value))}><option value={1000}>1×</option><option value={400}>2.5×</option><option value={100}>10×</option></select></label></div>
      <input aria-label="Replay move" type="range" min={0} max={moves.length} value={cursor} disabled={running} onChange={e => { setPlaying(false); setCursor(Number(e.target.value)); }} /></>}
    </section>{(moves.length > 0 || battle?.result) && <aside>{battle?.result && <section className="arena-result"><h2>{productLabel(battle.result.outcome)}{battle.result.winner ? ' · Side ' + battle.result.winner : ''}</h2><details><summary>Result details</summary><p>{productLabel(battle.result.reason)}</p></details></section>}
      {moves.slice(0, cursor).some(m => m.captured) && <section><h2>Captured pieces</h2><p>{moves.slice(0, cursor).filter(m => m.captured).map(m => m.captured!.id).join(' · ')}</p></section>}
      {moves.length > 0 && <><h2>Battle log</h2><ol className="battle-log">{moves.map(m => <li key={m.number} className={m.number === cursor ? 'selected' : ''}>
        <button disabled={running} onClick={() => { setPlaying(false); setCursor(m.number); }}>#{m.number} {m.side} · {m.pieceId} ({m.from.row + 1},{m.from.col + 1}) → ({m.to.row + 1},{m.to.col + 1}){m.captured ? ' × ' + m.captured.id : ''}</button>
      </li>)}</ol></>}</aside>}</div>
    <details className="arena-history"><summary>Previous battles</summary><section><button disabled={running} onClick={() => void api<{ battles: typeof history }>('battles').then(data => { if (alive.current) setHistory(data.battles); }).catch(e => { if (alive.current) setError(e.message); })}>Load history</button>
      {!history.length && <p>Chưa tải hoặc chưa có lịch sử.</p>}{history.map(item => <p key={item.id}>{item.botA} vs {item.botB} · {productLabel(item.status)} · {item.result ? productLabel(item.result.outcome) : 'Pending'} <button disabled={running} onClick={() => void api<Battle>('battles/' + item.id).then(next => { receive(next); setPlaying(false); if (next.status === 'RUNNING') { busy.current = true; void poll(next.id, Date.now() + 600000); } }).catch(e => { if (alive.current) setError(e.message); })}>View replay</button></p>)}</section></details>
  </main>;
}
