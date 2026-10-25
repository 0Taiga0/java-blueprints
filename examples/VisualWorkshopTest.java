import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VisualWorkshopTest {
    @Test
    void calculateChecksBothBranchesAndBoundaries() {
        assertEquals(16, VisualWorkshop.calculate(12, true));
        assertEquals(14, VisualWorkshop.calculate(12, false));
        assertEquals(12, VisualWorkshop.calculate(10, true));
        assertEquals(15, VisualWorkshop.calculate(11, true));
        assertEquals(2, VisualWorkshop.calculate(0, false));
        assertEquals(0, VisualWorkshop.calculate(-5, true));
    }

    @Test
    void foreachKeepsOnlyPositiveValues() {
        assertEquals(7, VisualWorkshop.sumPositive(new int[]{-2, 3, 0, 4}));
        assertEquals(0, VisualWorkshop.sumPositive(new int[0]));
    }

    @Test
    void whileChecksConditionBeforeBody() {
        assertEquals(3, VisualWorkshop.countdown(3));
        assertEquals(0, VisualWorkshop.countdown(0));
        assertEquals(0, VisualWorkshop.countdown(-3));
    }

    @Test
    void doWhileExecutesAtLeastOnce() {
        assertEquals(3, VisualWorkshop.atLeastOnce(3));
        assertEquals(1, VisualWorkshop.atLeastOnce(0));
    }

    @Test
    void stringValuesPreserveCyrillic() {
        assertEquals("Привет, Егор", VisualWorkshop.greeting("Егор"));
        assertEquals("Привет, ", VisualWorkshop.greeting(""));
    }

    @Test
    void ownMethodCanBeCalledWithAnArgument() {
        assertEquals(12, VisualWorkshop.twice(6));
        assertEquals(-12, VisualWorkshop.twice(-6));
    }
}
