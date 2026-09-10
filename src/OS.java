import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

public class OS {
    private static Kernel ki;

    // Compatibility snapshots for inspection; syscall code uses its own request.
    public static List<Object> parameters = new ArrayList<>();
    public static Object retVal;

    public enum CallType {
        SwitchProcess,
        SendMessage,
        Open, Close, Read, Seek, Write,
        GetMapping,
        CreateProcess,
        Sleep,
        GetPID,
        AllocateMemory,
        FreeMemory,
        GetPIDByName,
        WaitForMessage,
        Exit
    }

    public static CallType currentCall;

    // One request owns its arguments and result until its caller resumes.
    static final class SystemCall {
        final CallType type;
        final List<Object> arguments;
        final Process caller;
        final Semaphore completed = new Semaphore(0);
        Object result;
        Throwable failure;

        SystemCall(CallType type, Process caller, Object... arguments) {
            this.type = type;
            this.caller = caller;
            this.arguments = Arrays.asList(arguments.clone());
        }
    }

    // Enter kernel, then immediately block the calling userland thread.
    private static Object invoke(CallType type, Object... arguments) {
        Process caller = Process.current();
        if (caller == null) throw new IllegalStateException("System calls require a running userland process");
        SystemCall call = new SystemCall(type, caller, arguments);
        caller.prepareToStop();
        ki.submit(call);
        caller.stop(); // yield to kernel; only this process's gate can resume it
        call.completed.acquireUninterruptibly();
        if (call.failure instanceof RuntimeException e) throw e;
        if (call.failure instanceof Error e) throw e;
        return call.result;
    }

    public static void switchProcess() {
        invoke(CallType.SwitchProcess);
    }

    public static void Startup(UserlandProcess init) {
        ki = new Kernel();
        // Idle keeps the OS alive when nothing else is runnable.
        // Register both processes before either can issue a syscall.
        ki.bootstrap(init);
        ki.start();
    }

    public enum PriorityType { realtime, interactive, background }

    public static int CreateProcess(UserlandProcess up) {
        return createProcess(up, PriorityType.interactive);
    }

    public static int createProcess(UserlandProcess up, PriorityType priority) {
        return (int) invoke(CallType.CreateProcess, up, priority);
    }

    public static int GetPID() {
        return (int) invoke(CallType.GetPID);
    }

    public static void Exit() {
        invoke(CallType.Exit);
    }

    // A user process that returns or throws still releases its kernel resources.
    static void processFinished(Process process) {
        if (ki != null && ki.isCurrentProcess(process)) {
            try {
                invoke(CallType.Exit);
            } catch (Process.ProcessExit expected) {
                // The Java thread is already unwinding its run() method.
            }
        }
    }

    public static void Sleep(int mills) {
        invoke(CallType.Sleep, mills);
    }

    public static int Open(String s) {
        return (int) invoke(CallType.Open, s);
    }

    public static void Close(int id) {
        invoke(CallType.Close, id);
    }

    public static byte[] Read(int id, int size) {
        return (byte[]) invoke(CallType.Read, id, size);
    }

    public static void Seek(int id, int to) {
        invoke(CallType.Seek, id, to);
    }

    public static int Write(int id, byte[] data) {
        return (int) invoke(CallType.Write, id, data);
    }

    public static void SendMessage(KernelMessage km) {
        invoke(CallType.SendMessage, km);
    }

    public static KernelMessage WaitForMessage() {
        KernelMessage message;
        do {
            message = (KernelMessage) invoke(CallType.WaitForMessage);
        } while (message == null);
        return message;
    }

    public static int GetPidByName(String name) {
        return (int) invoke(CallType.GetPIDByName, name);
    }

    // Ask kernel to load mapping for a virtual page into the TLB
    public static void GetMapping(int virtualPage) {
        invoke(CallType.GetMapping, virtualPage);
        // no return value; kernel either loads TLB or kills the process
    }

    // AllocateMemory: size must be multiple of page size
    public static int AllocateMemory(int size ) {
        if (size <= 0 || size % Hardware.PAGE_SIZE != 0) {
            return -1;
        }
        return (int) invoke(CallType.AllocateMemory, size); // starting virtual address or -1
    }

    // FreeMemory: pointer and size must be page-aligned
    public static boolean FreeMemory(int pointer, int size) {
        if (pointer < 0 ||
                pointer % Hardware.PAGE_SIZE != 0 ||
                size <= 0 ||
                size % Hardware.PAGE_SIZE != 0) {
            return false;
        }
        return (boolean) invoke(CallType.FreeMemory, pointer, size);
    }

}
