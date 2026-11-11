import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// ESLint 9 does not count <Component /> as a use of Component (ESLint 10 does), so without this
// no-unused-vars reports every imported component. This marks the name an element starts with
// (Foo in <Foo> and <Foo.Bar>) as used, which is what eslint-plugin-react's jsx-uses-vars does.
const jsxUsesVars = {
  meta: { type: 'problem', schema: [] },
  create(context) {
    return {
      JSXOpeningElement(node) {
        let name = node.name;
        while (name.type === 'JSXMemberExpression') name = name.object;
        if (name.type === 'JSXIdentifier') context.sourceCode.markVariableAsUsed(name.name, node);
      },
    };
  },
};

export default defineConfig([
  // Generated from openapi.json (npm run api:types and api:client).
  globalIgnores([
    'dist',
    'coverage',
    'src/services/apiSchema.ts',
    'src/services/generatedApi.ts',
    'src/services/requestBodies.ts',
  ]),
  {
    files: ['**/*.{js,jsx}'],
    extends: [
      js.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser,
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      local: { rules: { 'jsx-uses-vars': jsxUsesVars } },
    },
    rules: {
      'local/jsx-uses-vars': 'error',
      // Only an explicit leading underscore opts out; a capitalised name is not exempt, so an
      // unused component import is reported like anything else.
      'no-unused-vars': ['error', { varsIgnorePattern: '^_', argsIgnorePattern: '^_' }],
    },
  },
  // Hooks come from apiSlice.ts, which adds the cache tags; the generated endpoints alone have none.
  {
    files: ['src/**/*.{js,jsx,ts,tsx}'],
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
  // Files converted to TypeScript (#206) get the same hooks and refresh rules, parsed by
  // typescript-eslint, whose no-unused-vars understands types and replaces the core rule.
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
