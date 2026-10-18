// @vitest-environment jsdom
import { afterEach, it, expect, vi } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import BotWorkspace from './BotWorkspace';
import BotArena from './BotArena';
afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); });
const metadata = { botId: 'user-id', name: 'My Bot', latestVersion: 1, sourceSha256: 'abc', createdAt: '', updatedAt: '', validationStatus: 'SOURCE_ACCEPTED', executionEligibility: 'NOT_EXECUTABLE' };
const detail = { metadata, versions: [{ version: 1, source: 'saved code', sourceSha256: 'abc', createdAt: '', validationStatus: 'SOURCE_ACCEPTED', executionEligibility: 'NOT_EXECUTABLE' }] };
const response = (value: unknown) => ({ ok: true, json: async () => value });
it('creates and saves source with a server request ID and shows hash/eligibility', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response({ submissions: [] })).mockResolvedValueOnce(response(detail)).mockResolvedValueOnce(response({ submissions: [metadata] }));
  vi.stubGlobal('fetch', fetcher); render(<BotWorkspace />);
  await waitFor(() => expect((screen.getByRole('button', { name: 'Save Bot' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('JavaScript source'), { target: { value: 'saved code' } });
  expect(screen.getByText('Chưa lưu')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Save Bot' }));
  await screen.findByText('SHA-256: abc');
  const request = JSON.parse(fetcher.mock.calls[1][1].body);
  expect(request.source).toBe('saved code');expect(request.requestId).toBeTruthy();
  expect(screen.getByText('Source accepted · Not ready to compete')).toBeTruthy();
  expect(fetcher.mock.calls[1][0]).toContain('/api/local/submissions');
});
it('supports saved version read-only and sends expectedVersion for rename', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response({ submissions: [metadata] })).mockResolvedValueOnce(response(detail))
    .mockResolvedValueOnce(response({ ...detail, metadata: { ...metadata, name: 'Renamed', latestVersion: 2 } }))
    .mockResolvedValueOnce(response({ submissions: [metadata] }));
  vi.stubGlobal('fetch', fetcher); render(<BotWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: /My Bot · v1/ }));
  await screen.findByText('SHA-256: abc');
  fireEvent.change(screen.getByLabelText('Version'), { target: { value: '1' } });
  expect((screen.getByLabelText('JavaScript source') as HTMLTextAreaElement).readOnly).toBe(true);
  fireEvent.change(screen.getByLabelText('Version'), { target: { value: '0' } });
  fireEvent.change(screen.getByLabelText('Bot name'), { target: { value: 'Renamed' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save Bot' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(4));
  expect(fetcher.mock.calls[2][1].method).toBe('PUT');
  expect(JSON.parse(fetcher.mock.calls[2][1].body).expectedVersion).toBe(1);
});
it('displays network errors without claiming validation or execution', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Backend offline')));
  render(<BotWorkspace />);expect((await screen.findByRole('alert')).textContent).toContain('Backend offline');
  expect(screen.getByText(/Source accepted không chứng minh/)).toBeTruthy();
});
it('arena lists user bots and refuses to start one without replacing it', async () => {
  const fetcher = vi.fn().mockResolvedValue(response({ bots: ['FirstLegalMoveBot', 'CapturePriorityBot'], initialBoard: [], submissions: [metadata] }));
  vi.stubGlobal('fetch', fetcher); render(<BotArena />);
  await screen.findAllByRole('option', { name: 'My Bot · v1 · Not ready to compete' });
  fireEvent.change(screen.getByLabelText('Bot A'), { target: { value: 'submission:user-id' } });
  const start = screen.getByRole('button', { name: 'Start Battle' });
  expect((start as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(start);expect(fetcher).toHaveBeenCalledTimes(1);
  expect(screen.getByText(/chọn trusted fixtures/)).toBeTruthy();
  expect((screen.getByLabelText('Bot A') as HTMLSelectElement).value).toBe('submission:user-id');
});
it('blocks oversized UTF-8 drafts and warns before source can be lost', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ submissions: [] })));
  render(<BotWorkspace />); await waitFor(() => expect((screen.getByRole('button', { name: 'Save Bot' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText('JavaScript source'), { target: { value: 'é'.repeat(16385) } });
  expect((screen.getByRole('button', { name: 'Save Bot' }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByRole('alert').textContent).toContain('Source vượt 32KiB');
});
