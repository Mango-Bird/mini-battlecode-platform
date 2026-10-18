import { useEffect, useRef, useState } from 'react';
import { localApi, type BotMetadata } from './local-submissions';
import { PageHeader } from './ui';
import { productLabel } from './product-labels';
import { Link } from './navigation';
type Ticket = { ticketId: string; botId: string; version: number; elo: number; status: string };
type Entry = { ticket: Ticket; ratingWindow: number; durationSeconds: number };
type Match = { matchId: string; gameNumber: number; status: string; snapshot: { turn: number; result: { outcome: string; winner: string | null; reason: string } | null } | null; ratingChanges: { botId: string; before: number; after: number }[] };
export default function Ranked() {
  const [bots, setBots] = useState<BotMetadata[]>([]), [bot, setBot] = useState(''), [entry, setEntry] = useState<Entry | null>(null), [matches, setMatches] = useState<Match[]>([]);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [searching, setSearching] = useState(false), [notice, setNotice] = useState('');
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const alive = useRef(true), inFlight = useRef(false);
  useEffect(() => { alive.current = true; const abort = new AbortController();
    void localApi<{ submissions: BotMetadata[] }>('submissions', 'GET', undefined, abort.signal).then(d => { if (!abort.signal.aborted) { setBots(d.submissions); setBot(previous => previous || d.submissions[0]?.botId || ''); } }).catch(e => { if (!abort.signal.aborted) setError(e.message); });
    void refresh(abort.signal).catch(e => { if (!abort.signal.aborted) setError(e.message); });return () => { alive.current = false; abort.abort(); };
  }, []);
  async function refresh(signal?: AbortSignal) {
    const [q, m] = await Promise.all([localApi<{ entry: Entry | null; ratings?: Record<string, number> }>('ranked/queue/me', 'GET', undefined, signal), localApi<{ matches: Match[] }>('ranked/matches', 'GET', undefined, signal)]);
    if (alive.current && !signal?.aborted) { setEntry(q.entry); if (q.entry && ['WAITING', 'RESERVED', 'RUNNING'].includes(q.entry.ticket.status)) setBot(q.entry.ticket.botId); setRatings(q.ratings ?? {}); setMatches(m.matches); setSearching(!!q.entry && ['WAITING', 'RESERVED', 'RUNNING'].includes(q.entry.ticket.status)); }
  }
  useEffect(() => { if (!searching) return; const abort = new AbortController(); let timer: ReturnType<typeof setTimeout>; const deadline = Date.now() + 10 * 60_000;
    const poll = async () => { if (Date.now() > deadline) { setSearching(false); setNotice('Updates paused. Refresh to check the server.'); return; }
      try { await refresh(abort.signal); } catch (e) { if (!abort.signal.aborted) { setError((e as Error).message); setSearching(false); } return; }
      if (!abort.signal.aborted) timer = setTimeout(() => void poll(), 1500);
    }; timer = setTimeout(() => void poll(), 1500);return () => { abort.abort(); clearTimeout(timer); };
  }, [searching]);
  async function mutate(cancel: boolean) {
    if (inFlight.current) return; const selected = bots.find(b => b.botId === bot); if (!cancel && !selected) return;
    inFlight.current = true; setBusy(true); setError(''); setNotice('');
    try { await localApi(cancel ? 'ranked/queue/me' : 'ranked/queue', cancel ? 'DELETE' : 'POST', cancel ? { requestId: crypto.randomUUID() } : { requestId: crypto.randomUUID(), botId: selected!.botId, version: selected!.latestVersion, sourceSha256: selected!.sourceSha256 }); await refresh(); }
    catch (e) { if (alive.current) setError((e as Error).message); } finally { inFlight.current = false; if (alive.current) setBusy(false); }
  }
  const active = entry && ['WAITING', 'RESERVED', 'RUNNING'].includes(entry.ticket.status), selected = bots.find(b => b.botId === bot);
  return <main><PageHeader title="Ranked" description="OTT 9×9 · Two games, swapped sides" />
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <label>Bot<select aria-label="Ranked bot" value={bot} disabled={!!active || busy} onChange={e => setBot(e.target.value)}>{bots.map(b => <option key={b.botId} value={b.botId}>{b.name} · v{b.latestVersion}</option>)}</select></label>
    {ratings[bot] !== undefined && <p>Current Elo {Math.round(ratings[bot])}</p>}
    {!active && <button disabled={busy || selected?.executionEligibility !== 'EXECUTABLE'} onClick={() => void mutate(false)}>Join Ranked Queue</button>}
    {selected && selected.executionEligibility !== 'EXECUTABLE' && <p>Bot is not ready for secure execution. Validate it in Bot Editor.</p>}
    {entry && <section className="ui-card"><p role="status">{entry.ticket.status === 'WAITING' ? 'Searching' : ['RESERVED', 'RUNNING'].includes(entry.ticket.status) ? 'Match Found' : productLabel(entry.ticket.status)}</p><p>Queue Elo {Math.round(entry.ticket.elo)} · Window ±{entry.ratingWindow} · {entry.durationSeconds}s</p>
      {entry.ticket.status === 'WAITING' && <button disabled={busy} onClick={() => void mutate(true)}>Cancel Search</button>}</section>}
    <button disabled={busy || searching} onClick={() => void refresh().catch(e => setError(e.message))}>Refresh</button>
    {!!matches.length && <section><h2>Match history</h2>{matches.map(m => <article className="ui-card" key={m.matchId}><h3>Ranked · Game {m.gameNumber}</h3><p>{productLabel(m.status)}{m.snapshot ? ` · Turn ${m.snapshot.turn}` : ''}</p>
      {m.snapshot?.result && <p>{productLabel(m.snapshot.result.outcome)}{m.snapshot.result.winner ? ' · Side ' + m.snapshot.result.winner : ''}</p>}
      {m.ratingChanges.filter(r => r.botId === entry?.ticket.botId).map(r => <p key={r.botId}>Confirmed Elo {r.before.toFixed(1)} → {r.after.toFixed(1)}</p>)}
      <Link href={'/matches/' + m.matchId}>{m.status === 'RUNNING' ? 'Watch match' : 'View replay'}</Link>
      {m.status === 'INTERRUPTED' && <p>Execution interrupted. No rating for the interrupted game.</p>}
    </article>)}</section>}
  </main>;
}
