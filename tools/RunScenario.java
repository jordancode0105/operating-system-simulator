/** Command-line adapter for the original console scenarios. */
public class RunScenario {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -cp build/classes RunScenario <process-class> [constructor arguments]");
            System.exit(2);
        }
        Class<? extends UserlandProcess> type = Class.forName(args[0]).asSubclass(UserlandProcess.class);
        UserlandProcess process;
        if (type == Piggy.class) {
            process = new Piggy(args.length > 1 ? Integer.parseInt(args[1]) : 1);
        } else {
            String[] values = java.util.Arrays.copyOfRange(args, 1, args.length);
            Class<?>[] types = new Class<?>[values.length];
            java.util.Arrays.fill(types, String.class);
            process = type.getConstructor(types).newInstance((Object[]) values);
        }
        OS.Startup(process);
    }
}
