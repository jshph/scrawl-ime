import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

/** Shell-only UI probe. Does not simulate Wacom kernel input or prove EPD output. */
public final class ScrawlUiProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("press") || args[0].equals("release") || args[0].equals("select"))) {
            throw new IllegalArgumentException("press|release|select screenX screenY");
        }
        boolean press = !args[0].equals("release");
        float x = Float.parseFloat(args[1]), y = Float.parseFloat(args[2]);
        Class<?> managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = managerClass.getMethod("getInstance").invoke(null);
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        int[] actions = args[0].equals("select")
            ? new int[]{MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}
            : new int[]{MotionEvent.ACTION_HOVER_MOVE};
        long downTime = SystemClock.uptimeMillis();
        for (int action : actions) {
            coordinates.pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates}, 0,
                press ? MotionEvent.BUTTON_STYLUS_PRIMARY : 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0);
            try {
                Object accepted = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class)
                    .invoke(manager, event, 2);
                System.out.println("UI injection " + MotionEvent.actionToString(action) + " accepted=" + accepted);
                if (!Boolean.TRUE.equals(accepted)) System.exit(1);
            } finally {
                event.recycle();
            }
            SystemClock.sleep(70);
        }
    }
}
