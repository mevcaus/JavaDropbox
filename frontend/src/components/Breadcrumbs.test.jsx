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
});
