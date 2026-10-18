import { useEffect, useState } from 'react';
import { localApi } from './local-submissions';
import { PageHeader } from './ui';
import { Link } from './navigation';
import { productLabel } from './product-labels';
import { BotLink, type Match } from './competitive';
export function MatchRows({ matches }: { matches: Match[] }) {
  return <div className="table-scroll"><table><thead><tr><th>Match</th><th>Type</th><th>Result</th><th>Date</th><th>Replay</th></tr></thead><tbody>{matches.map(m => <tr key={m.matchId}><td><BotLink bot={m.a} /> vs <BotLink bot={m.b} /></td><td>{m.kind}</td><td>{m.snapshot?.result ? productLabel(m.snapshot.result.outcome) : productLabel(m.status)}</td><td>{new Date(m.date).toLocaleString()}</td><td><Link href={'/matches/' + m.matchId}>View match</Link>{m.ratingChanges.length > 0 && <details><summary>Elo changes</summary>{m.ratingChanges.map(r => <p key={r.botId}>{r.botId === m.a.botId ? m.a.name : m.b.name}: {r.before.toFixed(1)} → {r.after.toFixed(1)}</p>)}</details>}</td></tr>)}</tbody></table></div>;
}
export default function CompetitiveHistory() {
  const [matches, setMatches] = useState<Match[]>([]), [busy, setBusy] = useState(true), [error, setError] = useState(''), [kind, setKind] = useState('All');
  const [page, setPage] = useState(0);
  useEffect(() => { const abort = new AbortController();setBusy(true);setError(''); void localApi<{ matches: Match[] }>('match-history' + (page ? '?page=' + page : ''), 'GET', undefined, abort.signal).then(d => { if (!abort.signal.aborted) setMatches(d.matches); }).catch(e => { if (!abort.signal.aborted) setError(e.message); }).finally(() => { if (!abort.signal.aborted) setBusy(false); }); return () => abort.abort(); }, [page]);
  const visible = matches.filter(m => kind === 'All' || m.kind === kind);
  return <main><PageHeader title="Match History" /><label>Type on this page<select value={kind} onChange={e => setKind(e.target.value)}>{['All', 'Ranked', 'Unranked', 'Tournament'].map(k => <option key={k}>{k}</option>)}</select></label>{busy && <p role="status">Loading…</p>}{error && <p role="alert">{error}</p>}{!busy && !error && (!visible.length ? <p>No matches yet.</p> : <MatchRows matches={visible} />)}{(page>0||matches.length===50)&&<div className="action-row"><button disabled={busy||page===0} onClick={()=>setPage(p=>p-1)}>Previous page</button><span>Page {page+1}</span><button disabled={busy||matches.length<50||page>=200} onClick={()=>setPage(p=>p+1)}>Next page</button></div>}</main>;
}
