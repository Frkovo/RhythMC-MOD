package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

/**
 * 缓动曲线（与插件 {@code runtime/utils/EasingUtils} 同表，序号与 {@link Easing} 一致）。
 * 时间轴 HUD 的事件曲线按它绘制，保证与世界内曲线形状一致。
 */
public final class EasingCurve {

    private EasingCurve() {
    }

    /** {@code a → b}，{@code t} ∈ [0,1]。 */
    public static double ease(double a, double b, double t, int easing) {
        if (t <= 0d) {
            return a;
        }
        if (t >= 1d) {
            return b;
        }
        return a + (b - a) * fn(t, Easing.clamp(easing));
    }

    private static double fn(double x, int easing) {
        double pi = Math.PI;
        return switch (easing) {
            case 0 -> x;
            case 1 -> 1 - Math.cos(x * pi / 2);
            case 2 -> Math.sin(x * pi / 2);
            case 3 -> -(Math.cos(pi * x) - 1) / 2;
            case 4 -> x * x;
            case 5 -> 1 - (1 - x) * (1 - x);
            case 6 -> x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
            case 7 -> x * x * x;
            case 8 -> 1 - Math.pow(1 - x, 3);
            case 9 -> x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
            case 10 -> x * x * x * x;
            case 11 -> 1 - Math.pow(1 - x, 4);
            case 12 -> x < 0.5 ? 8 * x * x * x * x : 1 - Math.pow(-2 * x + 2, 4) / 2;
            case 13 -> x * x * x * x * x;
            case 14 -> 1 - Math.pow(1 - x, 5);
            case 15 -> x < 0.5 ? 16 * x * x * x * x * x : 1 - Math.pow(-2 * x + 2, 5) / 2;
            case 16 -> x == 0 ? 0 : Math.pow(2, 10 * x - 10);
            case 17 -> x == 1 ? 1 : 1 - Math.pow(2, -10 * x);
            case 18 -> x == 0 ? 0 : x == 1 ? 1
                    : x < 0.5 ? Math.pow(2, 20 * x - 10) / 2 : (2 - Math.pow(2, -20 * x + 10)) / 2;
            case 19 -> 1 - Math.sqrt(1 - Math.pow(x, 2));
            case 20 -> Math.sqrt(1 - Math.pow(x - 1, 2));
            case 21 -> x < 0.5 ? (1 - Math.sqrt(1 - Math.pow(2 * x, 2))) / 2
                    : (Math.sqrt(1 - Math.pow(-2 * x + 2, 2)) + 1) / 2;
            case 22 -> 2.70158 * x * x * x - 1.70158 * x * x;
            case 23 -> 1 + 2.70158 * Math.pow(x - 1, 3) + 1.70158 * Math.pow(x - 1, 2);
            case 24 -> x < 0.5
                    ? (Math.pow(2 * x, 2) * ((1.70158 * 1.525 + 1) * 2 * x - 1.70158 * 1.525)) / 2
                    : (Math.pow(2 * x - 2, 2) * ((1.70158 * 1.525 + 1) * (x * 2 - 2) + 1.70158 * 1.525) + 2) / 2;
            case 25 -> x == 0 ? 0 : x == 1 ? 1
                    : -Math.pow(2, 10 * x - 10) * Math.sin((x * 10 - 10.75) * (2 * pi / 3));
            case 26 -> x == 0 ? 0 : x == 1 ? 1
                    : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * (2 * pi / 3)) + 1;
            case 27 -> x == 0 ? 0 : x == 1 ? 1 : x < 0.5
                    ? -(Math.pow(2, 20 * x - 10) * Math.sin((20 * x - 11.125) * (2 * pi / 4.5))) / 2
                    : (Math.pow(2, -20 * x + 10) * Math.sin((20 * x - 11.125) * (2 * pi / 4.5))) / 2 + 1;
            case 28 -> 1 - easeOutBounce(1 - x);
            case 29 -> easeOutBounce(x);
            case 30 -> x < 0.5 ? (1 - easeOutBounce(1 - 2 * x)) / 2 : (1 + easeOutBounce(2 * x - 1)) / 2;
            case 31 -> x < 1 ? 0 : 1;
            case 32 -> x <= 0 ? 0 : 1;
            default -> x < 0.5 ? 0 : 1;
        };
    }

    private static double easeOutBounce(double x) {
        final double n1 = 7.5625;
        final double d1 = 2.75;
        if (x < 1 / d1) {
            return n1 * x * x;
        } else if (x < 2 / d1) {
            double shifted = x - 1.5 / d1;
            return n1 * shifted * shifted + 0.75;
        } else if (x < 2.5 / d1) {
            double shifted = x - 2.25 / d1;
            return n1 * shifted * shifted + 0.9375;
        } else {
            double shifted = x - 2.625 / d1;
            return n1 * shifted * shifted + 0.984375;
        }
    }
}
