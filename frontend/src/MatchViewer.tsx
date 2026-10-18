import { useEffect, useRef, useState } from 'react';
import { localApi } from './local-submissions';
import { OttBoard } from './BotArena';
import { PageHeader } from './ui';
import { productLabel } from './product-labels';
import { BotLink, replayValid, terminal, type Match } from './competitive';
export default function MatchViewer({ id }: { id: string }) {
  const [match, setMatch] = useState<Match | null>(null), [error, setError] = useState(''), [paused, setPaused] = useState(false), [retry, setRetry] = useState(0);
  const [cursor, setCursor] = useState(0), [playing, setPlaying] = useState(false), [speed, setSpeed] = useState(600); const latest = useRef<Match | null>(null);
  useEffect(() => { const abort = new AbortController(); let timer: ReturnType<typeof setTimeout>; const deadline = Date.now() + 600000; setError(''); setPaused(false);
    if (latest.current?.matchId !== id) { latest.current = null; setMatch(null); setCursor(0); setPlaying(false); }
    const load = async () => { try {
      const next = await localApi<Match>('matches/' + id, 'GET', undefined, abort.signal); if (abort.signal.aborted) return;
      if (next.snapshot && !replayValid(next.snapshot)) throw new Error('Replay integrity check failed.');
      const old = latest.current; if (old && (terminal(old) && !terminal(next) || (next.snapshot?.moves.length ?? 0) < (old.snapshot?.moves.length ?? 0))) throw new Error('Stale match state. Refresh to synchronize.');
      latest.current = next; setMatch(next); if (!terminal(next)) setCursor(next.snapshot?.moves.length ?? 0); else if (!old || !terminal(old)) { setCursor(0); setPlaying(false); }
      if (!terminal(next)) { if (Date.now() >= deadline) { setPaused(true); return; } timer = setTimeout(() => void load(), 1500); }
    } catch (e) { if (!abort.signal.aborted) { setError((e as Error).message); setPaused(true); } } };
    void load(); return () => { abort.abort(); clearTimeout(timer); };
  }, [id, retry]);
  const snapshot = match?.snapshot, completed = !!match && terminal(match), count = snapshot?.moves.length ?? 0;
  useEffect(() => { if (!playing || !completed || cursor >= count) { if (playing) setPlaying(false); return; } const timer = setTimeout(() => setCursor(n => n + 1), speed); return () => clearTimeout(timer); }, [playing, completed, cursor, count, speed]);
  const seek = (n: number) => { setPlaying(false); setCursor(n); };
  return <main className="match-viewer"><PageHeader title="Match" description={match ? `${match.kind} · OTT 9×9` : undefined} />
    {error && <p role="alert">{error}</p>}{(!match || paused) && <button onClick={() => setRetry(n => n + 1)}>Refresh</button>}
    {!match && !error && <p role="status">Loading…</p>}
    {match && <><div className="match-identities"><section><span>Side A</span><h2><BotLink bot={match.a} /></h2><p>v{match.a.version}{match.a.ownerDisplay ? ' · ' + match.a.ownerDisplay : ''}</p>{match.a.eloBefore !== null && <p>Elo {Math.round(match.a.eloBefore)}</p>}</section><section><span>Side B</span><h2><BotLink bot={match.b} /></h2><p>v{match.b.version}{match.b.ownerDisplay ? ' · ' + match.b.ownerDisplay : ''}</p>{match.b.eloBefore !== null && <p>Elo {Math.round(match.b.eloBefore)}</p>}</section></div>
      <p role="status">{completed ? 'Replay' : paused ? 'Updates paused' : snapshot ? 'Live' : 'Waiting for snapshot'} · {productLabel(match.status)}</p>
      {snapshot && <div className="spectator-layout"><section><OttBoard pieces={snapshot.frames[cursor] ?? snapshot.board} move={snapshot.moves[cursor - 1]} /><p>{completed ? `Move ${cursor}/${count}` : `Turn ${snapshot.turn} · Side ${snapshot.currentSide}`}</p>
        {completed && count > 0 && <><div className="replay-controls"><button disabled={cursor === 0} onClick={() => seek(0)}>First</button><button disabled={cursor === 0} onClick={() => seek(cursor - 1)}>Previous</button><button onClick={() => { if (cursor === count) setCursor(0); setPlaying(p => !p); }}>{playing ? 'Pause' : 'Play'}</button><button disabled={cursor === count} onClick={() => seek(cursor + 1)}>Next</button><button disabled={cursor === count} onClick={() => seek(count)}>Last</button><label>Speed<select aria-label="Playback speed" value={speed} onChange={e => setSpeed(Number(e.target.value))}><option value={1000}>Slow</option><option value={600}>Normal</option><option value={200}>Fast</option></select></label></div><input aria-label="Replay move" type="range" min={0} max={count} value={cursor} onChange={e => seek(Number(e.target.value))} /></>}
      </section>{count > 0 && <aside><h2>Moves</h2><ol className="battle-log">{snapshot.moves.map(m => <li key={m.number}><button disabled={!completed} aria-current={m.number === cursor ? 'step' : undefined} onClick={() => seek(m.number)}>#{m.number} · Side {m.side} · {m.pieceId}{m.captured ? ' · Capture ' + m.captured.id : ''}</button></li>)}</ol></aside>}</div>}
      {snapshot?.result && <section className="ui-card"><h2>{productLabel(snapshot.result.outcome)}{snapshot.result.winner ? ` · Side ${snapshot.result.winner}` : ''}</h2><p>{productLabel(snapshot.result.reason)}</p>{match.ratingChanges.map(r => <p key={r.botId}>{r.botId === match.a.botId ? match.a.name : match.b.name} · Confirmed Elo {r.before.toFixed(1)} → {r.after.toFixed(1)} ({r.after - r.before >= 0 ? '+' : ''}{(r.after - r.before).toFixed(1)})</p>)}</section>}
      <details><summary>Details</summary><p>{match.gameMode} · {match.rulesVersion}</p><p>{new Date(match.date).toLocaleString()}</p></details>
    </>}
  </main>;
}
