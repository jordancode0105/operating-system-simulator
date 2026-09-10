public class MemSmoke extends UserlandProcess {
    @Override public void main() {
        int sz = 4 * Hardware.PAGE_SIZE;
        int base = OS.AllocateMemory(sz);
        System.out.println("[MemSmoke] base=" + base);
        for (int i = 0; i < sz; i++) Hardware.Write(base + i, (byte)(i & 0x7F));
        for (int i = 0; i < sz; i++) {
            byte got = Hardware.Read(base + i);
            if (got != (byte)(i & 0x7F)) {
                System.out.println("[MemSmoke] MISMATCH at " + i);
                OS.Exit(); return;
            }
        }
        System.out.println("[MemSmoke] OK");
        System.out.println("[MemSmoke] free=" + OS.FreeMemory(base, sz));
        OS.Exit();
    }
}
