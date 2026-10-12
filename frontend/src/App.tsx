import { useEffect, useReducer, useRef, useState } from 'react';
import { initial, receive, type Message, type Payload, type State } from './model';
import { Connection } from './transport';
const labels: Record<string, string> = { 'rock-paper-scissors': 'Rock · Paper · Scissors', 'number-guessing': 'Number Guessing', 'room-only-no-gameplay': 'Phòng thử nghiệm TCP' };
type ListedRoom = { id: string; ruleId: string; status: string; players: string; maxPlayers: string };
export default function App() {
  const [state, dispatch] = useReducer(receive, initial);
  const stateRef = useRef<State>(state); stateRef.current = state;
  const connection = useRef<Connection | null>(null);
  const actionLock = useRef<string | null>(null);
  const [nickname, setNickname] = useState(''); const [status, setStatus] = useState('offline');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const [rooms, setRooms] = useState<ListedRoom[]>([]); const [rules, setRules] = useState<string[]>([]);
  const [rule, setRule] = useState('rock-paper-scissors'); const [rounds, setRounds] = useState(3);
  const [min, setMin] = useState(1); const [max, setMax] = useState(100); const [guess, setGuess] = useState('');
  const [submitted, setSubmitted] = useState<string | null>(null); const [showResult, setShowResult] = useState(true);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 250); return () => { clearInterval(timer); connection.current?.close(); }; }, []);
  const request = (command: string, payload: Payload = {}) => connection.current ? connection.current.request(command, payload) : Promise.reject(new Error('Chưa kết nối.'));
  async function task(work: () => Promise<unknown>) { if (busy) return; setBusy(true); setError(''); try { await work(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
  async function list(command: string, prefix: string) {
    const items: Payload[] = []; let offset = '0';
    for (let page = 0; page < 100; page++) {
      const p = (await request(command, { offset, limit: '10' })).payload!;
      for (let i = 0; i < Number(p.count); i++) { const item: Payload = {}; Object.entries(p).forEach(([key, value]) => { const base = `${prefix}.${i}.`; if (key.startsWith(base)) item[key.slice(base.length)] = value; }); items.push(item); }
      if (p.nextOffset === '-1') return items;
      if (Number(p.nextOffset) <= Number(offset)) throw new Error('Pagination server không hợp lệ.');
      offset = p.nextOffset;
    }
    throw new Error('Danh sách quá lớn; hãy tải lại.');
  }
  async function refresh() { const r = await list('LIST_RULES', 'rule'); setRules(r.map(x => x.id)); setRooms(await list('LIST_ROOMS', 'room') as ListedRoom[]); }
  async function connect() {
    setStatus('connecting');
    const onMessage = (message: Message) => {
      dispatch(message);
      if (message.command === 'MATCH_EVENT' && ['FINISHED', 'CANCELLED'].includes(message.payload?.type ?? '')) setShowResult(true);
    };
    let c: Connection;
    try { c = new Connection(import.meta.env.VITE_WS_URL, onMessage, reason => { setStatus('offline'); setError(reason); }); }
    catch (e) { setStatus('offline'); throw e; }
    connection.current = c;
    try { await c.hello(nickname.trim()); setStatus('online'); await refresh(); }
    catch (e) { c.close(); setStatus('offline'); throw e; }
  }
  async function create() {
    const payload: Payload = { ruleId: rule, maxPlayers: '2', maxTurns: '100', turnTimeoutMillis: '30000' };
    if (rule === 'rock-paper-scissors') payload['option.roundsToWin'] = String(rounds);
    if (rule === 'number-guessing') { payload['option.minNumber'] = String(min); payload['option.maxNumber'] = String(max); }
    await request('CREATE_ROOM', payload);
  }
  async function action(fields: Payload) {
    const match = stateRef.current.match; if (!match) return;
    const token = `${match.id}:${match.turn}`;
    if (actionLock.current === token || submitted === token) return;
    actionLock.current = token; setSubmitted(token); setError('');
    try { await request('ACTION', { matchId: match.id, turn: String(match.turn), ...fields }); }
    catch (e) { if (actionLock.current === token) { actionLock.current = null; setSubmitted(null); } setError((e as Error).message); }
  }
  const match = state.match, room = state.room;
  const token = match ? `${match.id}:${match.turn}` : '';
  const locked = submitted === token || (match?.view[`submitted.${state.identity}`] === 'true');
  const playerName = (id: string) => (room?.players ?? match?.players ?? state.result?.players ?? []).find(p => p.playerId === id)?.nickname ?? id.slice(0, 8);
  const online = status === 'online';
  return <div className="app"><header><a className="brand" href="#"><span className="logo">B/</span> MINI BATTLECODE <small>NETWORK ARENA</small></a><div className={`connection ${online ? 'live' : ''}`}><i />{status === 'connecting' ? 'Đang kết nối' : online ? 'Đã kết nối' : 'Offline'}</div>{online && <button className="quiet" onClick={() => void task(async () => { try { await request('QUIT'); } finally { connection.current?.close(); } })}>Ngắt kết nối</button>}</header>
    <main>{error && <div className="error" role="alert">{error}<button aria-label="Ẩn lỗi" onClick={() => setError('')}>×</button></div>}
    {!online ? <section className="welcome"><div className="eyebrow">CODE. CONNECT. COMPETE.</div><h1>Một arena.<br /><em>Nhiều đối thủ.</em></h1><p>Thi đấu cùng bạn bè qua mạng. Chọn trò chơi, tạo phòng và để server điều phối trận đấu.</p><form className="panel connect-panel" onSubmit={e => { e.preventDefault(); void task(connect); }}><label>Nickname<input value={nickname} onChange={e => setNickname(e.target.value)} maxLength={32} required placeholder="Bạn muốn được gọi là gì?" /></label><button disabled={busy || !nickname.trim()}>{busy ? 'Đang kết nối…' : 'Vào arena →'}</button><small>Mỗi lần kết nối tạo một session mới.</small></form><div className="welcome-games"><span>01 / ROCK PAPER SCISSORS</span><span>02 / NUMBER GUESSING</span></div></section>
    : state.result && showResult ? <section className="result panel"><div className="eyebrow">MATCH COMPLETE</div><h1>{state.result.cancelled ? 'Trận đã huỷ' : state.result.winners.includes(state.identity) ? 'Bạn chiến thắng!' : state.result.winners.length ? 'Trận đấu kết thúc' : 'Hoà'}</h1><p>{state.result.cancelled ? 'Server đã huỷ trận đấu.' : `Winner: ${state.result.winners.map(id => playerName(id)).join(', ') || 'Không có'}`}</p><div className="reason">{state.result.reason}</div><button onClick={() => { setShowResult(false); void task(refresh); }}>Về lobby →</button></section>
    : match ? <section><div className="section-title"><div><div className="eyebrow">LIVE MATCH / {match.id.slice(0, 8)}</div><h1>{labels[match.ruleId] ?? match.ruleId}</h1></div><div className="clock">{Math.max(0, Math.ceil((match.deadline - now) / 1000))}s<small>deadline server</small></div></div>
      <div className="players">{match.players.map(p => <div className="panel player" key={p.playerId}><span className="avatar">{p.nickname.slice(0, 1).toUpperCase()}</span><div><strong>{p.nickname}{p.playerId === state.identity ? ' · bạn' : ''}</strong><small>{p.playerId.slice(0, 8)}</small></div>{match.ruleId === 'rock-paper-scissors' && <b className="score">{match.view[`score.${p.playerId}`] ?? '0'}</b>}</div>)}</div>
      {match.ruleId === 'rock-paper-scissors' ? <div className="panel game"><div className="eyebrow">ROUND {match.view.round ?? match.turn + 1} / ĐẠT {match.view.roundsToWin} ĐIỂM</div><h2>{locked ? 'Đã gửi lựa chọn. Chờ đối thủ.' : 'Lựa chọn của bạn?'}</h2><p>Hai người chọn đồng thời. Lựa chọn của đối thủ được giữ kín đến khi kết thúc lượt.</p><div className="choices">{[['ROCK', '✊', 'Rock'], ['PAPER', '✋', 'Paper'], ['SCISSORS', '✌', 'Scissors']].map(([value, icon, label]) => <button disabled={locked || now >= match.deadline} key={value} onClick={() => void action({ 'action.choice': value })}><span>{icon}</span>{label}</button>)}</div>{match.view.yourChoice && <p>Lựa chọn của bạn: <strong>{match.view.yourChoice}</strong></p>}{match.view.lastRound && <div className="reveal"><b>Round {match.view.lastRound} đã công bố</b>{match.players.map(p => <p key={p.playerId}>{p.nickname}: {match.view[`lastChoice.${p.playerId}`]}</p>)}<span>{match.view.lastRoundWinner === 'DRAW' ? 'Hoà' : `Winner: ${playerName(match.view.lastRoundWinner)}`}</span></div>}</div>
      : <div className="panel game"><div className="eyebrow">TURN {match.turn + 1}</div><h2>{match.view.yourTurn === 'true' ? 'Đến lượt bạn đoán.' : `Chờ ${playerName(match.view.currentPlayer)}…`}</h2><p>Đoán số trong khoảng <strong>{match.view.minNumber}–{match.view.maxNumber}</strong>. Gợi ý được server công bố sau mỗi lượt.</p><form className="guess" onSubmit={e => { e.preventDefault(); void action({ 'action.guess': guess }); }}><input aria-label="Số dự đoán" type="number" min={match.view.minNumber} max={match.view.maxNumber} value={guess} onChange={e => setGuess(e.target.value)} required /><button disabled={locked || match.view.yourTurn !== 'true' || now >= match.deadline}>Gửi dự đoán →</button></form><div className="history">{Array.from({ length: Number(match.view.historyCount ?? 0) }, (_, i) => <div key={i}><span>{playerName(match.view[`history.${i}.player`])}</span><b>{match.view[`history.${i}.guess`]}</b><span>{match.view[`history.${i}.hint`] === 'HIGHER' ? '↑ Cao hơn' : match.view[`history.${i}.hint`] === 'LOWER' ? '↓ Thấp hơn' : match.view[`history.${i}.hint`]}</span></div>)}</div></div>}</section>
    : room ? <section><div className="eyebrow">ROOM / {room.roomId.slice(0, 8)}</div><h1>{labels[room.ruleId] ?? room.ruleId}</h1><p>{room.status} · {room.players.length}/{room.capacity} người chơi</p><div className="players">{room.players.map(p => <div className="panel player" key={p.playerId}><span className="avatar">{p.nickname.slice(0, 1).toUpperCase()}</span><div><strong>{p.nickname}{p.playerId === state.identity ? ' · bạn' : ''}</strong><small>{p.playerId.slice(0, 8)}</small></div>{p.playerId === room.hostId && <span className="tag">HOST</span>}</div>)}</div><div className="panel room-actions"><p>{state.identity === room.hostId ? 'Bạn là host. Bắt đầu khi có đủ người chơi.' : 'Chờ host bắt đầu trận đấu.'}</p>{state.identity === room.hostId && <button disabled={busy || room.status !== 'WAITING' || room.players.length < 2 || room.ruleId === 'room-only-no-gameplay'} onClick={() => void task(() => request('START_MATCH', { roomId: room.roomId }))}>Bắt đầu trận →</button>}<button className="secondary" disabled={busy || room.status !== 'WAITING'} onClick={() => void task(async () => { await request('LEAVE_ROOM'); await refresh(); })}>Rời phòng</button></div></section>
    : <section><div className="section-title"><div><div className="eyebrow">WELCOME, {nickname}</div><h1>Chọn cuộc đấu tiếp theo.</h1></div><button className="secondary" disabled={busy} onClick={() => void task(refresh)}>↻ Tải lại</button></div><div className="lobby-grid"><div><h2>Phòng đang mở <span className="count">{rooms.length}</span></h2>{rooms.length ? rooms.map(r => <div className="panel listed-room" key={r.id}><div><span className="eyebrow">{r.id.slice(0, 8)}</span><h3>{labels[r.ruleId] ?? r.ruleId}</h3><small>{r.status} · {r.players}/{r.maxPlayers} người chơi</small></div><button className="secondary" disabled={busy || r.status !== 'WAITING' || Number(r.players) >= Number(r.maxPlayers)} onClick={() => void task(() => request('JOIN_ROOM', { roomId: r.id }))}>Tham gia →</button></div>) : <div className="panel empty"><span>＋</span><h3>Arena đang chờ bạn.</h3><p>Tạo phòng đầu tiên và mời bạn bè cùng tham gia.</p></div>}</div><form className="panel create" onSubmit={e => { e.preventDefault(); void task(create); }}><div className="eyebrow">YOUR ARENA, YOUR RULES</div><h2>Tạo phòng</h2><label>Trò chơi<select value={rule} onChange={e => setRule(e.target.value)}>{rules.map(id => <option key={id} value={id}>{labels[id] ?? id}</option>)}</select></label>{rule === 'rock-paper-scissors' && <label>Điểm để chiến thắng<input type="number" min={1} max={10} value={rounds} onChange={e => setRounds(Number(e.target.value))} required /></label>}{rule === 'number-guessing' && <div className="range"><label>Số nhỏ nhất<input type="number" min={1} max={1000000} value={min} onChange={e => setMin(Number(e.target.value))} required /></label><label>Số lớn nhất<input type="number" min={Math.max(2, min + 1)} max={1000000} value={max} onChange={e => setMax(Number(e.target.value))} required /></label></div>}<p>2 người chơi · 30 giây mỗi lượt</p><button disabled={busy}>Tạo phòng →</button></form></div></section>}
    </main><footer><span>MINI BATTLECODE / PHASE 7</span><span>{state.identity && online ? `SESSION ${state.identity.slice(0, 8)}` : 'SERVER AUTHORITATIVE MULTIPLAYER'}</span></footer></div>;
}
