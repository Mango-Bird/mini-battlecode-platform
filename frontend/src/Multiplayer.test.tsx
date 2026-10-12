// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { Message, Payload, Room } from './model';
const mock = vi.hoisted(() => ({ emit: (_message: Message) => {}, actions: [] as Payload[] }));
vi.mock('./transport', () => ({ Connection: class {
  constructor(_url: string, handler: (message: Message) => void) { mock.emit = handler; }
  async hello() { mock.emit({ type: 'response', command: 'ACK', sessionId: 'a', payload: { for: 'HELLO' } }); }
  async request(command: string, payload: Payload) {
    if (command === 'ACTION') mock.actions.push(payload);
    return { command: 'ACK', payload: command === 'LIST_RULES' ? { count: '2', nextOffset: '-1', 'rule.0.id': 'rock-paper-scissors', 'rule.1.id': 'number-guessing' } : { count: '0', nextOffset: '-1' } };
  }
  close() {}
} }));
import App from './App';
const room: Room = { roomId: 'room1', ruleId: 'rock-paper-scissors', status: 'WAITING', hostId: 'a', capacity: 2, revision: 1, players: [{ playerId: 'a', nickname: 'Same' }, { playerId: 'b', nickname: 'Same' }] };
async function connected() { render(<App />); fireEvent.change(screen.getByLabelText('Nickname'), { target: { value: 'Same' } }); fireEvent.click(screen.getByRole('button', { name: 'Vào arena →' })); await screen.findByRole('heading', { name: 'Chọn cuộc đấu tiếp theo.' }); }
function snapshot(r: Room) { act(() => mock.emit({ type: 'room', command: 'ROOM_STATE', sessionId: 'a', roomId: r.roomId, revision: r.revision, room: r })); }
function started(view: Payload) { act(() => mock.emit({ type: 'event', command: 'MATCH_EVENT', sessionId: 'a', payload: { type: 'STARTED', matchId: 'match1', turn: '0', deadlineEpochMillis: String(Date.now() + 30000), ...Object.fromEntries(Object.entries(view).map(([key, value]) => ['view.' + key, value])) } })); }
beforeEach(() => { mock.actions.length = 0; }); afterEach(cleanup);
describe('multiplayer controls', () => {
  it('uses server identity for host migration even when nicknames match', async () => { await connected(); snapshot({ ...room, hostId: 'b' }); expect(screen.queryByRole('button', { name: 'Bắt đầu trận →' })).toBeNull(); snapshot({ ...room, revision: 2 }); expect(screen.getByRole('button', { name: 'Bắt đầu trận →' })).toBeTruthy(); });
  it('blocks double submit and sends authoritative matchId/expected turn', async () => { await connected(); snapshot(room); started({ round: '1', roundsToWin: '3' }); const rock = screen.getByRole('button', { name: '✊ Rock' }); fireEvent.click(rock); fireEvent.click(rock); expect(mock.actions).toHaveLength(1); expect(mock.actions[0]).toEqual({ matchId: 'match1', turn: '0', 'action.choice': 'ROCK' }); expect((rock as HTMLButtonElement).disabled).toBe(true); });
  it('disables guessing outside own turn and keeps cancellation result after CLOSED', async () => { await connected(); snapshot({ ...room, ruleId: 'number-guessing' }); started({ minNumber: '1', maxNumber: '100', currentPlayer: 'b', yourTurn: 'false' }); expect((screen.getByRole('button', { name: 'Gửi dự đoán →' }) as HTMLButtonElement).disabled).toBe(true); act(() => { mock.emit({ type: 'event', command: 'MATCH_EVENT', sessionId: 'a', payload: { type: 'CANCELLED', matchId: 'match1', turn: '0', reason: 'MAX_TURNS' } }); mock.emit({ type: 'room', command: 'ROOM_STATE', sessionId: 'a', roomId: room.roomId, revision: 3, room: null }); }); expect(screen.getByRole('heading', { name: 'Trận đã huỷ' })).toBeTruthy(); });
});
