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
  fireEvent.change(screen.getByLabelText('Confirm Password'), { target: { value: 'long-enough-test-password' } });fireEvent.click(screen.getByRole('button', { name: 'Register' }));
  await screen.findByText(/Account created/);expect(fetcher.mock.calls[0][1].credentials).toBe('include');expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ username: 'alice', password: 'long-enough-test-password' });expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('');
});
it.each(['12345678', 'a-password-longer-than-eight'])('accepts registration password %s with matching confirmation', async password => {
  const fetcher = vi.fn().mockResolvedValue(response({}, 201)); vi.stubGlobal('fetch', fetcher);
  render(<AuthPage register />);
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
  for (const label of ['Password', 'Confirm Password']) fireEvent.change(screen.getByLabelText(label), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Register' }));
  await screen.findByText(/Account created/);expect(fetcher).toHaveBeenCalledTimes(1);
});
it.each([['1234567', '1234567', /ít nhất 8/], ['12345678', 'different', /không trùng khớp/], ['é'.repeat(37), 'é'.repeat(37), /72 byte/]])('rejects invalid registration without a request or clearing inputs', (password, confirmation, error) => {
  const fetcher = vi.fn();vi.stubGlobal('fetch', fetcher);const { container } = render(<AuthPage register />);
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('Confirm Password'), { target: { value: confirmation } });
  fireEvent.submit(container.querySelector('form')!);
  expect(screen.getByRole('alert').textContent).toMatch(error);expect(fetcher).not.toHaveBeenCalled();
  expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe(password);
  expect((screen.getByLabelText('Confirm Password') as HTMLInputElement).value).toBe(confirmation);
});
it('retains registration inputs after server rejection', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ error: 'Registration rejected' }, 400)));
  render(<AuthPage register />);fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
  for (const label of ['Password', 'Confirm Password']) fireEvent.change(screen.getByLabelText(label), { target: { value: '12345678' } });
  fireEvent.click(screen.getByRole('button', { name: 'Register' }));await screen.findByText('Registration rejected');
  for (const label of ['Password', 'Confirm Password']) expect((screen.getByLabelText(label) as HTMLInputElement).value).toBe('12345678');
});
it.each([true, false])('toggles password visibility independently without submitting (register=%s)', register => {
  const fetcher = vi.fn();vi.stubGlobal('fetch', fetcher);render(<AuthPage register={register} />);
  const password = screen.getByLabelText('Password');expect(password.getAttribute('type')).toBe('password');
  const toggle = screen.getByRole('button', { name: 'Hiện mật khẩu' });expect(toggle.getAttribute('type')).toBe('button');
  toggle.focus();expect(document.activeElement).toBe(toggle);fireEvent.click(toggle);
  expect(password.getAttribute('type')).toBe('text');expect(toggle.getAttribute('aria-pressed')).toBe('true');
  if (register) {
    const confirm = screen.getByLabelText('Confirm Password');expect(confirm.getAttribute('type')).toBe('password');
    fireEvent.click(screen.getByRole('button', { name: 'Hiện xác nhận mật khẩu' }));expect(confirm.getAttribute('type')).toBe('text');
    fireEvent.click(screen.getByRole('button', { name: 'Ẩn xác nhận mật khẩu' }));expect(confirm.getAttribute('type')).toBe('password');
  }
  fireEvent.click(toggle);expect(password.getAttribute('type')).toBe('password');expect(toggle.getAttribute('aria-pressed')).toBe('false');expect(fetcher).not.toHaveBeenCalled();
});
it('does not apply registration minimum to existing-account login', async () => {
  const fetcher = vi.fn().mockResolvedValue(response({ error: 'Invalid credentials' }, 401));vi.stubGlobal('fetch', fetcher);
  render(<AuthPage />);fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'legacy' } });fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'oldpass' } });
  fireEvent.click(screen.getByRole('button', { name: 'Login' }));await screen.findByRole('alert');
  expect(JSON.parse(fetcher.mock.calls[0][1].body).password).toBe('oldpass');
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
