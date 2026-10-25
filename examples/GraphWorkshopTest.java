import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphWorkshopTest {
    @Test void positive() { assertEquals(2, GraphWorkshop.score(1)); }
    @Test void zero() { assertEquals(1, GraphWorkshop.score(0)); }
    @Test void negative() { assertEquals(-1, GraphWorkshop.score(-2)); }
}
