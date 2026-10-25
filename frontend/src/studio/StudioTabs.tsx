import type { ReactNode } from 'react';
import { navigateTabs } from '../analysis/tabNavigation';

type Props<T extends string> = {
  prefix: string; label: string; value: T; items: { id: T; label: string }[];
  onChange: (value: T) => void; className?: string; panelId?: string; disabled?: boolean;
};

export function StudioTabs<T extends string>({ prefix, label, value, items, onChange, className = '', panelId, disabled = false }: Props<T>) {
  return <div className={className} role="tablist" aria-label={label} onKeyDown={navigateTabs}>
    {items.map(item => <button key={item.id} type="button" id={prefix + '-tab-' + item.id}
      role="tab" disabled={disabled} aria-selected={value === item.id} tabIndex={value === item.id ? 0 : -1}
      aria-controls={panelId ?? prefix + '-panel-' + item.id} onClick={() => onChange(item.id)}>{item.label}</button>)}
  </div>;
}

export function StudioPanel({ prefix, id, active, className = '', children }: {
  prefix: string; id: string; active: boolean; className?: string; children: ReactNode;
}) {
  // hidden + inert сохраняют состояние содержимого и исключают скрытые поля из фокуса.
  return <section id={prefix + '-panel-' + id} role="tabpanel" aria-labelledby={prefix + '-tab-' + id}
    hidden={!active} inert={!active} className={'studio-dock-panel ' + className}>{children}</section>;
}
