import { useEffect, useState } from 'react';
import type { Match } from './competitive';
import { productLabel } from './product-labels';
import { UserMenu } from './layouts';
import { Link } from './navigation';
import { localApi, submissionApi, type BotMetadata } from './local-submissions';
import { useSession } from './session';
import { Badge, Card, EmptyState, ErrorState, LoadingState, PageHeader, StatCard, Table } from './ui';
type TournamentSummary = {
    tournamentId: string;
    ownerId: string;
    config: {
        name: string;
        format: string;
    };
    status: string;
    entries: {
        ownerId: string;
    }[];
};
type MatchSummary = {
    id: string;
    botA: string;
    botB: string;
    status: string;
    result: {
        outcome: string;
        reason: string;
    } | null;
};
export function Home() { return <main className="home"><div className="hero"><div><h1>Write the bot.<br /><span>Own the next move.</span></h1><p className="hero-copy">Biến thuật toán thành đối thủ trên bàn cờ. Viết JavaScript, quản lý từng phiên bản và xem những nước đi được server xác thực.</p><div className="action-row"><Link className="ui-button primary" href="/dashboard">Open player console ↗</Link><Link className="ui-button secondary" href="/games">Explore the games</Link></div></div><div className="hero-code" aria-label="Bot contract example"><div className="code-bar"><span>● ● ●</span> chooseMove.js <Badge>bot-1</Badge></div><pre>{'// Your strategy starts here\nfunction chooseMove(observation) {\n  const move = observation.legalMoves[0];\n\n  return {\n    pieceId: move.pieceId,\n    to: move.to\n  };\n}'}</pre><div className="code-footer"><span className="online-dot"/> OTT BOARD · 9 × 9</div></div></div><div className="feature-grid"><Card><span className="feature-number">01 / DEVELOP</span><h2>Your bot, versioned.</h2><p>Viết chiến lược và lưu phiên bản bot của bạn.</p><Link href="/bot-workspace">Enter workspace →</Link></Card><Card><span className="feature-number">02 / BATTLE</span><h2>Watch every decision.</h2><p>Xem trận đấu và khám phá từng nước đi bằng replay.</p><Link href="/bot-arena">Open arena →</Link></Card><Card><span className="feature-number">03 / COMPETE</span><h2>Build a tournament.</h2><p>Tạo hoặc tham gia giải Round Robin và Single Elimination.</p><Link href="/tournaments">Browse tournaments →</Link></Card></div></main>; }
export function Games() { return <main><PageHeader eyebrow="GAMES & RULES" title="Choose your battleground" /><div className="feature-grid"><Card><Badge>BOT ARENA · bot-1</Badge><h2>OTT Board 9 × 9</h2><p>Hai side A/B, mỗi side khởi đầu với 6 quân ROCK / PAPER / SCISSORS. Bot chọn một legalMove trong observation; tọa độ row/col từ 0 đến 8.</p><p>ROCK thắng SCISSORS, SCISSORS thắng PAPER, PAPER thắng ROCK. Kết quả bắt quân và trận đấu do luật OTT trên server quyết định.</p><Link href="/bot-arena">Enter arena →</Link></Card><Card><Badge>MULTIPLAYER</Badge><h2>Rock Paper Scissors</h2><p>Kết nối WebSocket, tạo hoặc tham gia phòng và gửi lựa chọn. Server bảo vệ lựa chọn chưa công bố.</p><Link href="/multiplayer">Open multiplayer →</Link></Card><Card><Badge>MULTIPLAYER</Badge><h2>Number Guessing</h2><p>Gửi số đoán hợp lệ. Server giữ số bí mật và xác thực mỗi action.</p><Link href="/multiplayer">Open multiplayer →</Link></Card></div></main>; }
export function Documentation() { return <main><PageHeader eyebrow="DEVELOPER GUIDE" title="From source to a match" description="Contract hiện tại và giới hạn của local player tools."/><div className="docs-grid"><Card><h2>01 · Create & save</h2><p>Mỗi Save tạo version bất biến; source tối đa 32KiB UTF-8. SHA-256 nhận diện source bytes, không chứng minh code an toàn.</p><h2>02 · Validate</h2><p>Source accepted, static validation và runtime verification là các trạng thái riêng. Chỉ thi đấu khi backend xác nhận EXECUTABLE và secure worker sẵn sàng.</p><h2>03 · Battle & replay</h2><p>Arena dùng version/hash đã chọn. Replay đọc frames và MoveEvent từ backend.</p><h2>04 · Tournament</h2><p>Đăng ký version đã lưu. Organizer điều khiển lifecycle theo state backend; thứ hạng và champion lấy từ server.</p></Card><Card><h2>JavaScript contract</h2><Badge>bot-1 / ott-board-9x9-v1</Badge><pre>{'function chooseMove(observation) {\n  const { pieceId, to } = observation.legalMoves[0];\n  return { pieceId, to };\n}'}</pre><p>Hàm đồng bộ; execution độc lập mỗi lượt. Không Promise, filesystem, network, subprocess, dynamic import hoặc thư viện ngoài.</p><h3>Observation</h3><p>playerId, turn, boardSize, board, legalMoves, remainingTimeMs.</p><h3>Local persistence</h3><p>Saved sources và lịch sử giải đấu lưu ở PostgreSQL. Bản nháp chỉ ở bộ nhớ tab; reload sẽ mất. Player tools chưa expose trên production.</p></Card></div></main>; }
export function Account() { const session = useSession(); return <main><PageHeader title="Account"/><Card><label>Username</label><h2>{session?.user?.username}</h2><UserMenu account /><details><summary>Account details</summary><p className="mono">User ID: {session?.user?.id}</p></details></Card></main>; }
export function DataPage({ kind }: {
    kind: 'dashboard' | 'bots' | 'history';
}) {
    const [bots, setBots] = useState<BotMetadata[]>([]), [tournaments, setTournaments] = useState<TournamentSummary[]>([]), [matches, setMatches] = useState<MatchSummary[]>([]);
    const [loading, setLoading] = useState(true), [errors, setErrors] = useState<string[]>([]), [available, setAvailable] = useState({ bots: false, tournaments: false, matches: false });
    const session = useSession();
    useEffect(() => {
        const abort = new AbortController();
        let active = true;
        const botJob = kind !== 'history' ? submissionApi<{
            submissions: BotMetadata[];
        }>('', 'GET', undefined, abort.signal) : Promise.resolve(null);
        const tournamentJob = kind === 'dashboard' ? localApi<{
            tournaments: TournamentSummary[];
        }>('tournaments', 'GET', undefined, abort.signal) : Promise.resolve(null);
        const matchJob = kind !== 'bots' ? localApi<{
            matches: Match[];
        }>('match-history', 'GET', undefined, abort.signal) : Promise.resolve(null);
        void Promise.allSettled([botJob, tournamentJob, matchJob]).then(([b, t, m]) => { if (!active)
            return; if (b.status === 'fulfilled' && b.value) {
            setBots(b.value.submissions);
            setAvailable(v => ({ ...v, bots: true }));
        } if (t.status === 'fulfilled' && t.value) {
            setTournaments(t.value.tournaments.filter(item => item.ownerId === session?.user?.id || item.entries.some(e => e.ownerId === session?.user?.id)));
            setAvailable(v => ({ ...v, tournaments: true }));
        } if (m.status === 'fulfilled' && m.value) {
            setMatches(m.value.matches.map(m => ({ id: m.matchId, botA: m.a.name, botB: m.b.name, status: m.status, result: m.snapshot?.result ?? null })));
            setAvailable(v => ({ ...v, matches: true }));
        } setErrors([b, t, m].filter(r => r.status === 'rejected').map(r => String((r as PromiseRejectedResult).reason.message))); setLoading(false); });
        return () => { active = false; abort.abort(); };
    }, [kind, session?.user?.id]);
    const dashboard = kind === 'dashboard';
    return <main><PageHeader eyebrow="PLAYER CONSOLE" title={dashboard ? `Welcome back, ${session?.user?.username ?? 'player'}` : kind === 'bots' ? 'My Bots' : 'Match History'} description={kind === 'history' ? 'Local arena history' : undefined} actions={<Link className="ui-button primary" href={kind === 'history' ? '/bot-arena' : '/bot-workspace'}>{kind === 'history' ? 'Open replay viewer' : 'New Bot'}</Link>}/>{loading ? <LoadingState /> : <>{errors.map((error, i) => <ErrorState key={i}>{error}</ErrorState>)}{dashboard && <><div className="stats-grid"><StatCard label="My saved bots" value={available.bots ? bots.length : 'Unavailable'}/><StatCard label="My tournaments" value={available.tournaments ? tournaments.length : 'Unavailable'} note="Organizing or participating"/><StatCard label="Recent matches" value={available.matches ? matches.length : 'Unavailable'} /></div><div className="action-row"><Link className="ui-button secondary" href="/bot-workspace">Edit strategy</Link><Link className="ui-button secondary" href="/bot-arena">Start a battle</Link><Link className="ui-button secondary" href="/my-tournaments">Manage tournaments</Link></div></>}{kind !== 'history' && <Card>{dashboard && <h2>My Bots</h2>}{available.bots && !bots.length ? <EmptyState title="Your first strategy starts here"><p>Create a bot and save a version.</p></EmptyState> : bots.map(bot => <div className="data-row" key={bot.botId}><div><Link href={'/bots/' + encodeURIComponent(bot.botId)}>{bot.name}</Link><small>Version {bot.latestVersion} · {productLabel(bot.executionEligibility)}</small></div><Link className="ui-button secondary" href={'/bot-workspace?bot=' + encodeURIComponent(bot.botId)}>Edit</Link><details><summary>Details</summary><p className="mono">ID: {bot.botId}</p><p className="source-hash">SHA-256: {bot.sourceSha256}</p><p>{productLabel(bot.validationStatus)}</p><p>{productLabel(bot.executionEligibility)}</p></details></div>)}</Card>}{dashboard && <Card><h2>My Tournaments</h2>{available.tournaments && !tournaments.length ? <EmptyState title="No tournaments yet"/> : tournaments.map(t => <div className="data-row" key={t.tournamentId}><Link href={'/tournaments/' + t.tournamentId}>{t.config.name}</Link><Badge>{productLabel(t.status)}</Badge></div>)}</Card>}{kind !== 'bots' && <Card><h2>Recent matches</h2>{available.matches && !matches.length ? <EmptyState title="No recorded matches"/> : <Table label="Local match history"><thead><tr><th scope="col">Battle</th><th scope="col">Status</th><th scope="col">Result</th><th scope="col">Replay</th></tr></thead><tbody>{matches.slice(dashboard ? -5 : -100).reverse().map(m => <tr key={m.id}><td>{m.botA} <span className="muted">vs</span> {m.botB}</td><td><Badge>{productLabel(m.status)}</Badge></td><td>{m.result ? productLabel(m.result.outcome) : 'Pending'}</td><td><Link href={'/matches/' + encodeURIComponent(m.id)}>View match</Link></td></tr>)}</tbody></Table>}</Card>}</>}</main>;
}
