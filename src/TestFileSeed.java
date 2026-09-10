public class TestFileSeed extends UserlandProcess {
    private final String openStr;
    public TestFileSeed(String openStr) { this.openStr = openStr; }

    @Override
    public void main() {
        int fd = OS.Open(openStr);
        OS.Seek(fd, 0);
        OS.Write(fd, new byte[]{1,2,3,4,5,6,7,8});
        OS.Close(fd);
        OS.Exit();
    }
}
