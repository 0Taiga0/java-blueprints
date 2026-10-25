/**
 * Учебный пример: предупреждение — повод подумать, а не автоматическая ошибка.
 * Число операций сравниваем по росту входных данных, а не по секундомеру.
 */
public class AnalysisExamples {
    // Для каждого элемента проверяем каждый элемент: два вложенных обхода.
    public static int countEqualPairs(int[] values) {
        int count = 0;
        for (int left : values) {
            for (int right : values) {
                if (left == right) {
                    count++;
                }
            }
        }
        return count;
    }

    // Два обхода подряд остаются линейными.
    public static int sumTwice(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        for (int value : values) {
            result += value;
        }
        return result;
    }

    // Каждый раз уменьшаем оставшееся число вдвое.
    public static int halvings(int n) {
        int steps = 0;
        for (int remaining = n; remaining > 1; remaining /= 2) {
            steps++;
        }
        return steps;
    }

    // В обеих ветках одно действие. Условие здесь можно упростить вручную.
    public static int identicalBranches(int value) {
        if (value > 0) {
            return value * 2;
        } else {
            return value * 2;
        }
    }

    // Одинаковые три действия встречаются дважды.
    public static int repeatedWork(int accumulator) {
        accumulator += 1;
        accumulator *= 2;
        accumulator -= 3;
        accumulator += 1;
        accumulator *= 2;
        accumulator -= 3;
        return accumulator;
    }

    // Пустой обработчик скрывает причину неудачи. Проверьте, было ли это задумано.
    public static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    // Четыре проверки друг внутри друга труднее читать.
    public static int nestedConditions(int value) {
        if (value > 0) {
            if (value < 100) {
                if (value % 2 == 0) {
                    if (value % 3 == 0) {
                        return value;
                    }
                }
            }
        }
        return 0;
    }

    // Граница while не распознана: панель честно покажет неопределённость.
    public static int untilZero(int value) {
        while (value > 0) {
            value--;
        }
        return value;
    }
}
