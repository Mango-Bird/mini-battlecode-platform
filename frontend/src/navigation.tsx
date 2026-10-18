import { createContext, useContext, useEffect, useState, type ReactNode, type AnchorHTMLAttributes } from 'react';
type Navigation = {
    path: string;
    navigate: (href: string) => void;
};
const Context = createContext<Navigation | null>(null);
let blocked = false;
export function setNavigationBlocked(value: boolean) { blocked = value; }
export function confirmNavigation() { return !blocked || window.confirm('Code chưa lưu. Chuyển trang? Bản nháp chỉ được giữ trong tab này; tải lại sẽ mất bản nháp.'); }
export function Router({ children }: {
    children: ReactNode;
}) {
    const [path, setPath] = useState(window.location.pathname + window.location.search);
    useEffect(() => { const pop = () => { if (!confirmNavigation()) {
        history.pushState(null, '', path);
        return;
    } setPath(window.location.pathname + window.location.search); }; window.addEventListener('popstate', pop); return () => window.removeEventListener('popstate', pop); }, [path]);
    function navigate(href: string) { if (!confirmNavigation())
        return; history.pushState(null, '', href); setPath(href); window.scrollTo?.(0, 0); }
    return <Context.Provider value={{ path, navigate }}>{children}</Context.Provider>;
}
export function useNavigation() { return useContext(Context); }
export function Link({ href = '/', onClick, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) {
    const router = useNavigation();
    return <a href={href} {...props} onClick={e => { onClick?.(e); if (!e.defaultPrevented && router && e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey && !props.target && href.startsWith('/')) {
        e.preventDefault();
        router.navigate(href);
    } }}/>;
}
