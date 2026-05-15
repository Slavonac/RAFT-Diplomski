package raf.rs.node;

import io.grpc.*;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class PauseInterceptor implements ServerInterceptor {

    private static final Set<String> ALLOWED_WHEN_PAUSED = Set.of(
            "RAFT/Stop", "RAFT/Pause", "RAFT/Resume", "RAFT/GetNodeLog"
    );

    private final AtomicBoolean paused = new AtomicBoolean(false);

    public void setPaused(boolean value) { paused.set(value); }
    public boolean isPaused() { return paused.get(); }

    @Override
    public <Req, Res> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Res> call,
            Metadata headers,
            ServerCallHandler<Req, Res> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();


        if (paused.get() && !ALLOWED_WHEN_PAUSED.contains(methodName)) {
            call.close(Status.UNAVAILABLE.withDescription("Node is paused"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
