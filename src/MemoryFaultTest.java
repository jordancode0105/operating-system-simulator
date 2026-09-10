public class MemoryFaultTest extends UserlandProcess {
    @Override
    public void main() {
        System.out.println("[MemFaultTest] starting; will intentionally fault");

        // Pick address not allocated
        int badAddr = 50 * Hardware.PAGE_SIZE; // no AllocateMemory called
        byte b = Hardware.Read(badAddr);
        System.out.println("MemoryFaultTest THIS SHOULD NOT PRINT value=" + b);

        OS.Exit();
    }
}
