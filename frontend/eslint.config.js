import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
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
  globalIgnores(['dist']),
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
])
