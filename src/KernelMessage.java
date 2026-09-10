import java.util.Arrays;

public class KernelMessage {
    public final int fromPid;
    public final int toPid;
    public final int purpose;      // message type/purpose
    public final byte[] data;

    public KernelMessage(int fromPid, int toPid, int purpose, byte[] data) {
        this.fromPid = fromPid;
        this.toPid = toPid;
        this.purpose = purpose;
        this.data = (data == null) ? null : Arrays.copyOf(data, data.length);
    }

    // Copy constructor
    public KernelMessage(KernelMessage other) {
        this(
                other.fromPid,
                other.toPid,
                other.purpose,
                other.data
        );
    }

    @Override
    public String toString() {
        return "KernelMessage{from=" + fromPid +
                ", to=" + toPid +
                ", purpose=" + purpose +
                ", dataLen=" + (data == null ? 0 : data.length) +
                "}";
    }
}
