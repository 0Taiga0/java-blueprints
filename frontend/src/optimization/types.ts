import type { Conversion } from '../api';
import type { ComparisonResult } from '../check/api';
import type { SourceRange } from '../flow/types';

export type OptimizationInput = { fileName: string; source: string; revision: string };
export type Candidate = {
  id: string; rule: string; title: string; description: string; methodId: string;
  range: SourceRange; nodeIds: string[]; available: boolean; reason: string;
};
export type OptimizationOptions = { revision: string; candidates: Candidate[]; limitations: string[] };
export type OptimizationPreview = {
  candidate: Candidate; revision: string; source: string; conversion: Conversion;
  selectedMethodId: string; selectedNodeId: string; summary: string; notes: string[];
  beforeFragment: string; afterFragment: string;
};
export type VerificationSnapshot = OptimizationInput & {
  candidateId: string; testFileName: string; testSource: string;
  preview: OptimizationPreview; report: ComparisonResult;
};
export type VerificationContext = OptimizationInput & {
  candidateId: string; testFileName: string; testSource: string;
};
