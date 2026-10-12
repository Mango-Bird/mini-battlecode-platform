export type Payload = Record<string, string>;
export type Room = { roomId: string; ruleId: string; status: string; hostId: string; capacity: number; revision: number; players: { playerId: string; nickname: string }[] };
export type Message = { type: string; command: string; requestId?: string; sessionId: string; errorCode?: string; payload?: Payload; roomId?: string | null; revision?: number; room?: Room | null };
export type Match = { id: string; turn: number; deadline: number; view: Payload; ruleId: string; players: Room['players'] };
export type Result = { id: string; winners: string[]; reason: string; cancelled: boolean; players: Room['players'] };
export type State = { identity: string; room: Room | null; match: Match | null; result: Result | null; versions: Record<string, number> };
export const initial: State = { identity: '', room: null, match: null, result: null, versions: {} };
export function receive(state: State, message: Message): State {
  if (message.type === 'response' && message.command === 'ACK' && message.payload?.for === 'HELLO') return { ...initial, identity: message.sessionId };
  if (message.type === 'room') {
    const id = message.roomId;
    if (!id) return state;
    const revision = message.revision ?? 0;
    if (revision <= (state.versions[id] ?? -1)) return state;
    return { ...state, versions: { ...state.versions, [id]: revision }, room: message.room ?? (state.room?.roomId === id ? null : state.room), result: message.room && message.room.roomId !== state.room?.roomId ? null : state.result };
  }
  if (message.command !== 'MATCH_EVENT' || !message.payload) return state;
  const p = message.payload, id = p.matchId;
  if (p.type === 'FINISHED' || p.type === 'CANCELLED') {
    if (state.match?.id !== id) return state;
    return { ...state, match: null, result: { id, winners: p.winners?.split(',').filter(Boolean) ?? [], reason: p.reason, cancelled: p.type === 'CANCELLED', players: state.match.players } };
  }
  if (state.result?.id === id || p.type !== 'STARTED' && p.type !== 'STATE') return state;
  if (p.type === 'STATE' && state.match?.id !== id) return state;
  if (state.match?.id === id && Number(p.turn) < state.match.turn) return state;
  const view: Payload = {}; Object.entries(p).forEach(([key, value]) => { if (key.startsWith('view.')) view[key.slice(5)] = value; });
  return { ...state, result: null, match: { id, turn: Number(p.turn), deadline: Number(p.deadlineEpochMillis), view, ruleId: state.match?.ruleId ?? state.room?.ruleId ?? '', players: state.match?.players ?? state.room?.players ?? [] } };
}
export function endpoint(value: string | undefined, secure: boolean) {
  if (!value) throw new Error('Thiếu VITE_WS_URL. Cấu hình endpoint WebSocket trước khi chạy.');
  const url = new URL(value);
  if (!['ws:', 'wss:'].includes(url.protocol) || secure && url.protocol !== 'wss:' || url.username || url.password) throw new Error('Endpoint không hợp lệ; trang HTTPS yêu cầu WSS.');
  return url.href;
}
