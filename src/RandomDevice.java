import java.util.Random;

public class RandomDevice implements Device {

    private final Random[] devices = new Random[10];

    @Override
    public int Open(String s) {
        for (int i = 0; i < devices.length; i++) {
            if (devices[i] == null) {
                if (s != null && !s.isEmpty()) {
                    try {
                        int seed = Integer.parseInt(s);
                        devices[i] = new Random(seed);
                    } catch (NumberFormatException e) {
                        devices[i] = new Random();
                    }
                } else {
                    devices[i] = new Random();
                }
                return i; // id = index
            }
        }
        return -1;
    }

    @Override
    public void Close(int id) {
        if (id >= 0 && id < devices.length) {
            devices[id] = null; // free that slot
        }
    }

    @Override
    public byte[] Read(int id, int size) {
        if (id < 0 || id >= devices.length || devices[id] == null) {
            return new byte[0]; // invalid id
        }

        byte[] result = new byte[size];
        Random r = devices[id];

        for (int i = 0; i < size; i++) {
            result[i] = (byte) r.nextInt(256);
        }

        return result;
    }

    @Override
    public void Seek(int id, int to) {
        if (id < 0 || id >= devices.length || devices[id] == null) {
            return;
        }
        Random r = devices[id];
        for (int i = 0; i < to; i++) {
            r.nextInt();
        }
    }

    @Override
    public int Write(int id, byte[] data) {
        return 0;
    }
}
