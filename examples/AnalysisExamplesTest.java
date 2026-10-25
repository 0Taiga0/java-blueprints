import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisExamplesTest {
    @Test void countsAllOrderedPairs() {
        assertEquals(5, AnalysisExamples.countEqualPairs(new int[] {1, 1, 2}));
        assertEquals(0, AnalysisExamples.countEqualPairs(new int[0]));
    }
    @Test void sumsTwice() {
        assertEquals(12, AnalysisExamples.sumTwice(new int[] {1, 2, 3}));
    }
    @Test void halvesPositiveNumber() {
        assertEquals(3, AnalysisExamples.halvings(8));
        assertEquals(0, AnalysisExamples.halvings(1));
    }
    @Test void checksIdenticalBranches() {
        assertEquals(6, AnalysisExamples.identicalBranches(3));
        assertEquals(-6, AnalysisExamples.identicalBranches(-3));
    }
    @Test void repeatsActions() {
        assertEquals(17, AnalysisExamples.repeatedWork(5));
    }
    @Test void handlesInvalidInputIntentionally() {
        assertEquals(42, AnalysisExamples.parseOrZero("42"));
        assertEquals(0, AnalysisExamples.parseOrZero("не число"));
    }
    @Test void checksAllNestedConditions() {
        assertEquals(6, AnalysisExamples.nestedConditions(6));
        assertEquals(0, AnalysisExamples.nestedConditions(5));
    }
    @Test void eventuallyReachesZero() {
        assertEquals(0, AnalysisExamples.untilZero(4));
    }
}
