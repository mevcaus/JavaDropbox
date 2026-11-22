import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // Generated from openapi.json (npm run api:types and api:client).
  globalIgnores([
    'dist',
    'coverage',
    'src/services/apiSchema.ts',
    'src/services/generatedApi.ts',
    'src/services/requestBodies.ts',
  ]),
  // The build's own config files and scripts, which run in Node; the app itself is TypeScript.
  {
    files: ['**/*.{js,cjs,mjs}'],
    extends: [js.configs.recommended],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.node,
    },
    rules: {
      'no-unused-vars': ['error', { varsIgnorePattern: '^_', argsIgnorePattern: '^_' }],
    },
  },
  // Hooks come from apiSlice.ts, which adds the cache tags; the generated endpoints alone have none.
  // Requests go through those hooks too, not baseQuery's sendRequest, which only accountRequests.ts
  // uses, for the requests that carry a secret.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/services/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            { group: ['**/generatedApi', '**/generatedApi.*'], message: 'Import from services/apiSlice instead.' },
            {
              group: ['**/baseQuery', '**/baseQuery.*'],
              message: 'Use the hooks from services/apiSlice, or services/accountRequests for a request carrying a secret.',
            },
          ],
        },
      ],
    },
  },
  // The app, parsed by typescript-eslint, whose no-unused-vars understands types and replaces the
  // core rule.
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { varsIgnorePattern: '^_', argsIgnorePattern: '^_' }],
    },
  },
])
