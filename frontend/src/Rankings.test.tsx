// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import Rankings from './Rankings';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const row = { rank: 1, botId: 'bot', name: 'Strategy', elo: 1516.25, glicko2: 1540.4, rd: 300.2, volatility: .06, wins: 1, draws: 0, losses: 0, provisional: true };
it('defaults to Elo and discloses Glicko and actual history', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => url.endsWith('/history') ? { events: [{ matchId: 'match', score: 1, eloBefore: 1500, eloAfter: 1516.25, gameMode: 'ott-board-9x9-v1', rulesVersion: 'ott-board-9x9-v1', ratingPeriod: 1 }] } : { rankings: [row] } })));
  render(<Rankings />); await screen.findByText('Strategy'); expect(screen.getByText('1516')).toBeTruthy(); expect(screen.queryByRole('columnheader', { name: 'RD' })).toBeNull();
  fireEvent.click(screen.getByRole('checkbox')); await screen.findByText('1540'); expect(screen.getByText('300.2')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: 'Details' })); await screen.findByText(/Elo 1500.0 → 1516.3/); expect(screen.getByText('Match match').closest('details')?.open).toBe(false);
});
it('does not invent rankings when empty or on error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ rankings: [] }) })); render(<Rankings />); await screen.findByText('No ranked results yet.'); expect(screen.queryByRole('table')).toBeNull();
});
it('searches actual names and links a profile without showing identifiers', async () => {vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ rankings: [row] }) }));render(<Rankings />);await screen.findByRole('link', { name: 'Strategy' });fireEvent.change(screen.getByLabelText('Search bots'), { target: { value: 'missing' } });expect(screen.getByText('No matching bots.')).toBeTruthy();fireEvent.change(screen.getByLabelText('Search bots'), { target: { value: 'strategy' } });expect(screen.getByRole('link', { name: 'Strategy' }).getAttribute('href')).toBe('/bots/bot');});
