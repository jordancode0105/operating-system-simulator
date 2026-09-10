public class TestOpenCapacity extends UserlandProcess {
    @Override
    public void main() {
        int ok = 0, fail = 0;
        for (int i = 0; i < 12; i++) {
            int fd = OS.Open("random");
            if (fd >= 0) ok++; else fail++;
        }
        System.out.println("[Cap] opened=" + ok + " failed=" + fail);
        for (int i = 0; i < 10; i++) OS.Close(i);
        OS.Exit();
    }
}
