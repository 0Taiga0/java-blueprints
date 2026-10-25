import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptimizationExamplesTest {
    @Test void doublesBothSignsAndZero() {
        assertEquals(6, OptimizationExamples.doubleValue(3));
        assertEquals(-6, OptimizationExamples.doubleValue(-3));
        assertEquals(0, OptimizationExamples.doubleValue(0));
    }
    @Test void checksAllRewardConditions() {
        assertEquals(10, OptimizationExamples.reward(5, true, true, true));
        assertEquals(0, OptimizationExamples.reward(5, false, true, true));
        assertEquals(0, OptimizationExamples.reward(5, true, false, true));
        assertEquals(0, OptimizationExamples.reward(5, true, true, false));
        assertEquals(0, OptimizationExamples.reward(-5, true, true, true));
    }
    @Test void preservesOrderOfCalls() {
        OptimizationExamples.trace.setLength(0);
        assertTrue(OptimizationExamples.orderedChecks(1, 2));
        assertEquals("AB", OptimizationExamples.trace.toString());
    }
    @Test void skipsSecondCallWhenFirstConditionIsFalse() {
        OptimizationExamples.trace.setLength(0);
        assertFalse(OptimizationExamples.orderedChecks(-1, 2));
        assertEquals("A", OptimizationExamples.trace.toString());
    }
    @Test void preservesEffectOfIdenticalBranchCondition() {
        OptimizationExamples.trace.setLength(0);
        assertEquals(6, OptimizationExamples.effectfulCondition(3));
        assertEquals("C", OptimizationExamples.trace.toString());
    }
    @Test void preservesDivisionException() {
        assertEquals(1, OptimizationExamples.divisionCondition(2));
        assertThrows(ArithmeticException.class, () -> OptimizationExamples.divisionCondition(0));
    }
}
