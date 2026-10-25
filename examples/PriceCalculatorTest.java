import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {
    @Test
    void sumsPositivePrices() {
        assertEquals(400, PriceCalculator.total(List.of(120, 80, 200)));
    }

    @Test
    void ignoresNegativePrices() {
        assertEquals(10, PriceCalculator.total(List.of(-5, 0, 10)));
    }

    @Test
    void emptyBasketCostsZero() {
        assertEquals(0, PriceCalculator.total(List.of()));
    }

    @Test
    void onlyNegativePricesCostZero() {
        assertEquals(0, PriceCalculator.total(List.of(-5, -20)));
    }
}
