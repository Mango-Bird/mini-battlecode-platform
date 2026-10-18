import { Component, useEffect, useRef, useId, type ReactNode, type ButtonHTMLAttributes, type InputHTMLAttributes, type SelectHTMLAttributes } from 'react';
export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & {
    variant?: 'primary' | 'secondary' | 'danger';
}) { return <button className={`ui-button ${variant} ${className}`} {...props}/>; }
export function Card({ children, className = '' }: {
    children: ReactNode;
    className?: string;
}) { return <section className={`ui-card ${className}`}>{children}</section>; }
export function Badge({ children }: {
    children: ReactNode;
}) { return <span className="ui-badge">{children}</span>; }
export function Input(props: InputHTMLAttributes<HTMLInputElement>) { return <input className="ui-input" {...props}/>; }
export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) { return <select className="ui-input" {...props}/>; }
export function Table({ children, label }: {
    children: ReactNode;
    label: string;
}) { return <div className="table-scroll" tabIndex={0} role="region" aria-label={label}><table>{children}</table></div>; }
export function LoadingState({ children = 'Loading…' }: {
    children?: ReactNode;
}) { return <div role="status" className="state-box"><span className="spinner"/>{children}</div>; }
export function EmptyState({ title, children }: {
    title: string;
    children?: ReactNode;
}) { return <div className="state-box"><h3>{title}</h3>{children}</div>; }
export function ErrorState({ children }: {
    children: ReactNode;
}) { return <div className="error-box" role="alert">{children}</div>; }
export function Toast({ children }: {
    children: ReactNode;
}) { return <div className="toast" role="status">{children}</div>; }
export function StatCard({ label, value, note }: {
    label: string;
    value: ReactNode;
    note?: string;
}) { return <Card className="stat-card"><span>{label}</span><strong>{value}</strong>{note && <small>{note}</small>}</Card>; }
export function PageHeader({ eyebrow, title, description, actions }: {
    eyebrow?: string;
    title: string;
    description?: string;
    actions?: ReactNode;
}) { return <div className="page-heading"><div><h1>{title}</h1>{description && <p>{description}</p>}</div>{actions}</div>; }
export function Tabs({ items, value, onChange }: {
    items: {
        id: string;
        label: string;
    }[];
    value: string;
    onChange: (id: string) => void;
}) {
    return <div className="ui-tabs" role="tablist" aria-label="Sections">{items.map((item, i) => <button key={item.id} role="tab" id={`tab-${item.id}`} aria-selected={value === item.id} aria-controls={`panel-${item.id}`} tabIndex={value === item.id ? 0 : -1} onClick={() => onChange(item.id)} onKeyDown={e => {
                const next = e.key === 'ArrowRight' ? (i + 1) % items.length : e.key === 'ArrowLeft' ? (i + items.length - 1) % items.length : e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : -1;
                if (next >= 0) {
                    e.preventDefault();
                    onChange(items[next].id);
                    document.getElementById(`tab-${items[next].id}`)?.focus();
                }
            }}>{item.label}</button>)}</div>;
}
export function Modal({ title, open, onClose, children }: {
    title: string;
    open: boolean;
    onClose: () => void;
    children: ReactNode;
}) {
    const ref = useRef<HTMLDialogElement>(null);
    const titleId = useId();
    useEffect(() => { const dialog = ref.current; if (!open || !dialog)
        return; const previous = document.activeElement as HTMLElement | null; dialog.showModal(); return () => { dialog.close(); previous?.focus(); }; }, [open]);
    return <dialog ref={ref} className="ui-modal" aria-labelledby={titleId} onCancel={e => { e.preventDefault(); onClose(); }}><h2 id={titleId}>{title}</h2>{children}<Button variant="secondary" onClick={onClose}>Close</Button></dialog>;
}
export function ConfirmDialog({ open, title, onConfirm, onClose }: {
    open: boolean;
    title: string;
    onConfirm: () => void;
    onClose: () => void;
}) { return <Modal open={open} title={title} onClose={onClose}><p>This action requires confirmation.</p><Button variant="danger" onClick={onConfirm}>Confirm</Button></Modal>; }
export class ErrorBoundary extends Component<{
    children: ReactNode;
}, {
    failed: boolean;
}> {
    state = { failed: false };
    static getDerivedStateFromError() { return { failed: true }; }
    render() { return this.state.failed ? <ErrorState>Trang gặp lỗi. <Button onClick={() => window.location.reload()}>Reload page</Button></ErrorState> : this.props.children; }
}
