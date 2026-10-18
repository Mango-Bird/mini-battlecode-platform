import { useEffect, useState } from 'react';
import { localApi } from './local-submissions';
import { PageHeader } from './ui';
import { Link } from './navigation';
import { RatingChart } from './competitive';
type Row = { rank: number; botId: string; name: string; elo: number; glicko2: number; rd: number; volatility: number; wins: number; draws: number; losses: number; provisional: boolean };
type Event = { matchId: string; gameMode: string; rulesVersion: string; score: number; eloBefore: number; eloAfter: number; ratingPeriod: number };
type Period = { ratingPeriod: number; before: number; after: number; rd: number; volatility: number; games: number };
export default function Rankings() {
  const [rows, setRows] = useState<Row[]>([]), [advanced, setAdvanced] = useState(false), [busy, setBusy] = useState(true), [error, setError] = useState('');
  const [events, setEvents] = useState<Event[]>([]), [periods, setPeriods] = useState<Period[]>([]), [selected, setSelected] = useState('');
  const [search, setSearch] = useState(''), [provisional, setProvisional] = useState('All');
  const visible = rows.filter(r => r.name.toLowerCase().includes(search.toLowerCase()) && (provisional === 'All' || r.provisional === (provisional === 'Provisional')));
  useEffect(() => { const abort = new AbortController(); setBusy(true); setRows([]); setError('');
    void localApi<{ rankings: Row[] }>('rankings' + (advanced ? '/glicko2' : ''), 'GET', undefined, abort.signal).then(data => { if (!abort.signal.aborted) setRows(data.rankings); }).catch(e => { if (!abort.signal.aborted) setError(e.message); }).finally(() => { if (!abort.signal.aborted) setBusy(false); });
    return () => abort.abort();
  }, [advanced]);
  useEffect(() => { if (!selected) return; const abort = new AbortController(); setEvents([]); setPeriods([]);
    void localApi<{ events: Event[]; periods?: Period[] }>('rankings/' + selected + '/history', 'GET', undefined, abort.signal).then(data => { if (!abort.signal.aborted) { setEvents(data.events); setPeriods(data.periods ?? []); } }).catch(e => { if (!abort.signal.aborted) setError(e.message); });return () => abort.abort();
  }, [selected]);
  return <main><PageHeader title="Rankings" description="OTT 9×9" />
    <label>Search bots<input type="search" value={search} onChange={e => setSearch(e.target.value)} /></label><label>Status<select value={provisional} onChange={e => setProvisional(e.target.value)}><option>All</option><option>Provisional</option><option>Established</option></select></label>
    <label><input type="checkbox" checked={advanced} onChange={e => { setAdvanced(e.target.checked); setSelected(''); }} /> Advanced · Glicko-2</label>
    {error && <p role="alert">{error}</p>}{busy && <p role="status">Loading…</p>}
    {!busy && !error && !rows.length && <p>No ranked results yet.</p>}
    {!!visible.length && <div className="table-scroll"><table><thead><tr>{['Rank', 'Bot', advanced ? 'Glicko-2' : 'Elo', 'W / D / L', ...(advanced ? ['RD', 'Volatility', 'History'] : [])].map(h => <th key={h}>{h}</th>)}</tr></thead><tbody>{visible.map(r => <tr key={r.botId}><td>{r.rank}</td><td><Link href={'/bots/' + r.botId}>{r.name}</Link>{r.provisional && <small> · Provisional</small>}</td><td>{Math.round(advanced ? r.glicko2 : r.elo)}</td><td>{r.wins} / {r.draws} / {r.losses}</td>{advanced && <><td>{r.rd.toFixed(1)}</td><td>{r.volatility.toFixed(5)}</td><td><button onClick={() => setSelected(r.botId)}>Details</button></td></>}</tr>)}</tbody></table></div>}
    {!busy && !error && rows.length > 0 && !visible.length && <p>No matching bots.</p>}
    {selected && <section><h2>Rating history</h2><RatingChart history={events} />{!events.length && <p>No rating events.</p>}{events.map(e => <div className="ui-card" key={e.matchId}>{e.score === 1 ? 'Win' : e.score === .5 ? 'Draw' : 'Loss'} · Elo {e.eloBefore.toFixed(1)} → {e.eloAfter.toFixed(1)}<details><summary>Match details</summary><p>{e.gameMode} · {e.rulesVersion} · Period {e.ratingPeriod}</p><p>Match {e.matchId}</p></details></div>)}{!!periods.length && <details><summary>Glicko-2 periods</summary>{periods.map(p => <p key={p.ratingPeriod}>Period {p.ratingPeriod} · {p.before.toFixed(1)} → {p.after.toFixed(1)} · RD {p.rd.toFixed(1)} · Volatility {p.volatility.toFixed(5)} · {p.games} games</p>)}</details>}</section>}
  </main>;
}
