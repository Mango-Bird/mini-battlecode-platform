import { productLabel } from './product-labels';
import { useEffect, useRef, useState } from 'react';
import { localApi, submissionApi, type BotMetadata } from './local-submissions';
import { OttBoard } from './BotArena';
import './tournaments.css';
import { PageHeader, Tabs, EmptyState } from './ui';
import { Link } from './navigation';

type Entry = { entryId: string; ownerId: string; ownerDisplay: string; botId: string; botName: string; version: number; sourceSha256: string; seedOrder: number };
type Match = { matchId: string; round: number; slot: number; attempt: number; entryA: string; entryB: string | null; status: string; battleId: string | null; winnerId: string | null; result: { outcome: string; reason: string } | null; interruptionReason?: string };
type Standing = { rank: number; entryId: string; botName: string; ownerDisplay: string; played: number; wins: number; draws: number; losses: number; forfeits: number; points: number };
type Tournament = { tournamentId: string; ownerId: string; config: { name: string; format: string; maxParticipants: number; seed: number; maxDrawRematches: number; registrationDeadline: string | null }; status: string;
  entries: Entry[]; rounds: { number: number; matches: Match[]; winners: string[] }[]; standings: Standing[]; result: { championId: string | null; reason: string } | null; schedulingReason: string };
type Piece = { id: string; owner: string; type: string; position: { row: number; col: number } };
type Move = { number: number; side: string; pieceId: string; from: Piece['position']; to: Piece['position']; captured: Piece | null };
type Replay = { status: string; board: Piece[]; frames: Piece[][]; moves: Move[]; result: { outcome: string; winner: string | null; reason: string } | null };

export function TournamentBracket({ tournament }: { tournament: Tournament }) {
  const name = (id: string | null) => tournament.entries.find(e => e.entryId === id)?.botName ?? 'BYE';
  return <div className="tournament-bracket" aria-label="Tournament bracket">{tournament.rounds.map(round => <section key={round.number}>
    <h3>Round {round.number}</h3>{round.matches.map(match => <div key={match.matchId} className="bracket-match">
      <b className={match.winnerId === match.entryA ? 'winner' : ''}>{name(match.entryA)}</b> vs <b className={match.winnerId === match.entryB && match.entryB ? 'winner' : ''}>{name(match.entryB)}</b>
      <p>{productLabel(match.status)} · attempt {match.attempt + 1}{match.result ? ' · ' + productLabel(match.result.outcome) : ''}</p>
    </div>)}</section>)}</div>;
}
export default function Tournaments({ mine = false, initialTournamentId }: { mine?: boolean; initialTournamentId?: string }) {
  const [creating, setCreating] = useState(false);
  const [canManage, setCanManage] = useState(false), [tab, setTab] = useState('overview');
  const [list, setList] = useState<Tournament[]>([]), [detail, setDetail] = useState<Tournament | null>(null);
  const [viewer, setViewer] = useState(''), [bots, setBots] = useState<BotMetadata[]>([]);
  const [name, setName] = useState('Local Tournament'), [format, setFormat] = useState('ROUND_ROBIN'), [capacity, setCapacity] = useState(4);
  const [seed, setSeed] = useState(42), [rematches, setRematches] = useState(1), [deadline, setDeadline] = useState('');
  const [bot, setBot] = useState(''), [version, setVersion] = useState(1);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [replay, setReplay] = useState<Replay | null>(null);
  const [selectedMatch, setSelectedMatch] = useState(''), [cursor, setCursor] = useState(0), [playing, setPlaying] = useState(false), [speed, setSpeed] = useState(400);
  const [monitorGeneration, setMonitorGeneration] = useState(0);
  const alive = useRef(true), lock = useRef(false), abort = useRef(new AbortController()), until = useRef(0);
  const mutation = useRef<{ fingerprint: string; requestId: string } | null>(null);
  async function refresh() {
    const data = await localApi<{ tournaments: Tournament[]; viewerOwnerId: string }>('tournaments', 'GET', undefined, abort.current.signal);
    if (alive.current) { setList(data.tournaments); setViewer(data.viewerOwnerId); }
  }
  async function task(work: () => Promise<void>) {
    if (lock.current) return; lock.current = true; setBusy(true); setError('');
    try { await work(); } catch (e) { if (alive.current) setError((e as Error).message); }
    finally { lock.current = false; if (alive.current) setBusy(false); }
  }
  useEffect(() => {
    alive.current = true; abort.current = new AbortController();
    if (import.meta.env.DEV) void task(async () => { await refresh(); const own = await submissionApi<{ submissions: BotMetadata[] }>('', 'GET', undefined, abort.current.signal); if (alive.current) setBots(own.submissions); if (initialTournamentId) await select(encodeURIComponent(initialTournamentId)); });
    return () => { alive.current = false; abort.current.abort(); };
  }, []);
  async function select(id: string) {
    setCanManage(false);
    const data = await localApi<{ tournament: Tournament; viewerOwnerId: string; canManage: boolean }>('tournaments/' + id, 'GET', undefined, abort.current.signal);
    if (alive.current) { setDetail(data.tournament); setViewer(data.viewerOwnerId); setCanManage(data.canManage === true); }
  }
  async function mutate(path: string, fields: object) {
    const fingerprint = JSON.stringify([path, fields]);
    if (mutation.current?.fingerprint !== fingerprint) mutation.current = { fingerprint, requestId: crypto.randomUUID() };
    const next = await localApi<Tournament>(path, 'POST', { ...fields, requestId: mutation.current.requestId }, abort.current.signal);
    mutation.current = null;
    if (alive.current) { setDetail(next); setCanManage(false); setReplay(null); setSelectedMatch(''); until.current = Date.now() + 15 * 60000; }
    await refresh();
    await select(next.tournamentId);
  }
  useEffect(() => {
    if (!detail || !['RUNNING', 'REGISTRATION_OPEN'].includes(detail.status)) return;
    if (!until.current) until.current = Date.now() + 15 * 60000;
    let stopped = false; let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (Date.now() > until.current) { setError('Đã dừng polling sau 15 phút; Refresh để tiếp tục theo dõi. Backend không bị xử thua.'); return; }
      try {
        const data = await localApi<{ tournament: Tournament; canManage: boolean; viewerOwnerId: string }>('tournaments/' + detail!.tournamentId, 'GET', undefined, abort.current.signal);
        if (!stopped && alive.current) { setDetail(data.tournament); setCanManage(data.canManage === true); setViewer(data.viewerOwnerId); if (['RUNNING', 'REGISTRATION_OPEN'].includes(data.tournament.status)) timer = setTimeout(() => void poll(), 1000); }
      } catch (e) { if (!stopped && alive.current) setError((e as Error).message); }
    }
    timer = setTimeout(() => void poll(), 1000);
    return () => { stopped = true; clearTimeout(timer); };
  }, [detail?.tournamentId, detail?.status, monitorGeneration]);
  useEffect(() => {
    if (!selectedMatch || !detail || replay?.status !== 'RUNNING') return;
    let stopped = false; let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (Date.now() > until.current) return;
      try { const next = await localApi<Replay>(`tournaments/${detail!.tournamentId}/matches/${selectedMatch}`, 'GET', undefined, abort.current.signal);
        if (!stopped && alive.current) { setReplay(next); setCursor(next.moves.length); if (next.status === 'RUNNING') timer = setTimeout(() => void poll(), 1000); }
      } catch (e) { if (!stopped && alive.current) setError((e as Error).message); }
    }
    timer = setTimeout(() => void poll(), 1000); return () => { stopped = true; clearTimeout(timer); };
  }, [selectedMatch, replay?.status]);
  useEffect(() => { if (!playing || !replay || cursor >= replay.moves.length) { if (playing) setPlaying(false); return; } const timer = setTimeout(() => setCursor(c => c + 1), speed); return () => clearTimeout(timer); }, [playing, cursor, speed, replay]);
  if (!import.meta.env.DEV) return <main><h1>Tournaments chỉ dành cho local development</h1><a href="/">Multiplayer</a></main>;
  const chosen = bots.find(b => b.botId === bot), registered = detail?.entries.some(e => e.ownerId === viewer);
  const versions = chosen?.versions ?? (chosen ? [{ version: chosen.latestVersion, sourceSha256: chosen.sourceSha256 }] : []);
  const hash = versions.find(v => v.version === version)?.sourceSha256;
  const admin = canManage && !!detail && detail.ownerId === viewer, running = replay?.status === 'RUNNING';
  const visibleList = mine ? list.filter(t => t.ownerId === viewer || t.entries.some(e => e.ownerId === viewer)) : list;
  return <main className="tournaments"><PageHeader title={initialTournamentId && detail ? detail.config.name : mine ? 'My Tournaments' : 'Tournaments'} actions={!initialTournamentId ? <button className="primary" disabled={busy} onClick={() => setCreating(true)}>Create Tournament</button> : undefined} />
    {busy && <p role="status">Loading…</p>}
    {error && <p role="alert">{error}</p>}
    {!initialTournamentId && <details className="create-tournament" open={creating} onToggle={e => setCreating(e.currentTarget.open)}><summary>Tournament settings</summary><section><label>Name<input value={name} onChange={e => setName(e.target.value)} maxLength={80} /></label>
      <label>Format<select value={format} onChange={e => setFormat(e.target.value)}><option value="ROUND_ROBIN">Round Robin</option><option value="SINGLE_ELIMINATION">Single Elimination</option></select></label>
      <label>Max participants<input type="number" min={2} max={16} value={capacity} onChange={e => setCapacity(Number(e.target.value))} /></label>
      <details><summary>Advanced settings</summary><label>Seed<input type="number" value={seed} onChange={e => setSeed(Number(e.target.value))} /></label>
      <label>Draw rematches<select value={rematches} onChange={e => setRematches(Number(e.target.value))}>{[0, 1, 2].map(n => <option key={n}>{n}</option>)}</select></label></details>
      <label>Registration deadline<input type="datetime-local" value={deadline} onChange={e => setDeadline(e.target.value)} /></label>
      <button className="primary" disabled={busy || !name.trim()} onClick={() => void task(async () => { await mutate('tournaments', { name, format, maxParticipants: capacity, seed, maxDrawRematches: rematches, registrationDeadline: deadline ? new Date(deadline).toISOString() : null }); setCreating(false); })}>Create</button>
    </section></details>}<section><button disabled={busy} onClick={() => void task(async () => { until.current = Date.now() + 15 * 60000; setMonitorGeneration(n => n + 1); await refresh(); if (detail) await select(detail.tournamentId); })}>Refresh</button>
      {!initialTournamentId && <>{!visibleList.length && <EmptyState title="Chưa có giải đấu." />}<ul>{visibleList.map(t => <li key={t.tournamentId}><button disabled={busy} onClick={() => void task(async () => { until.current = Date.now() + 15 * 60000; setReplay(null); setSelectedMatch(''); setTab('overview'); await select(t.tournamentId); })}>{t.config.name} · {productLabel(t.config.format)} · {productLabel(t.status)}</button><small>{t.entries.length}/{t.config.maxParticipants} participants</small><Link href={'/tournaments/' + t.tournamentId}>View / Join →</Link></li>)}</ul></>}
    </section>{detail && <section>{!initialTournamentId && <h2>{detail.config.name}</h2>}<p>{productLabel(detail.status)} · {detail.entries.length}/{detail.config.maxParticipants} participants</p><details><summary>Tournament details</summary><p>Seed {detail.config.seed} · {detail.config.maxDrawRematches} draw rematches; nếu vẫn hòa, seed order thấp hơn đi tiếp.</p></details>
      <Tabs value={tab} onChange={setTab} items={[{ id: 'overview', label: 'Overview' }, { id: 'participants', label: 'Participants' }, { id: 'schedule', label: 'Schedule / Bracket' }, { id: 'standings', label: 'Standings' }, { id: 'matches', label: 'Match Results' }]} />
      <div role="tabpanel" id="panel-overview" aria-labelledby="tab-overview" hidden={tab !== 'overview'}>
      {admin && <div className="organizer-panel">{['open', 'close', 'start', 'cancel'].map(op => <button key={op} disabled={busy || (op === 'open' ? detail.status !== 'CREATED' : op === 'close' ? detail.status !== 'REGISTRATION_OPEN' : op === 'start' ? detail.status !== 'REGISTRATION_CLOSED' : ['FINISHED', 'CANCELLED'].includes(detail.status))} onClick={() => void task(() => mutate(`tournaments/${detail.tournamentId}/${op}`, {}))}>{({open: 'Open registration', close: 'Close registration', start: 'Start Tournament', cancel: 'Cancel Tournament'}[op])}</button>)}</div>}
      <h3>Join tournament</h3><select aria-label="Register bot" value={bot} onChange={e => { setBot(e.target.value); setVersion(bots.find(b => b.botId === e.target.value)?.latestVersion ?? 1); }}><option value="">Choose own bot</option>{bots.map(b => <option key={b.botId} value={b.botId}>{b.name} · {productLabel(b.executionEligibility)}</option>)}</select>
      <select aria-label="Register version" value={version} onChange={e => setVersion(Number(e.target.value))}>{versions.map(v => <option key={v.version} value={v.version}>v{v.version}</option>)}</select>
      <button disabled={busy || registered || detail.status !== 'REGISTRATION_OPEN' || chosen?.executionEligibility !== 'EXECUTABLE' || !hash} onClick={() => void task(() => mutate(`tournaments/${detail.tournamentId}/register`, { botId: bot, version, sourceSha256: hash }))}>Register</button>
      <button disabled={busy || !registered || detail.status !== 'REGISTRATION_OPEN'} onClick={() => void task(() => mutate(`tournaments/${detail.tournamentId}/withdraw`, {}))}>Withdraw</button>
      {detail.result && <><h3>Champion: {detail.entries.find(e => e.entryId === detail.result!.championId)?.botName ?? 'None'}</h3><details><summary>Result details</summary><p>{productLabel(detail.result.reason)}</p></details></>}
      </div><div role="tabpanel" id="panel-participants" aria-labelledby="tab-participants" hidden={tab !== 'participants'}><h3>Participants · {detail.entries.length}/{detail.config.maxParticipants}</h3><ul>{detail.entries.map(e => <li key={e.entryId}>{e.botName} · v{e.version} · {e.ownerDisplay} · seed #{e.seedOrder + 1}<details><summary>Version details</summary><small className="source-hash">{e.sourceSha256}</small></details></li>)}</ul></div>
      <div role="tabpanel" id="panel-schedule" aria-labelledby="tab-schedule" hidden={tab !== 'schedule'}><h3>Schedule / Bracket</h3><p>{detail.schedulingReason}</p>
      {detail.config.format === 'SINGLE_ELIMINATION' && <TournamentBracket tournament={detail} />}
      {detail.config.format === 'ROUND_ROBIN' && detail.rounds.map(round => <p key={round.number}>Round {round.number} · {round.matches.length} matches</p>)}</div>
      <div role="tabpanel" id="panel-standings" aria-labelledby="tab-standings" hidden={tab !== 'standings'}>
      <h3>Standings</h3><details><summary>Scoring & tie-break</summary><p>Win 3 · Draw 1 · Loss 0. Round Robin plays both sides. Ties: points, wins, head-to-head points, then seed order. BYEs do not count as ranked wins.</p></details><table><thead><tr>{['Rank', 'Bot', 'Owner', 'Played', 'Wins', 'Draws', 'Losses', 'Forfeits', 'Points'].map(h => <th key={h}>{h}</th>)}</tr></thead><tbody>{detail.standings.map(s => <tr key={s.entryId}><td>{s.rank}</td><td>{s.botName}</td><td>{s.ownerDisplay}</td><td>{s.played}</td><td>{s.wins}</td><td>{s.draws}</td><td>{s.losses}</td><td>{s.forfeits}</td><td>{s.points}</td></tr>)}</tbody></table>
      </div><div role="tabpanel" id="panel-matches" aria-labelledby="tab-matches" hidden={tab !== 'matches'}><h3>Matches</h3>{detail.rounds.map(r => <div key={r.number}><h4>Round {r.number}</h4>{r.matches.map(m => <p key={m.matchId}>{detail.entries.find(e => e.entryId === m.entryA)?.botName} vs {detail.entries.find(e => e.entryId === m.entryB)?.botName ?? 'BYE'} · {productLabel(m.status)} · {m.result ? productLabel(m.result.outcome) : ''} · {m.interruptionReason}
        <button disabled={busy || !m.battleId || !detail.entries.some(e => e.ownerId === viewer && (e.entryId === m.entryA || e.entryId === m.entryB))} onClick={() => void task(async () => { const next = await localApi<Replay>(`tournaments/${detail.tournamentId}/matches/${m.matchId}`, 'GET', undefined, abort.current.signal); if (alive.current) { setSelectedMatch(m.matchId); setReplay(next); setCursor(next.moves.length); setPlaying(false); } })}>View match</button></p>)}</div>)}
      {replay && <section><h3>Match Viewer · {productLabel(replay.status)} · {replay.result ? productLabel(replay.result.outcome) : ''}</h3><OttBoard pieces={replay.frames[cursor] ?? replay.board} move={cursor ? replay.moves[cursor - 1] : undefined} />
        <p>{replay.result?.reason} · Replay {cursor}/{replay.moves.length}</p><button disabled={running} onClick={() => { setPlaying(false); setCursor(0); }}>Reset</button><button disabled={running || cursor === 0} onClick={() => { setPlaying(false); setCursor(n => n - 1); }}>Previous</button>
        <button disabled={running || !replay.moves.length} onClick={() => { if (cursor === replay.moves.length) setCursor(0); setPlaying(p => !p); }}>{playing ? 'Pause' : 'Play'}</button><button disabled={running || cursor === replay.moves.length} onClick={() => { setPlaying(false); setCursor(n => n + 1); }}>Next</button>
        <select aria-label="Replay speed" value={speed} onChange={e => setSpeed(Number(e.target.value))}><option value={1000}>1×</option><option value={400}>2.5×</option><option value={100}>10×</option></select>
        <ol>{replay.moves.map(m => <li key={m.number}>#{m.number} {m.side} {m.pieceId} → ({m.to.row},{m.to.col}) {m.captured ? 'captures ' + m.captured.id : ''}</li>)}</ol></section>}
      </div></section>}</main>;
}
