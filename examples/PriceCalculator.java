import java.util.List;

// Считаем стоимость покупок.
public class PriceCalculator {
    public static int total(List<Integer> prices) {
        int sum = 0;
        for (int price : prices) {
            if (price > 0) {
                sum += price;
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        List<Integer> prices = List.of(120, 80, 200);
        int result = total(prices);
        System.out.println("Сумма: " + result);
    }
}
