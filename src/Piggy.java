import java.util.Random;

public class Piggy extends UserlandProcess {
    private final int id;
    private final Random rnd = new Random();

    public Piggy(int id) { this.id = id; }

    @Override public void main() {
        final int PAGES = 100;
        final int SIZE  = PAGES * Hardware.PAGE_SIZE;

        int base = OS.AllocateMemory(SIZE);
        System.out.println("[Piggy" + id + "] base=" + base);
        if (base < 0) { System.out.println("[Piggy" + id + "] alloc failed"); OS.Exit(); return; }
        for (int p = 0; p < PAGES; p++) {
            int va = base + p * Hardware.PAGE_SIZE;
            Hardware.Write(va, (byte)(id ^ p));
            if ((p & 15) == 0) { cooperate(); OS.Sleep(1); } // let others run to cause evictions
        }
        System.out.println("[Piggy" + id + "] wrote " + PAGES + " pages");

        // Touch a random sample of pages to force swap-ins and verify data
        for (int i = 0; i < 50; i++) {
            int p = rnd.nextInt(PAGES);
            int va = base + p * Hardware.PAGE_SIZE;
            byte got = Hardware.Read(va);
            byte expect = (byte)(id ^ p);
            if (got != expect) {
                System.out.println("[Piggy" + id + "] VERIFY FAIL at page " + p +
                        " expect=" + (expect & 0xFF) + " got=" + (got & 0xFF));
                OS.Exit(); return;
            }
            if ((i & 7) == 0) { cooperate(); OS.Sleep(1); }
        }
        System.out.println("[Piggy" + id + "] verify OK");
        boolean freed = OS.FreeMemory(base, SIZE);
        System.out.println("[Piggy" + id + "] freed=" + freed);
        OS.Exit();
    }
}
