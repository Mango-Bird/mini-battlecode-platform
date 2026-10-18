// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { Modal, Tabs } from './ui';
afterEach(cleanup);
it('tabs support arrows, Home and End with a single keyboard stop', () => {
    function Example() { const [value, setValue] = useState('one'); return <Tabs value={value} onChange={setValue} items={[{ id: 'one', label: 'One' }, { id: 'two', label: 'Two' }]}/>; }
    render(<Example />);
    const one = screen.getByRole('tab', { name: 'One' }), two = screen.getByRole('tab', { name: 'Two' });
    fireEvent.keyDown(one, { key: 'ArrowRight' });
    expect(two.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(two);
    expect(one.tabIndex).toBe(-1);
    fireEvent.keyDown(two, { key: 'Home' });
    expect(one.getAttribute('aria-selected')).toBe('true');
});
it('native modal opens, closes on Escape and restores focus', () => {
    const show = vi.fn(function (this: HTMLDialogElement) { this.setAttribute('open', ''); }), close = vi.fn();
    HTMLDialogElement.prototype.showModal = show;
    HTMLDialogElement.prototype.close = close;
    const onClose = vi.fn();
    const { unmount } = render(<Modal title="Navigation" open onClose={onClose}><p>Links</p></Modal>);
    expect(show).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('heading', { name: 'Navigation' })).toBeTruthy();
    fireEvent(screen.getByText('Links').closest('dialog')!, new Event('cancel', { cancelable: true }));
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
    expect(close).toHaveBeenCalledTimes(1);
});
