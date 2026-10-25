public class VisualWorkshop {
    // Все части этого метода можно настроить визуально.
    public static int calculate(int amount, boolean vip) {
        int count = 0;
        count += amount;
        if (vip && amount > 10) {
            count += 2;
        }
        for (int i = 0; i < 2; i++) {
            count += 1;
        }
        return Math.max(count, 0);
    }

    public static int sumPositive(int[] values) {
        int sum = 0;
        for (int value : values) {
            if (value > 0) {
                sum += value;
            }
        }
        return sum;
    }

    public static int countdown(int n) {
        int steps = 0;
        while (n > 0) {
            steps++;
            n--;
        }
        return steps;
    }

    public static int atLeastOnce(int n) {
        int steps = 0;
        do {
            steps++;
            n--;
        } while (n > 0);
        return steps;
    }

    public static String greeting(String name) {
        String message = "Привет, ";
        message += name;
        return message;
    }

    public static int twice(int value) {
        return value * 2;
    }

    public static void main(String[] args) {
        int result = calculate(12, true);
        System.out.println(result);
    }
}
