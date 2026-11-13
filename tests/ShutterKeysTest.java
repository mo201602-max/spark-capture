package jp.sparkcapture;

public class ShutterKeysTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (int code : new int[]{24,25,27,79,85,126,127})
            check(ShutterKeys.isShutter(code), "shutter key not recognized: " + code);
        for (int code : new int[]{0,3,4,23,26,28,66,80})
            check(!ShutterKeys.isShutter(code), "unrelated/focus key triggered: " + code);
        check(ShutterKeys.shouldTrigger(0,0,false), "initial press must trigger");
        check(!ShutterKeys.shouldTrigger(1,0,false), "release must not trigger twice");
        check(!ShutterKeys.shouldTrigger(0,1,false), "held key must not repeat");
        check(!ShutterKeys.shouldTrigger(0,12,false), "long hold must not repeat");
        check(!ShutterKeys.shouldTrigger(0,0,true), "canceled press must not trigger");
        System.out.println("Shutter key recognition and duplicate-press tests passed");
    }
}
