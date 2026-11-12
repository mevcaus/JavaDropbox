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

    describe('a json error body', () => {
        it('prefers the specific message over the generic status text', () => {
            const error = { response: { status: 400, data: { error: 'Bad Request', message: 'Name is too long' } } };
            expect(readableError(error, 'fallback')).toBe('Name is too long');
        });

        it('falls back to the status text when there is no message', () => {
            const error = { response: { status: 403, data: { status: 403, error: 'Forbidden', path: '/api/files' } } };
            expect(readableError(error, 'fallback')).toBe('Forbidden');
        });

        it('ignores a blank message', () => {
            const error = { response: { status: 400, data: { error: 'Bad Request', message: '  ' } } };
            expect(readableError(error, 'fallback')).toBe('Bad Request');
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

    // The API slice's errors are the answer itself, not an axios error carrying one.
    describe('an error from the API slice', () => {
        it('reads the server\'s message, or the status when there is none', () => {
            expect(readableError({ status: 409, data: { message: 'Already exists' } }, 'Failed')).toBe('Already exists');
            expect(readableError({ status: 500, data: '<!doctype html><h1>500</h1>' }, 'Failed')).toBe(
                'The server is unavailable right now. Please try again.',
            );
        });

        it('falls back when the request got no answer at all', () => {
            expect(readableError({ status: 'FETCH_ERROR', error: 'TypeError: Failed to fetch' }, 'Failed')).toBe('Failed');
        });
    });

    // A status is a number; one that isn't, which neither axios nor the slice sends, says nothing.
    it('takes no meaning from a status that is not a number', () => {
        expect(readableError({ status: '503' }, 'Failed')).toBe('Failed');
    });

    it('falls back for something that is not an error at all', () => {
        expect(readableError(undefined, 'Failed')).toBe('Failed');
        expect(readableError(null, 'Failed')).toBe('Failed');
        expect(readableError('oops', 'Failed')).toBe('Failed');
    });
});
