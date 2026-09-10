public class ExitTest extends UserlandProcess {
    @Override
    public void main() {
        System.out.println("Now exiting");
        OS.Exit();
    }
}
