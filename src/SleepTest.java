public class SleepTest extends UserlandProcess {
    @Override
    public void main() {
        System.out.println("Sleeping for 1000 ms");
        OS.Sleep(1000);
        System.out.println("Awake");
        OS.Exit();
    }
}
