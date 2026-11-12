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
    files: ['**/*.js'],
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
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/services/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            { group: ['**/generatedApi', '**/generatedApi.*'], message: 'Import from services/apiSlice instead.' },
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
