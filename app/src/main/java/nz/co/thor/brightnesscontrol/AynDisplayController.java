package nz.co.thor.brightnesscontrol;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

final class AynDisplayController {
    private static final int SET_DISPLAY_BRIGHTNESS = 16386;
    private static final int GET_DISPLAY_BRIGHTNESS = 16385;

    private AynDisplayController() {}

    static boolean setBrightness(int displayId, int value) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            IBinder controller = (IBinder) getService.invoke(null, "SettingsController");
            if (controller == null) return false;

            request.writeInt(displayId);
            request.writeInt(value);
            request.writeBoolean(false);
            if (!controller.transact(SET_DISPLAY_BRIGHTNESS, request, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == value;
        } catch (Exception exception) {
            return false;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    static int getBrightness(int displayId) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            IBinder controller = (IBinder) getService.invoke(null, "SettingsController");
            if (controller == null) return -1;
            request.writeInt(displayId);
            if (!controller.transact(GET_DISPLAY_BRIGHTNESS, request, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Exception exception) {
            return -1;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }
}
