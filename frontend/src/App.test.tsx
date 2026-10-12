// @vitest-environment jsdom
import { afterEach, describe, it, expect } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import App from './App';
afterEach(cleanup);
describe('welcome', () => {
  it('renders nickname and requires a name before connecting', () => { render(<App />); expect(screen.getByLabelText('Nickname')).toBeTruthy(); expect((screen.getByRole('button', { name: 'Vào arena →' }) as HTMLButtonElement).disabled).toBe(true); });
});
