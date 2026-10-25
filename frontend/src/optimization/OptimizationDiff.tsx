import { useEffect, useLayoutEffect, useRef } from 'react';
import { monaco } from '../monaco';

type Props = { original: string; modified: string; sideBySide: boolean };

/**
 * Модели предпросмотра принадлежат только этому компоненту.
 * Сначала отсоединяем их от DiffEditor, затем освобождаем редактор и модели.
 * Это важно для Monaco: dispose модели, которая ещё подключена к diff, — ошибка.
 */
export function OptimizationDiff({ original, modified, sideBySide }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<monaco.editor.IStandaloneDiffEditor | null>(null);

  useLayoutEffect(() => {
    if (!host.current) return;
    // Не используем getModel или URI основного редактора: обе модели новые.
    const before = monaco.editor.createModel(original, 'java');
    const after = monaco.editor.createModel(modified, 'java');
    const instance = monaco.editor.createDiffEditor(host.current, {
      theme: 'blueprints-dark', readOnly: true, originalEditable: false, domReadOnly: true,
      renderSideBySide: sideBySide, automaticLayout: true, minimap: { enabled: false },
      fontSize: 12, lineHeight: 20, scrollBeyondLastLine: false, renderOverviewRuler: false,
      wordWrap: 'on', diffWordWrap: 'on', fontFamily: "'Cascadia Code', Consolas, monospace",
      originalAriaLabel: 'Java-код до оптимизации', modifiedAriaLabel: 'Предложенный Java-код',
    });
    editor.current = instance;
    instance.setModel({ original: before, modified: after });
    return () => {
      editor.current = null;
      // setModel(null) снимает слушатели onWillDispose и отключает вычисление diff.
      instance.setModel(null);
      instance.dispose();
      before.dispose();
      after.dispose();
    };
  }, [original, modified]);

  useEffect(() => {
    editor.current?.updateOptions({ renderSideBySide: sideBySide });
  }, [sideBySide]);

  return <div ref={host} style={{ height: 360, width: '100%', minWidth: 0 }} />;
}
