import { playerToolsEnabled } from './runtime';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { localApi, notifyAuthChange, SessionExpiredError } from './local-submissions';
import type { User } from './Auth';
type Session = {
    user: User | null;
    loading: boolean;
    error: string;
    refresh: () => Promise<void>;
    logout: () => Promise<void>;
};
const Context = createContext<Session | null>(null);
export function useSession() { return useContext(Context); }
export function SessionProvider({ children }: {
    children: ReactNode;
}) {
    const [user, setUser] = useState<User | null>(null), [loading, setLoading] = useState(playerToolsEnabled), [error, setError] = useState('');
    const [generation, setGeneration] = useState(0);
    useEffect(() => {
        const abort = new AbortController();
        if (playerToolsEnabled) {
            setUser(null);
            setLoading(true);
            void localApi<{
                user: User;
            }>('auth/me', 'GET', undefined, abort.signal).then(data => { if (!abort.signal.aborted) {
                setUser(data.user);
                setError('');
            } }).catch(e => { if (!abort.signal.aborted)
                setError(e instanceof SessionExpiredError ? 'Đăng nhập để mở player workspace.' : e.message); }).finally(() => { if (!abort.signal.aborted)
                setLoading(false); });
        }
        const expired = () => { abort.abort(); setUser(null); setLoading(false); setError('Phiên đã hết hạn. Bản nháp chưa lưu được giữ riêng trong bộ nhớ tab cho tài khoản trước; đăng nhập lại cùng tài khoản trước khi reload.'); };
        const changed = (event: StorageEvent) => { if (event.key === 'battlecode-auth-change') {
            abort.abort();
            setUser(null);
            setLoading(true);
            setGeneration(n => n + 1);
        } };
        window.addEventListener('battlecode-session-expired', expired);
        window.addEventListener('storage', changed);
        return () => { abort.abort(); window.removeEventListener('battlecode-session-expired', expired); window.removeEventListener('storage', changed); };
    }, [generation]);
    async function refresh() { setUser(null); setLoading(true); setGeneration(n => n + 1); }
    async function logout() { await localApi('auth/logout', 'POST', {}); setUser(null); setError('Đã đăng xuất.'); notifyAuthChange(); }
    return <Context.Provider value={{ user, loading, error, refresh, logout }}>{children}</Context.Provider>;
}
