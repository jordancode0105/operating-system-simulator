public class InitPagingTests extends UserlandProcess {
    @Override
    public void main() {
        OS.createProcess(new MemoryReadWriteTest(), OS.PriorityType.interactive);
        OS.createProcess(new MemoryExtendTest(),     OS.PriorityType.interactive);
        OS.createProcess(new MemoryFaultTest(),      OS.PriorityType.interactive);
        OS.Exit();
    }
}
