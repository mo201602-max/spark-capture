package jp.sparkcapture;

/** Android KeyEvent values, kept free of Android dependencies for host tests. */
final class ShutterKeys {
    static boolean isShutter(int code) {
        return code == 24 || code == 25 || code == 27 || code == 79
                || code == 85 || code == 126 || code == 127;
    }
    static boolean shouldTrigger(int action, int repeats, boolean canceled) {
        return action == 0 && repeats == 0 && !canceled;
    }
}

