// @vitest-environment jsdom
import { afterEach, describe, it, expect, vi } from 'vitest';
import { Connection } from './transport';
class Socket extends EventTarget {
  static OPEN = 1; static latest: Socket;
  readyState = 0; onmessage?: (event: { data: string }) => void; onclose?: () => void; onerror?: () => void;
  sent: { requestId: string; command: string }[] = [];
  constructor(_url: string) { super(); Socket.latest = this; }
  send(text: string) { this.sent.push(JSON.parse(text)); }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
  reply(requestId: string, command = 'ACK') { this.onmessage?.({ data: JSON.stringify({ type: 'response', requestId, sessionId: 'server-a', command, payload: { for: 'HELLO' } }) }); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); this.onclose?.(); }
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
describe('WebSocket request lifecycle', () => {
  it('correlates responses and clears requests on close', async () => {
    vi.stubGlobal('WebSocket', Socket); const closed = vi.fn(); const c = new Connection('ws://localhost/ws', vi.fn(), closed);
    const hello = c.hello('Alice'); const socket = Socket.latest; socket.open(); await Promise.resolve(); socket.reply(socket.sent[0].requestId); await hello;
    const a = c.request('PING'), b = c.request('LIST_ROOMS'); socket.reply(socket.sent[2].requestId); await b;
    let rejected = false; const waiting = a.catch(() => { rejected = true; }); c.close(); await waiting; expect(rejected).toBe(true); expect(closed).toHaveBeenCalledTimes(1);
  });
  it('closes rather than retrying an ambiguous timed-out request', async () => {
    vi.useFakeTimers(); vi.stubGlobal('WebSocket', Socket); const closed = vi.fn(); const c = new Connection('ws://localhost/ws', vi.fn(), closed); Socket.latest.open();
    const request = c.request('ACTION').catch(e => e.message); await vi.advanceTimersByTimeAsync(10001); expect(await request).toContain('quá hạn'); expect(closed).toHaveBeenCalledTimes(1); expect(Socket.latest.sent).toHaveLength(1);
  });
});
