import java.io.IOException;
import java.io.RandomAccessFile;

public class FakeFileSystem implements Device {

    private final RandomAccessFile[] files = new RandomAccessFile[10];

    @Override
    public int Open(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("Filename must be non-empty.");
        }
        int slot = -1;
        for (int i = 0; i < files.length; i++) {
            if (files[i] == null) { slot = i; break; }
        }
        if (slot == -1) return -1;

        try {
            files[slot] = new RandomAccessFile(filename, "rw");
            return slot;
        } catch (IOException e) {
            throw new RuntimeException("Open failed for: " + filename, e);
        }
    }

    @Override
    public void Close(int id) {
        if (id < 0 || id >= files.length) return;
        RandomAccessFile raf = files[id];
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            files[id] = null; // free slot
        }
    }

    @Override
    public byte[] Read(int id, int size) {
        if (size <= 0 || id < 0 || id >= files.length || files[id] == null) return new byte[0];
        byte[] buf = new byte[size];
        try {
            int n = files[id].read(buf);
            if (n <= 0) return new byte[0];
            if (n == size) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Read failed (id=" + id + ")", e);
        }
    }

    @Override
    public void Seek(int id, int to) {
        if (id < 0 || id >= files.length || files[id] == null) return;
        try {
            files[id].seek(Math.max(0L, (long) to));
        } catch (IOException e) {
            throw new RuntimeException("Seek failed (id=" + id + ")", e);
        }
    }

    @Override
    public int Write(int id, byte[] data) {
        if (id < 0 || id >= files.length || files[id] == null || data == null || data.length == 0) return 0;
        try {
            files[id].write(data);
            return data.length;
        } catch (IOException e) {
            throw new RuntimeException("Write failed (id=" + id + ")", e);
        }
    }
}
