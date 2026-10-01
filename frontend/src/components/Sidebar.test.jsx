import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import Sidebar from './Sidebar';
import filesReducer from '../features/filesSlice';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

afterEach(cleanup);

describe('Sidebar', () => {
    it('shows where keyboard focus is, on the collapse toggle too', () => {
        const store = configureStore({ reducer: { files: filesReducer } });
        const { container } = render(
            <Provider store={store}>
                <MemoryRouter>
                    <Sidebar onClose={vi.fn()} isCollapsed={false} toggleCollapse={vi.fn()} />
                </MemoryRouter>
            </Provider>,
        );

        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });
});
