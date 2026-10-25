import type { SourceRange } from '../flow/types';

export type Finding = {
  id: string; rule: string; severity: 'warning' | 'info';
  title: string; message: string; recommendation: string;
  methodId: string; range: SourceRange; nodeIds: string[];
};
export type MethodAnalysis = {
  methodId: string; signature: string; range: SourceRange;
  statementCount: number; maxNesting: number;
  complexity: {
    loopEstimate: string | null; methodEstimate: string | null; explanation: string;
    variables: { symbol: string; meaning: string }[];
    assumptions: string[]; unknownReasons: string[];
  };
};
export type AnalysisReport = {
  findings: Finding[]; methods: MethodAnalysis[]; limitations: string[]; truncated: boolean;
};
export type AnalysisNavigation = (finding: Finding, destination: 'code' | 'block') => void;
