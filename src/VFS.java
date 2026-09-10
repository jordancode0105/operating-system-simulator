import java.util.HashMap;
import java.util.Map;

public class VFS implements Device {

    // Map device names -> device instances
    private final Map<String, Device> registry = new HashMap<>();

    // Map VFS id -> (device, device-local id)
    private final Device[] vfsDevice = new Device[10];
    private final int[]    vfsLocalId = new int[10];

    public VFS() {
        // Register built-in devices (add more if you create them)
        registry.put("random", new RandomDevice());
        registry.put("file",   new FakeFileSystem());
    }

    /** Allow kernel to add custom devices or override defaults */
    public void registerDevice(String name, Device device) {
        if (name == null || name.isEmpty() || device == null) {
            throw new IllegalArgumentException("Invalid device registration");
        }
        registry.put(name.toLowerCase(), device);
    }

    @Override
    public int Open(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("Open string must be non-empty");

        String trimmed = s.trim();
        int space = trimmed.indexOf(' ');
        String devName = (space < 0) ? trimmed : trimmed.substring(0, space);
        String rest    = (space < 0) ? ""       : trimmed.substring(space + 1).trim();

        Device dev = registry.get(devName.toLowerCase());
        if (dev == null) throw new IllegalArgumentException("Unknown device: " + devName);

        int localId = dev.Open(rest);          // let the device parse its own args
        if (localId < 0) return -1;            // device had no capacity / failed

        // find free VFS slot
        for (int i = 0; i < vfsDevice.length; i++) {
            if (vfsDevice[i] == null) {
                vfsDevice[i] = dev;
                vfsLocalId[i] = localId;
                return i;                      // VFS id returned to kernel
            }
        }
        // No VFS capacity: close device side and fail
        dev.Close(localId);
        return -1;
    }

    @Override
    public void Close(int id) {
        if (!valid(id)) return;
        Device dev = vfsDevice[id];
        int    lid = vfsLocalId[id];
        // close on device, then clear mapping
        dev.Close(lid);
        vfsDevice[id] = null;
        vfsLocalId[id] = 0;
    }

    @Override
    public byte[] Read(int id, int size) {
        if (!valid(id)) return new byte[0];
        return vfsDevice[id].Read(vfsLocalId[id], size);
    }

    @Override
    public void Seek(int id, int to) {
        if (!valid(id)) return;
        vfsDevice[id].Seek(vfsLocalId[id], to);
    }

    @Override
    public int Write(int id, byte[] data) {
        if (!valid(id)) return 0;
        return vfsDevice[id].Write(vfsLocalId[id], data);
    }

    private boolean valid(int id) {
        return id >= 0 && id < vfsDevice.length && vfsDevice[id] != null;
    }
}
