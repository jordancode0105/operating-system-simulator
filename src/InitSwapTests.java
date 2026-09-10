public class InitSwapTests extends UserlandProcess {
    @Override public void main() {
        OS.createProcess(new MemSmoke(), OS.PriorityType.interactive);

        for (int i = 1; i <= 20; i++) {
            OS.createProcess(new Piggy(i), OS.PriorityType.interactive);
        }

        OS.Exit();
    }
}
