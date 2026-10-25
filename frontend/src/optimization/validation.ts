import type { ComparisonResult, CheckResult } from '../check/api';
import type { VerificationContext, VerificationSnapshot } from './types';

// Одного зелёного статуса недостаточно: нужны полные, попарно совпавшие тесты.
export function completeMatch(report: ComparisonResult): boolean {
  const complete = (run: CheckResult) => run.status === 'passed' && run.passed > 0 &&
    run.failed === 0 && run.skipped === 0 && run.outputTruncated === false &&
    Array.isArray(run.tests) && run.tests.length === run.passed &&
    run.tests.every(test => test.status === 'passed');
  return report.status === 'matched' && complete(report.before) && complete(report.after) &&
    report.regressions === 0 && report.fixed === 0 && report.unresolved === 0 &&
    report.tests.length > 0 && report.unchanged === report.tests.length &&
    report.before.passed === report.tests.length && report.after.passed === report.tests.length &&
    report.tests.every(test => test.change === 'unchanged' && test.before?.status === 'passed' && test.after?.status === 'passed') &&
    new Set(report.tests.map(test => test.className + '\u0000' + test.name)).size === report.tests.length;
}

export function sameSnapshot(snapshot: VerificationSnapshot, current: VerificationContext): boolean {
  return snapshot.fileName === current.fileName && snapshot.source === current.source &&
    snapshot.revision === current.revision && snapshot.candidateId === current.candidateId &&
    snapshot.testFileName === current.testFileName && snapshot.testSource === current.testSource &&
    snapshot.preview.revision === current.revision && snapshot.preview.candidate.id === current.candidateId;
}

export function canApplyOptimization(snapshot: VerificationSnapshot | null, current: VerificationContext): boolean {
  return !!snapshot && sameSnapshot(snapshot, current) && snapshot.preview.candidate.available &&
    snapshot.preview.source !== current.source && !!snapshot.preview.source.trim() &&
    snapshot.preview.conversion.fileName === current.fileName &&
    !!current.testFileName.trim() && !!current.testSource.trim() && completeMatch(snapshot.report);
}
