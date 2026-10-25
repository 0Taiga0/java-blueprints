import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaFeaturesTest {
    @Test void switchHasFallThrough() {
        assertEquals(11, JavaFeatures.choose(0));
        assertEquals(10, JavaFeatures.choose(1));
        assertEquals(20, JavaFeatures.choose(2));
        assertEquals(-1, JavaFeatures.choose(10));
    }
    @Test void switchExpressionYieldsValue() {
        assertEquals("Ноль", JavaFeatures.switchValue(0));
        assertEquals("Минус 5", JavaFeatures.switchValue(-5));
    }
    @Test void finallyRunsBeforeReturn() {
        StringBuilder log = new StringBuilder();
        assertEquals(50, JavaFeatures.safeDivide(2, log));
        assertEquals("finally;", log.toString());
        log.setLength(0);
        assertEquals(-1, JavaFeatures.safeDivide(0, log));
        assertEquals("catch;finally;", log.toString());
    }
    @Test void breakAndContinueStillRunFinally() {
        StringBuilder log = new StringBuilder();
        assertEquals(1, JavaFeatures.jumps(log));
        assertEquals("012", log.toString());
    }
    @Test void checkedAndCustomExceptions() {
        assertThrows(DomainException.class, () -> JavaFeatures.checked(-1));
        assertThrows(java.io.IOException.class, () -> JavaFeatures.checked(0));
        assertThrows(BadPriceException.class, () -> JavaFeatures.unchecked(-1));
        assertEquals(0, JavaFeatures.handleChecked(-1));
    }
    @Test void collectionsAndCyrillicIo() {
        assertEquals(18, JavaFeatures.collections());
        assertEquals("Привет, Егор!", JavaFeatures.console("Егор\n"));
        assertEquals("Привет5", JavaFeatures.language(3));
        assertEquals(12, new JavaFeatures(12).price());
    }
}
