import { describe, it, expect } from 'vitest';
import { initial, receive, endpoint, type Room } from './model';
const room: Room = { roomId: 'room1', ruleId: 'rock-paper-scissors', status: 'WAITING', hostId: 'a', capacity: 2, revision: 1, players: [{ playerId: 'a', nickname: '<script>' }, { playerId: 'b', nickname: 'Alice' }] };
const roomMessage = (revision: number, value: Room | null = room) => ({ type: 'room', command: 'ROOM_STATE', sessionId: 'a', roomId: 'room1', revision, room: value });
const event = (type: string, turn = '0', matchId = 'match1') => ({ type: 'event', command: 'MATCH_EVENT', sessionId: 'a', payload: { type, turn, matchId, deadlineEpochMillis: '1000', winners: 'a', reason: 'RPS_WIN', 'view.yourChoice': 'ROCK' } });
describe('server-authoritative state', () => {
  it('takes identity only from HELLO ACK', () => { expect(receive(initial, { type: 'response', command: 'ACK', sessionId: 'a', payload: { for: 'HELLO' } }).identity).toBe('a'); });
  it('starts a clean session on manual reconnect without resuming old room or match', () => { const old=receive(receive(initial,roomMessage(1)),event('STARTED')); const fresh=receive(old,{type:'response',command:'ACK',sessionId:'new-player',payload:{for:'HELLO'}});expect(fresh.room).toBeNull();expect(fresh.match).toBeNull();expect(fresh.identity).toBe('new-player'); });
  it('ignores stale revisions and captures server host migration', () => { let s = receive(initial, roomMessage(2, { ...room, hostId: 'b' })); s = receive(s, roomMessage(1)); expect(s.room?.hostId).toBe('b'); });
  it('keeps terminal result after room CLOSED and ignores late state', () => { let s = receive(receive(initial, roomMessage(1)), event('STARTED')); s = receive(s, event('FINISHED')); s = receive(s, roomMessage(3, null)); s = receive(s, event('STATE')); expect(s.room).toBeNull(); expect(s.match).toBeNull(); expect(s.result?.winners).toEqual(['a']); });
  it('accepts simultaneous partial state on the same turn', () => { let s = receive(receive(initial, roomMessage(1)), event('STARTED')); s = receive(s, { ...event('STATE'), payload: { ...event('STATE').payload, 'view.yourChoice': 'PAPER' } }); expect(s.match?.view.yourChoice).toBe('PAPER'); });
  it('ignores stale turns and foreign match updates', () => { let s = receive(receive(initial, roomMessage(1)), event('STARTED', '2')); s = receive(s, event('STATE', '1')); s = receive(s, event('FINISHED', '2', 'other')); expect(s.match?.turn).toBe(2); });
  it('requires an explicit secure endpoint in production', () => { expect(() => endpoint(undefined, true)).toThrow(); expect(() => endpoint('ws://localhost/ws', true)).toThrow(); expect(endpoint('wss://backend.example/ws', true)).toBe('wss://backend.example/ws'); });
});
