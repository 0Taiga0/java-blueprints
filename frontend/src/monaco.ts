import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

// Редактор и worker входят в сборку; CDN и доступ к интернету при работе не нужны.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });
monaco.editor.defineTheme('blueprints-light', {
  base: 'vs', inherit: true,
  rules: [
    { token: 'comment', foreground: '83918B', fontStyle: 'italic' },
    { token: 'keyword', foreground: '8C4C8C' },
    { token: 'string', foreground: '287655' },
    { token: 'number', foreground: 'BD663E' },
  ],
  colors: {
    'editor.background': '#FFFFFF',
    'editor.foreground': '#26362F',
    'editorLineNumber.foreground': '#B0BAB4',
    'editorLineNumber.activeForeground': '#48735D',
    'editor.lineHighlightBackground': '#F5F8F4',
    'editor.selectionBackground': '#DCEAD8',
    'editorCursor.foreground': '#28664D',
  },
});
export { monaco };

monaco.editor.defineTheme('blueprints-dark', {
  base: 'vs-dark', inherit: true,
  rules: [
    { token: 'comment', foreground: '728697', fontStyle: 'italic' },
    { token: 'keyword', foreground: 'C99BFF' },
    { token: 'string', foreground: '81D6B4' },
    { token: 'number', foreground: 'F2BC79' },
  ],
  colors: {
    'editor.background': '#101923', 'editor.foreground': '#CDD9E5',
    'editorLineNumber.foreground': '#4D6275', 'editorLineNumber.activeForeground': '#8EC8FA',
    'editor.lineHighlightBackground': '#182635', 'editor.selectionBackground': '#27465F',
    'editorCursor.foreground': '#7CC4FF',
  },
});
