package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

/**
 * 缓动名表（与插件 {@code runtime.format.Easings} 顺序严格一致：枚举 ordinal = 协议值）。
 */
public final class Easing {

    public static final int LINEAR = 0;
    public static final int IN_SINE = 1;
    public static final int OUT_SINE = 2;
    public static final int IN_OUT_SINE = 3;
    public static final int IN_QUAD = 4;
    public static final int OUT_QUAD = 5;
    public static final int IN_OUT_QUAD = 6;
    public static final int IN_CUBIC = 7;
    public static final int OUT_CUBIC = 8;
    public static final int IN_OUT_CUBIC = 9;
    public static final int IN_QUART = 10;
    public static final int OUT_QUART = 11;
    public static final int IN_OUT_QUART = 12;
    public static final int IN_QUINT = 13;
    public static final int OUT_QUINT = 14;
    public static final int IN_OUT_QUINT = 15;
    public static final int IN_EXPO = 16;
    public static final int OUT_EXPO = 17;
    public static final int IN_OUT_EXPO = 18;
    public static final int IN_CIRC = 19;
    public static final int OUT_CIRC = 20;
    public static final int IN_OUT_CIRC = 21;
    public static final int IN_BACK = 22;
    public static final int OUT_BACK = 23;
    public static final int IN_OUT_BACK = 24;
    public static final int IN_ELASTIC = 25;
    public static final int OUT_ELASTIC = 26;
    public static final int IN_OUT_ELASTIC = 27;
    public static final int IN_BOUNCE = 28;
    public static final int OUT_BOUNCE = 29;
    public static final int IN_OUT_BOUNCE = 30;
    public static final int IN_SQUARE = 31;
    public static final int OUT_SQUARE = 32;
    public static final int IN_OUT_SQUARE = 33;

    /** 名称表：索引 = 协议值。 */
    public static final String[] NAMES = {
            "LINEAR",
            "IN_SINE", "OUT_SINE", "IN_OUT_SINE",
            "IN_QUAD", "OUT_QUAD", "IN_OUT_QUAD",
            "IN_CUBIC", "OUT_CUBIC", "IN_OUT_CUBIC",
            "IN_QUART", "OUT_QUART", "IN_OUT_QUART",
            "IN_QUINT", "OUT_QUINT", "IN_OUT_QUINT",
            "IN_EXPO", "OUT_EXPO", "IN_OUT_EXPO",
            "IN_CIRC", "OUT_CIRC", "IN_OUT_CIRC",
            "IN_BACK", "OUT_BACK", "IN_OUT_BACK",
            "IN_ELASTIC", "OUT_ELASTIC", "IN_OUT_ELASTIC",
            "IN_BOUNCE", "OUT_BOUNCE", "IN_OUT_BOUNCE",
            "IN_SQUARE", "OUT_SQUARE", "IN_OUT_SQUARE"};

    private Easing() {
    }

    public static int count() {
        return NAMES.length;
    }

    public static String name(int id) {
        return id >= 0 && id < NAMES.length ? NAMES[id] : NAMES[LINEAR];
    }

    public static int clamp(int id) {
        if (id < 0) {
            return LINEAR;
        }
        return id < NAMES.length ? id : NAMES.length - 1;
    }
}
