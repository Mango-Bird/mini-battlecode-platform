// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Tournaments, { TournamentBracket } from './Tournaments';

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); });
const entry = (id: string, seed: number) => ({ entryId: id, ownerId: id, ownerDisplay: 'Owner-' + id, botId: id, botName: 'Bot ' + id, version: 1, sourceSha256: 'hash', seedOrder: seed });
const tournament = { tournamentId: 'tour', ownerId: 'A', config: { name: 'Cup', format: 'SINGLE_ELIMINATION', maxParticipants: 3, seed: 42, maxDrawRematches: 1, registrationDeadline: null }, status: 'FINISHED',
  entries: [entry('A', 0), entry('B', 1)], rounds: [{ number: 1, winners: ['A'], matches: [{ matchId: 'match', round: 1, slot: 0, attempt: 0, entryA: 'A', entryB: 'B', status: 'FINISHED', battleId: 'battle', winnerId: 'A', result: { outcome: 'FORFEIT', reason: 'FORFEIT' } }] }],
  standings: [{ rank: 1, entryId: 'A', botName: 'Bot A', ownerDisplay: 'Owner-A', played: 1, wins: 1, draws: 0, losses: 0, forfeits: 0, points: 3 }], result: { championId: 'A', reason: 'ELIMINATION_COMPLETE' }, schedulingReason: '' };
const response = (value: unknown) => ({ ok: true, json: async () => value });
it('renders backend bracket winner and BYE', () => {
  render(<TournamentBracket tournament={{ ...tournament, rounds: [{ ...tournament.rounds[0], matches: [{ ...tournament.rounds[0].matches[0], entryB: null, status: 'BYE' }] }] }} />);
  expect(screen.getByText('Bot A').className).toBe('winner'); expect(screen.getByText('BYE')).toBeTruthy();
});
it('shows standings/champion and reuses authoritative replay frames', async () => {
  const board = [{ id: 'A-R1', owner: 'A', type: 'ROCK', position: { row: 0, col: 0 } }];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/matches/match') ? { status: 'FINISHED', board: [], frames: [board, []], moves: [{ number: 1, side: 'A', pieceId: 'A-R1', from: { row: 0, col: 0 }, to: { row: 1, col: 0 }, captured: null }], result: { outcome: 'FORFEIT', winner: 'A', reason: 'FORFEIT' } }
    : url.endsWith('/tournaments/tour') ? { tournament, viewerOwnerId: 'A' } : url.endsWith('/submissions') ? { submissions: [] } : { tournaments: [tournament], viewerOwnerId: 'A' })));
  render(<Tournaments />); fireEvent.click(await screen.findByRole('button', { name: 'Cup · Single Elimination · Finished' }));
  await screen.findByText('Champion: Bot A');
  fireEvent.click(screen.getByRole('tab', { name: 'Standings' }));
  expect(within(screen.getAllByRole('row')[1]).getByText('3')).toBeTruthy();
  fireEvent.click(screen.getByRole('tab', { name: 'Match Results' }));
  fireEvent.click(screen.getByRole('button', { name: 'View match' })); await screen.findByText('Match Viewer · Finished · Forfeit');
  expect(screen.queryByLabelText('1,1 A ROCK')).toBeNull(); fireEvent.click(screen.getByRole('button', { name: 'Reset' })); expect(screen.getByLabelText('1,1 A ROCK')).toBeTruthy();
});
it('creates with requestId and server workspace identity, never JSON ownerId', async () => {
  const created = { ...tournament, status: 'CREATED', entries: [], rounds: [], standings: [], result: null };
  const fetcher = vi.fn().mockResolvedValueOnce(response({ tournaments: [], viewerOwnerId: 'A' })).mockResolvedValueOnce(response({ submissions: [] })).mockResolvedValueOnce(response(created)).mockResolvedValueOnce(response({ tournaments: [created], viewerOwnerId: 'A' }));
  vi.stubGlobal('fetch', fetcher); render(<Tournaments />);
  await waitFor(() => expect((screen.getByRole('button', { name: 'Create Tournament' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: 'Create Tournament' })); fireEvent.click(screen.getByRole('button', { name: /^Create$/ })); await screen.findByRole('button', { name: 'Cup · Single Elimination · Draft' });
  const request = JSON.parse(fetcher.mock.calls[2][1].body); expect(request.requestId).toBeTruthy(); expect(request.ownerId).toBeUndefined(); expect(fetcher.mock.calls[2][1].credentials).toBe('include'); expect(fetcher.mock.calls[2][1].headers['X-Battlecode-Workspace']).toBeUndefined();
});
it('reports backend unavailable without inventing tournament results', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Worker offline'))); render(<Tournaments />); expect((await screen.findByRole('alert')).textContent).toContain('Worker offline');
  expect(screen.queryByText(/Champion:/)).toBeNull();
});
it('requires explicit backend canManage in addition to matching owner identity', async () => {
  const created = { ...tournament, status: 'CREATED', result: null };
  let canManage = false;
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/tournaments/tour') ? { tournament: created, viewerOwnerId: 'A', canManage } : url.endsWith('/submissions') ? { submissions: [] } : { tournaments: [created], viewerOwnerId: 'A' })));
  render(<Tournaments />); const choose = await screen.findByRole('button', { name: 'Cup · Single Elimination · Draft' }); fireEvent.click(choose); await screen.findByRole('tab', { name: 'Overview' });
  expect(screen.queryByRole('button', { name: 'Open registration' })).toBeNull();
  canManage = true; fireEvent.click(screen.getByRole('button', { name: 'Refresh' })); expect(await screen.findByRole('button', { name: 'Open registration' })).toBeTruthy();
});
