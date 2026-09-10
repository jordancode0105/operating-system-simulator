public class Init extends UserlandProcess {
    @Override
    public void main() {
        OS.createProcess(new TestRandomOnce("random"), OS.PriorityType.interactive);
        OS.createProcess(new TestRandomOnce("random 42"), OS.PriorityType.interactive);

        OS.createProcess(new TestFileWriteRead("file simple.dat"), OS.PriorityType.interactive);

        OS.createProcess(new TestFileSeed("file shared.dat"), OS.PriorityType.background);
        OS.createProcess(new TestFileReader("file shared.dat", "R1"), OS.PriorityType.background);
        OS.createProcess(new TestFileReader("file shared.dat", "R2"), OS.PriorityType.background);

        OS.createProcess(new TestOpenCapacity(), OS.PriorityType.background);

        OS.Exit();
    }
}
