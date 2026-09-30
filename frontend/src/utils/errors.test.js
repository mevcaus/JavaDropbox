import { describe, expect, it } from 'vitest';
import { readableError } from './errors';

// The backend answers both a rejected password and a missing session with a bare 401.
const unauthorized = { response: { status: 401, data: '' } };

describe('readableError', () => {
    describe('a 401', () => {
        it('reads as an expired session by default, e.g. during an upload', () => {
            expect(readableError(unauthorized, 'Failed to upload files.')).toBe(
                'Your session has expired. Please sign in again.',
            );
        });

        it('reads as a wrong password where the caller says it answers a sign-in', () => {
            expect(
                readableError(unauthorized, 'Login failed', { unauthorized: 'Invalid username or password.' }),
            ).toBe('Invalid username or password.');
        });

        it('still shows a message the server sent with it', () => {
            const error = { response: { status: 401, data: { message: 'Account disabled' } } };
            expect(readableError(error, 'Login failed')).toBe('Account disabled');
        });
    });

    it('explains a 5xx without a body', () => {
        expect(readableError({ response: { status: 503, data: '' } }, 'fallback')).toBe(
            'The server is unavailable right now. Please try again.',
        );
    });

    it('uses the fallback when there is no response at all', () => {
        expect(readableError(new Error('Network Error'), 'Failed to load files.')).toBe('Failed to load files.');
    });
});
