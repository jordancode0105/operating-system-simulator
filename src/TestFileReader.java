import java.util.Arrays;

public class TestFileReader extends UserlandProcess {
    private final String openStr, tag;
    public TestFileReader(String openStr, String tag) { this.openStr = openStr; this.tag = tag; }

    @Override
    public void main() {
        int fd = OS.Open(openStr);
        OS.Seek(fd, 0);
        byte[] r = OS.Read(fd, 8);
        System.out.println("[File-" + tag + "] " + Arrays.toString(r));
        OS.Close(fd);
        OS.Exit();
    }
}
