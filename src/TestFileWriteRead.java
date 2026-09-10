import java.util.Arrays;

public class TestFileWriteRead extends UserlandProcess {
    private final String openStr;
    public TestFileWriteRead(String openStr) { this.openStr = openStr; }

    @Override
    public void main() {
        int fd = OS.Open(openStr);
        System.out.println("[FWR] fd=" + fd);
        byte[] w = new byte[]{10,20,30,40};
        int n = OS.Write(fd, w);
        OS.Seek(fd, 0);
        byte[] r = OS.Read(fd, 4);
        System.out.println("[FWR] wrote=" + n + " read=" + Arrays.toString(r));
        OS.Close(fd);
        OS.Exit();
    }
}
