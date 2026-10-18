import { useEffect, useState } from 'react';
import { localApi } from './local-submissions';
import { PageHeader } from './ui';
import { Link } from './navigation';
import { RatingChart, type Match } from './competitive';
import { MatchRows } from './CompetitiveHistory';
type Profile = { name: string; ownerDisplay: string; canEdit: boolean; elo: number; glicko2: number; rd: number; volatility: number; games: number; wins: number; draws: number; losses: number; provisional: boolean; gameMode: string; rulesVersion: string; history: { matchId: string; eloBefore: number; eloAfter: number; score: number }[]; recentMatches: Match[] };
export default function BotProfile({ id }: { id: string }) {
  const [profile, setProfile] = useState<Profile | null>(null), [error, setError] = useState('');
  useEffect(() => { const abort = new AbortController(); setProfile(null); setError(''); void localApi<Profile>('bot-profiles/' + id, 'GET', undefined, abort.signal).then(d => { if (!abort.signal.aborted) setProfile(d); }).catch(e => { if (!abort.signal.aborted) setError(e.message); }); return () => abort.abort(); }, [id]);
  return <main><PageHeader title={profile?.name ?? 'Bot Profile'} actions={profile?.canEdit ? <Link className="ui-button primary" href={'/bot-workspace?bot=' + encodeURIComponent(id)}>Edit Bot</Link> : undefined} />
    {error && <p role="alert">{error}</p>}{!profile && !error && <p role="status">Loading…</p>}
    {profile && <><p>By {profile.ownerDisplay} · OTT 9×9{profile.provisional ? ' · Provisional' : ''}</p><div className="stats-grid"><section className="ui-card"><span>Elo</span><h2>{Math.round(profile.elo)}</h2></section><section className="ui-card"><span>W / D / L</span><h2>{profile.wins} / {profile.draws} / {profile.losses}</h2></section><section className="ui-card"><span>Ranked games</span><h2>{profile.games}</h2></section></div>
      {!!profile.history.length && <section className="ui-card"><h2>Rating history</h2><RatingChart history={profile.history} /><details><summary>Match breakdown</summary>{profile.history.map(e => <p key={e.matchId}>{e.score === 1 ? 'Win' : e.score === .5 ? 'Draw' : 'Loss'} · {e.eloBefore.toFixed(1)} → {e.eloAfter.toFixed(1)}</p>)}</details></section>}
      {!!profile.recentMatches.length && <section><h2>Recent matches you can view</h2><MatchRows matches={profile.recentMatches} /></section>}
      <details><summary>Advanced</summary><p>Glicko-2 {Math.round(profile.glicko2)} · RD {profile.rd.toFixed(1)} · Volatility {profile.volatility.toFixed(5)}</p><p>{profile.gameMode} · {profile.rulesVersion}</p></details>
    </>}
  </main>;
}
