public class InitPingPong extends UserlandProcess {
    @Override
    public void main() {
        OS.createProcess(new Ping(), OS.PriorityType.interactive);
        OS.createProcess(new Pong(), OS.PriorityType.interactive);
        OS.Exit();
    }
}
