public class OptimizationExamples {
    public static final StringBuilder trace = new StringBuilder();

    // Это условие только сравнивает примитивный параметр и не меняет программу.
    public static int doubleValue(int value) {
        if (value > 0) {
            // Объяснение первой ветки должно сохраниться.
            return value * 2;
        } else {
            // Объяснение второй ветки тоже должно сохраниться.
            return value * 2;
        }
    }

    // Четыре вложенных проверки можно сделать менее вложенными.
    public static int reward(int points, boolean active, boolean paid, boolean verified) {
        if (active) {
            if (paid) {
                if (verified) {
                    if (points > 0) {
                        return points * 2;
                    }
                }
            }
        }
        return 0;
    }

    // Объединение if должно сохранить порядок A, B и пропуск второго вызова.
    public static boolean orderedChecks(int first, int second) {
        if (positive(first, "A")) {
            if (positive(second, "B")) {
                return true;
            }
        }
        return false;
    }

    // Ветки одинаковые, но условие оставляет след: удалять его нельзя.
    public static int effectfulCondition(int value) {
        if (positive(value, "C")) {
            return value * 2;
        } else {
            return value * 2;
        }
    }

    // Даже одинаковые ответы не разрешают удалять возможное исключение.
    public static int divisionCondition(int value) {
        if (10 / value > 0) {
            return 1;
        } else {
            return 1;
        }
    }

    private static boolean positive(int value, String marker) {
        trace.append(marker);
        return value > 0;
    }
}
