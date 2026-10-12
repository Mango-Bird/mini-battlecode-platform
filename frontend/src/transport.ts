import { endpoint, type Message, type Payload } from './model';
export class Connection {
  private socket: WebSocket;
  private sequence = 0;
  private pending = new Map<string, { resolve: (message: Message) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
  private heartbeat?: ReturnType<typeof setInterval>;
  private stopped = false;
  constructor(url: string | undefined, onMessage: (message: Message) => void, onClose: (reason: string) => void) {
    this.socket = new WebSocket(endpoint(url, location.protocol === 'https:'));
    this.socket.onmessage = event => {
      try {
        const message: Message = JSON.parse(event.data);
        onMessage(message);
        if (message.type === 'response' && message.requestId) {
          const request = this.pending.get(message.requestId);
          if (!request) return;
          clearTimeout(request.timer); this.pending.delete(message.requestId);
          if (message.command === 'ERROR') request.reject(new Error(`${message.errorCode}: ${message.payload?.message}`)); else request.resolve(message);
        }
      } catch { this.close('Thông điệp server không hợp lệ.'); }
    };
    this.socket.onclose = () => this.close('Đã mất kết nối. Kết nối lại sẽ tạo session mới.');
    this.socket.onerror = () => this.close('Không thể kết nối backend. Kiểm tra endpoint và Origin allowlist.');
    this.closed = onClose;
  }
  private closed: (reason: string) => void;
  async hello(name: string) {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { this.close('Kết nối quá hạn.'); reject(new Error('Kết nối quá hạn.')); }, 10000);
      this.socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      this.socket.addEventListener('close', () => { clearTimeout(timer); reject(new Error('Kết nối bị đóng.')); }, { once: true });
    });
    await this.request('HELLO', { name });
    this.heartbeat = setInterval(() => void this.request('PING').catch(() => {}), 20000);
  }
  request(command: string, payload: Payload = {}): Promise<Message> {
    if (this.stopped || this.socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error('Chưa kết nối.'));
    if (this.pending.size >= 32) return Promise.reject(new Error('Quá nhiều yêu cầu đang chờ.'));
    const requestId = `web_${++this.sequence}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.close('Yêu cầu quá hạn; đóng session để tránh trạng thái không rõ.'), 10000);
      this.pending.set(requestId, { resolve, reject, timer });
      this.socket.send(JSON.stringify({ requestId, command, payload }));
    });
  }
  close(reason = 'Đã ngắt kết nối.') {
    if (this.stopped) return; this.stopped = true;
    clearInterval(this.heartbeat); this.socket.close();
    this.pending.forEach(p => { clearTimeout(p.timer); p.reject(new Error(reason)); }); this.pending.clear(); this.closed(reason);
  }
}
