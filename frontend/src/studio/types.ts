export type DockTab = 'code' | 'tests' | 'results' | 'console' | 'comparison' | 'info';
export type CheckTab = Extract<DockTab, 'tests' | 'results' | 'console' | 'comparison'>;
export type DockSize = 'compact' | 'normal' | 'large';
