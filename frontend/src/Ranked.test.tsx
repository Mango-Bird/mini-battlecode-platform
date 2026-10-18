// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import Ranked from './Ranked';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const bot = { botId: 'bot', name: 'Strategy', latestVersion: 1, sourceSha256: 'hash', executionEligibility: 'EXECUTABLE' };
it('joins once with frozen fields and cancels waiting search', async () => {
  let entry: unknown = null; const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/queue') && init?.method === 'POST') entry = { ticket: { ticketId: 'ticket', botId: 'bot', version: 1, elo: 1500, status: 'WAITING' }, ratingWindow: 100, durationSeconds: 0 };
    if (init?.method === 'DELETE') entry = null;
    return { ok: true, status: 200, json: async () => url.endsWith('/submissions') ? { submissions: [bot] } : url.endsWith('/queue/me') ? { entry } : { matches: [] } };
  });vi.stubGlobal('fetch', fetch);render(<Ranked />);await screen.findByText('Strategy · v1');fireEvent.click(screen.getByRole('button', { name: 'Join Ranked Queue' }));await screen.findByText('Searching');expect(screen.getByText(/Window ±100/)).toBeTruthy();
  const post = fetch.mock.calls.find(c => c[1]?.method === 'POST');expect(JSON.parse(post![1]!.body as string)).toMatchObject({ botId: 'bot', version: 1, sourceSha256: 'hash' });expect(JSON.parse(post![1]!.body as string).ownerId).toBeUndefined();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel Search' }));await screen.findByRole('button', { name: 'Join Ranked Queue' });
});
it('shows authoritative match and only committed rating change', async () => {
 vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => url.endsWith('/submissions') ? { submissions: [bot] } : url.endsWith('/queue/me') ? { entry: { ticket: { botId: 'bot', elo: 1500, status: 'COMPLETED' }, ratingWindow: 100, durationSeconds: 3 } } : { matches: [{ matchId: 'match', gameNumber: 1, status: 'COMPLETED', snapshot: { turn: 2, result: { outcome: 'FORFEIT', winner: 'B' } }, ratingChanges: [{ botId: 'bot', before: 1500, after: 1484 }] }] } })));
 render(<Ranked />);await screen.findByText('Confirmed Elo 1500.0 → 1484.0');expect(screen.getByText('Forfeit · Side B')).toBeTruthy();
});
it('shows network errors without inventing a match', async () => {vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network unavailable')));render(<Ranked />);await screen.findByRole('alert');expect(screen.queryByText('Match Found')).toBeNull();});
