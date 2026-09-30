import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Breadcrumbs from './Breadcrumbs';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

afterEach(cleanup);

describe('Breadcrumbs', () => {
    it('navigates to an ancestor folder by its full path', async () => {
        const user = userEvent.setup();
        const onNavigate = vi.fn();
        render(<Breadcrumbs currentPath="docs/2026/q1" onNavigate={onNavigate} />);

        await user.click(screen.getByRole('button', { name: '2026' }));
        await user.click(screen.getByRole('button', { name: 'Home' }));

        expect(onNavigate.mock.calls).toEqual([['docs/2026'], ['']]);
    });

    it('shows where keyboard focus is', () => {
        const { container } = render(<Breadcrumbs currentPath="a/b/c" onNavigate={vi.fn()} />);
        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });

    it('is a labelled navigation landmark that marks the folder being viewed', () => {
        render(<Breadcrumbs currentPath="docs/2026" onNavigate={vi.fn()} />);

        expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toBeInTheDocument();
        expect(screen.getByText('2026')).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('button', { name: 'docs' })).not.toHaveAttribute('aria-current');
    });

    it('marks Home as current at the root', () => {
        render(<Breadcrumbs currentPath="" onNavigate={vi.fn()} />);

        expect(screen.getByText('Home').closest('[aria-current]')).toHaveAttribute('aria-current', 'page');
    });
});
