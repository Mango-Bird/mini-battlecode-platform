// @vitest-environment jsdom
import { afterEach, it, expect, vi } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import BotArena, { OttBoard } from './BotArena';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const board = [{ id: 'A-R1', owner: 'A', type: 'ROCK', position: { row: 0, col: 0 } }];
it('renders 81 squares and highlights the server move', () => {
  render(<OttBoard pieces={board} move={{ number: 1, side: 'A', pieceId: 'A-R1', from: { row: 0, col: 0 }, to: { row: 1, col: 1 }, captured: null }} />);
  expect(screen.getAllByRole('gridcell')).toHaveLength(81);
  expect(screen.getByLabelText('1,1 A ROCK').className).toContain('last-move');
});
it('uses server result and frames, blocks repeated starts, and replays', async () => {
  let finish!: (value: unknown) => void;
  const fetcher = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ bots: ['FirstLegalMoveBot', 'CapturePriorityBot'], initialBoard: board }) })
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  vi.stubGlobal('fetch', fetcher); render(<BotArena />);
  const start = screen.getByRole('button', { name: 'Start Battle' });
  await waitFor(() => expect((start as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(start); fireEvent.click(start);
  expect(fetcher).toHaveBeenCalledTimes(2);
  finish({ ok: true, json: async () => ({ id: 'test', botA: 'FirstLegalMoveBot', botB: 'CapturePriorityBot', status: 'FINISHED', turn: 2, currentSide: 'B',
    board: [], frames: [board, []], moves: [{ number: 1, side: 'A', pieceId: 'A-R1', from: { row: 0, col: 0 }, to: { row: 1, col: 1 }, captured: null }],
    result: { outcome: 'FORFEIT', winner: 'A', reason: 'FORFEIT' } }) });
  await screen.findByText('Forfeit · Side A');
  expect(screen.queryByLabelText('1,1 A ROCK')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
  expect(screen.getByLabelText('1,1 A ROCK')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(screen.queryByLabelText('1,1 A ROCK')).toBeNull();
});
it('shows backend errors without fabricating a result', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ error: 'Origin denied' }) }));
  render(<BotArena />); expect((await screen.findByRole('alert')).textContent).toContain('Origin denied');
  expect(screen.getByRole('status').textContent).toBe('Error');
});
it('starts an eligible saved bot with the exact selected version and hash', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ bots: ['FirstLegalMoveBot', 'CapturePriorityBot'], initialBoard: [],
    execution: { executable: true }, submissions: [{ botId: 'saved-id', name: 'Saved', latestVersion: 2, sourceSha256: 'hash-two', executionEligibility: 'EXECUTABLE',
      versions: [{ version: 1, sourceSha256: 'hash-one' }, { version: 2, sourceSha256: 'hash-two' }] }] }) })
    .mockResolvedValueOnce({ ok: false, json: async () => ({ error: 'Worker unavailable' }) });
  vi.stubGlobal('fetch', fetcher); render(<BotArena />);
  await screen.findAllByRole('option', { name: 'Saved · v2 · Ready to compete' });
  fireEvent.change(screen.getByLabelText('Bot A'), { target: { value: 'submission:saved-id' } });
  fireEvent.change(screen.getByLabelText('Version A'), { target: { value: '1' } });
  fireEvent.click(screen.getByRole('button', { name: 'Start Battle' }));
  await screen.findByRole('alert');
  expect(JSON.parse(fetcher.mock.calls[1][1].body).botA).toEqual({ botId: 'saved-id', version: 1, sourceSha256: 'hash-one' });
  expect(JSON.parse(fetcher.mock.calls[1][1].body).botB).toBe('CapturePriorityBot');
  expect(screen.getByRole('status').textContent).toBe('Error');
});
