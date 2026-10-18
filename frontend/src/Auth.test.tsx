// @vitest-environment jsdom
import { afterEach, it, expect, vi } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import AuthPage, { AuthGate } from './Auth';
import { localApi } from './local-submissions';
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); });
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body });
it('registers through cookie API without storing or exposing a token', async () => {
  const fetcher = vi.fn().mockResolvedValue(response({ user: { id: 'A', username: 'alice' } }, 201));vi.stubGlobal('fetch', fetcher);
  render(<AuthPage register />);fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'long-enough-test-password' } });fireEvent.click(screen.getByRole('button', { name: 'Register' }));
  await screen.findByText(/Account created/);expect(fetcher.mock.calls[0][1].credentials).toBe('include');expect(JSON.parse(fetcher.mock.calls[0][1].body).ownerId).toBeUndefined();expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('');
});
it('protects private content and reports expired sessions', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ error: 'Invalid credentials' }, 401)));
  render(<AuthGate><p>private-source</p></AuthGate>);await screen.findByRole('link', { name: 'Login' });expect(screen.queryByText('private-source')).toBeNull();
});
it('clears private UI on expiry and logout', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response({ user: { id: 'A', username: 'alice' } })).mockResolvedValueOnce(response({ loggedOut: true }));vi.stubGlobal('fetch', fetcher);
  render(<AuthGate><p>private-source</p></AuthGate>);await screen.findByText('private-source');fireEvent.click(screen.getByRole('button', { name: 'Logout' }));await screen.findByRole('link', { name: 'Login' });expect(screen.queryByText('private-source')).toBeNull();
  expect(fetcher.mock.calls[1][0]).toContain('/auth/logout');expect(fetcher.mock.calls[1][1].credentials).toBe('include');
});
it('does not retry another identity after authorization denial', async () => {
  const fetcher = vi.fn().mockResolvedValue(response({ error: 'Not owner' }, 403));vi.stubGlobal('fetch', fetcher);
  await expect(localApi('submissions/A')).rejects.toThrow('không có quyền');expect(fetcher).toHaveBeenCalledTimes(1);
});
it('removes previous account content after private request gets 401', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ user: { id: 'A', username: 'alice' } })).mockResolvedValueOnce(response({}, 401)));
  render(<AuthGate><p>private-source</p></AuthGate>);await screen.findByText('private-source');await expect(localApi('submissions')).rejects.toThrow('Phiên');await waitFor(() => expect(screen.queryByText('private-source')).toBeNull());
});
it('unmounts private workspace before loading another account signalled by another tab', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response({ user: { id: 'A', username: 'alice' } })).mockResolvedValueOnce(response({}, 401));vi.stubGlobal('fetch', fetcher);
  render(<AuthGate><p>private-source-A</p></AuthGate>);await screen.findByText('private-source-A');window.dispatchEvent(new StorageEvent('storage', { key: 'battlecode-auth-change', newValue: 'change-signal-only' }));
  await screen.findByRole('link', { name: 'Login' });expect(screen.queryByText('private-source-A')).toBeNull();expect(fetcher).toHaveBeenCalledTimes(2);
});
