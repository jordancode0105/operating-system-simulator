import java.util.Arrays;

public class TestRandomOnce extends UserlandProcess {
    private final String openStr;
    public TestRandomOnce(String openStr) { this.openStr = openStr; }

    @Override
    public void main() {
        int fd = OS.Open(openStr);
        System.out.println("[Rnd] open='" + openStr + "' fd=" + fd);
        byte[] b = OS.Read(fd, 8);
        System.out.println("[Rnd] bytes=" + Arrays.toString(b));
        OS.Close(fd);
        OS.Exit();
    }
}
