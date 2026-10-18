// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import Platform from './Platform';
import BotArena from './BotArena';
import { productLabel } from './product-labels';
const response = (value: unknown) => ({ ok: true, status: 200, json: async () => value });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });
const user = { id: 'private-account-id', username: 'alice' };
it('Account keeps technical identity behind a closed disclosure and offers Logout', async () => {
  history.replaceState(null, '', '/account'); vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ user })));
  render(<Platform />); const main = await screen.findByRole('main'); await within(main).findByRole('heading', { name: 'alice' });
  expect(within(main).getByRole('button', { name: 'Logout' })).toBeTruthy();
  expect(within(main).getByText('User ID: private-account-id').closest('details')?.open).toBe(false);
  expect(screen.queryByText('HttpOnly session cookie')).toBeNull(); expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).toBeNull();
  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
});
it('My Bots prioritizes New Bot/Edit and keeps exact hash only in closed Details', async () => {
  const bot = { botId: 'bot-id', name: 'Strategy', latestVersion: 2, sourceSha256: 'exact-source-hash', validationStatus: 'SOURCE_ACCEPTED', executionEligibility: 'NOT_EXECUTABLE' };
  history.replaceState(null, '', '/my-bots'); vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/auth/me') ? { user } : { submissions: [bot] })));
  render(<Platform />); await screen.findByRole('link', { name: /^Edit$/ });
  expect(screen.getByRole('link', { name: 'New Bot' })).toBeTruthy(); expect(screen.getByText('Version 2 · Not ready to compete')).toBeTruthy();
  expect(screen.getByText('SHA-256: exact-source-hash').closest('details')?.open).toBe(false);
  expect(screen.queryByText('NOT_EXECUTABLE')).toBeNull(); expect(screen.getAllByRole('heading', { name: 'My Bots' })).toHaveLength(1);
  fireEvent.click(screen.getByText('Details', { exact: true })); expect(screen.getByText('SHA-256: exact-source-hash').closest('details')?.open).toBe(true);
});
it('idle Arena has no status badge, empty log, capture panel or replay controls', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ bots: ['FirstLegalMoveBot', 'CapturePriorityBot'], initialBoard: [] })));
  render(<BotArena />); await screen.findAllByRole('option', { name: 'FirstLegalMoveBot' });
  expect(screen.queryByText('IDLE')).toBeNull(); expect(screen.queryByRole('heading', { name: 'Battle log' })).toBeNull();
  expect(screen.queryByRole('heading', { name: 'Captured pieces' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Reset' })).toBeNull();
  expect(screen.getAllByRole('gridcell')).toHaveLength(81); expect(screen.getByRole('button', { name: 'Start Battle' })).toBeTruthy();
});
it('preserves enums as input while translating only presentation labels', () => {
  expect(productLabel('REGISTRATION_OPEN')).toBe('Registration Open'); expect(productLabel('RUNNING')).toBe('In Progress');
  expect(productLabel('FINISHED')).toBe('Finished'); expect(productLabel('CANCELLED')).toBe('Cancelled'); expect(productLabel('NEW_FAILURE_REASON')).toBe('New failure reason');
});
