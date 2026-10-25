import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

interface Priced {
    int price();
}

class DomainException extends Exception {
    DomainException(String message) { super(message); }
}

class BadPriceException extends IllegalArgumentException {
    BadPriceException(String message) { super(message); }
}

/** Пример возможностей графа: выбирайте методы по отдельности. */
public class JavaFeatures implements Priced {
    private final int base;
    public static final String TITLE = "Покупки";
    private static int created;
    private int[] marks = {1, 2, 3};

    static { created = 0; }

    public JavaFeatures(int base) {
        this.base = base;
        created++;
    }

    @Override
    public int price() { return base; }

    public static String language(int value) {
        byte b = 1;
        short s = 2;
        int n = value + b * s;
        long wide = n;
        float f = 1.5f;
        double rounded = Math.round(f * 10.0) / 10.0;
        char letter = 'Я';
        boolean positive = n > 0 && rounded >= 0 || n == 0;
        Integer boxed = n;
        int[] numbers = {boxed, (int) wide};
        String[] words = {"Привет", String.valueOf(letter)};
        Object object = words;
        String text = ((String[]) object)[0].trim();
        if (positive) return text + numbers[0];
        else return String.format("Отрицательное: %d", n);
    }

    public static int choose(int mode) {
        int result = 0;
        switch (mode) {
            case 0:
                result += 1;
                // Без break: переходим также в case 1.
            case 1:
                result += 10;
                break;
            case 2:
                result = 20;
                break;
            default:
                result = -1;
        }
        return result;
    }

    public static String switchValue(int value) {
        return switch (value) {
            case 0 -> "Ноль";
            case 1, 2 -> "Один или два";
            default -> {
                String prefix = value > 0 ? "Плюс " : "Минус ";
                yield prefix + Math.abs(value);
            }
        };
    }

    public static int loops(int[] numbers) {
        int sum = 0;
        for (int i = 0; i < numbers.length; i++) {
            if (numbers[i] < 0) continue;
            if (numbers[i] > 100) break;
            sum += numbers[i];
        }
        for (int value : numbers) sum += value;
        while (sum < 10) sum++;
        do { sum--; } while (sum > 100);
        return sum;
    }

    public static int collections() {
        Collection<Integer> values = new ArrayList<>(List.of(3, 1, 2));
        Iterable<Integer> iterable = values;
        List<Integer> list = new LinkedList<>(values);
        Collections.sort(list, Comparator.naturalOrder());
        Set<Integer> set = new HashSet<>(Set.of(1, 2));
        TreeSet<Integer> ordered = new TreeSet<>(set);
        Map<String, Integer> map = new HashMap<>(Map.of("цена", 10));
        TreeMap<String, Integer> sorted = new TreeMap<>(map);
        Iterator<Integer> iterator = iterable.iterator();
        int sum = 0;
        while (iterator.hasNext()) sum += iterator.next();
        Integer[] array = list.toArray(new Integer[0]);
        Arrays.sort(array, Comparator.reverseOrder());
        Optional<Integer> first = list.stream().findFirst();
        return sum + ordered.first() + sorted.get("цена") + first.orElse(0);
    }

    public static String console(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        try (Scanner scanner = new Scanner(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             Formatter formatter = new Formatter(Locale.ROOT)) {
            String name = scanner.nextLine();
            formatter.format("Привет, %s!", name);
            System.out.printf("%s%n", formatter);
            System.err.println("Диагностика: " + name);
            return formatter.toString();
        }
    }

    public static String keyboard() {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        System.out.print("Ваше имя: ");
        return scanner.nextLine();
    }

    public static int safeDivide(int value, StringBuilder log) {
        try {
            return 100 / value;
        } catch (ArithmeticException exception) {
            log.append("catch;");
            return -1;
        } finally {
            log.append("finally;");
        }
    }

    public static int checked(int value) throws IOException, DomainException {
        if (value < 0) throw new DomainException("Цена меньше нуля");
        if (value == 0) throw new IOException("Нет данных");
        return value;
    }

    public static int handleChecked(int value) {
        try {
            return checked(value);
        } catch (IOException | DomainException exception) {
            System.err.println(exception.getMessage());
            return 0;
        }
    }

    public static int jumps(StringBuilder log) {
        int sum = 0;
        for (int i = 0; i < 3; i++) {
            try {
                if (i == 0) continue;
                if (i == 2) break;
                sum += i;
            } finally {
                log.append(i);
            }
        }
        return sum;
    }

    public static void unchecked(int value) {
        if (value < 0) throw new BadPriceException("Неверная цена");
        if (value == 0) throw new NoSuchElementException("Пустая коллекция");
        if (value > 100) throw new UncheckedIOException(new IOException("Ошибка ввода"));
    }
}
