import java.util.*;

interface Formatter {
    String format(int value);
}

public class LanguageFeatures implements Formatter {
    private final List<Integer> values = new ArrayList<>();
    public LanguageFeatures(int[] input) {
        for (int value : input) values.add(value);
    }

    @Override
    public String format(int value) {
        return "Число: " + value;
    }

    public int calculate() {
        int total = 0;
        try {
            for (int value : values) {
                if (value < 0) continue;
                switch (value) {
                    case 0: break;
                    default: total += Math.abs(value);
                }
            }
            return total;
        } catch (RuntimeException error) {
            throw new IllegalStateException("Ошибка вычисления", error);
        } finally {
            System.out.println("Готово");
        }
    }

    public static void main(String[] args) {
        var example = new LanguageFeatures(new int[] {1, -2, 3});
        System.out.println(example.format(example.calculate()));
    }
}
