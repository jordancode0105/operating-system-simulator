public class PIDTest extends UserlandProcess {
    @Override
    public void main() {
        int pid = OS.GetPID();
        System.out.println("PidTester: my PID is " + pid);
        OS.Exit();
    }
}
