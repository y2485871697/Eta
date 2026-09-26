package vd.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Looper;

/**
 * Authenticated local IPC for the owner.
 *
 * <p>The socket is bound in the Linux abstract namespace so it never appears as a file another app
 * can open. Two checks gate every command:
 * <ul>
 *   <li>the peer uid reported by the kernel must equal the uid the owner was started for;</li>
 *   <li>the request must carry the per-session token that was printed once on the owner's stdout.</li>
 * </ul>
 *
 * <p>At most one connection is active at a time; a second peer is answered with
 * {@code SESSION_BUSY} and closed. Parsing and the token check happen on the connection thread, but
 * the command itself is posted to the owner's looper handler and the thread waits for the result,
 * so operations are serialized even with a future multi-connection owner.
 */
final class OwnerIpcServer {
    interface Dispatcher {
        /** Never throws: every failure becomes an {@code ok=false} response. */
        JSONObject dispatch(OwnerProtocol.Request request);

        /** True once the session has been torn down and the owner should stop. */
        boolean shouldStop();
    }

    private static final int READ_LIMIT = OwnerProtocol.MAX_REQUEST_CHARS + 1;

    private final LocalServerSocket server;
    private final String socketName;
    private final int allowedUid;
    private final String token;
    private final Handler handler;
    private final Dispatcher dispatcher;
    private final Runnable onStop;

    private final Object activeLock = new Object();
    private final AtomicBoolean quitPosted = new AtomicBoolean();
    private Connection activeConnection;
    private volatile boolean mutationUncertain;
    private volatile boolean stopped;
    private Thread acceptThread;

    OwnerIpcServer(String socketName, int allowedUid, String token, Handler handler,
            Dispatcher dispatcher, Runnable onStop) throws OwnerException {
        if (socketName == null || socketName.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "socket name");
        }
        this.socketName = socketName;
        this.allowedUid = allowedUid;
        this.token = token;
        this.handler = handler;
        this.dispatcher = dispatcher;
        this.onStop = onStop;
        try {
            this.server = new LocalServerSocket(socketName);
        } catch (IOException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "socket bind");
        }
    }

    String socketName() {
        return socketName;
    }

    void start() {
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "vd-owner-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        stopped = true;
        synchronized (activeLock) {
            if (activeConnection != null) closeQuietly(activeConnection.socket);
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private void acceptLoop() {
        while (!stopped) {
            LocalSocket socket;
            try {
                socket = server.accept();
            } catch (IOException ex) {
                if (stopped) {
                    return;
                }
                continue;
            }
            Thread connection = new Thread(new Connection(socket), "vd-owner-conn");
            connection.setDaemon(true);
            connection.start();
        }
    }

    private final class Connection implements Runnable {
        private final LocalSocket socket;

        Connection(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            boolean claimed = false;
            try {
                socket.setSoTimeout(10_000);
                Credentials credentials = socket.getPeerCredentials();
                if (credentials == null || credentials.getUid() != allowedUid) {
                    writeLine(socket, OwnerProtocol.fail(null, OwnerProtocol.ERROR_PEER_REJECTED,
                            "peer uid"));
                    return;
                }
                synchronized (activeLock) {
                    if (activeConnection != null) {
                        writeLine(socket, OwnerProtocol.fail(null,
                                OwnerProtocol.ERROR_SESSION_BUSY, "session busy"));
                        return;
                    }
                    activeConnection = this;
                    claimed = true;
                }
                serve();
            } catch (IOException ignored) {
                // Transport loss alone must not release or terminate the display.
            } finally {
                if (claimed) {
                    synchronized (activeLock) {
                        if (activeConnection == this) {
                            activeConnection = null;
                        }
                    }
                }
                closeQuietly(socket);
            }
        }

        private void serve() throws IOException {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            boolean authenticated = false;
            while (!stopped) {
                String line = readLine(socket, authenticated);
                if (line == null) {
                    return;
                }
                if (line.isEmpty()) {
                    continue;
                }
                if (line.length() > OwnerProtocol.MAX_REQUEST_CHARS) {
                    writeLine(out, OwnerProtocol.fail(null, OwnerProtocol.ERROR_REQUEST_TOO_LARGE,
                            "line too long"));
                    return;
                }
                OwnerProtocol.Request request;
                try {
                    request = OwnerProtocol.parseRequest(line);
                } catch (OwnerProtocolException ex) {
                    writeLine(out, OwnerProtocol.fail(null, ex.code, ex.getMessage()));
                    continue;
                }
                if (!OwnerProtocol.constantTimeEquals(request.token, token)) {
                    writeLine(out, OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_AUTH_REJECTED,
                            "token"));
                    return;
                }
                authenticated = true;
                if (mutationUncertain && !OwnerProtocol.OP_STATUS.equals(request.op)
                        && !OwnerProtocol.OP_SNAPSHOT.equals(request.op)) {
                    writeLine(out, OwnerProtocol.fail(request.op, "SESSION_UNCERTAIN", "mutation outcome unknown"));
                    return;
                }
                OwnerReleaseCompletion completion = new OwnerReleaseCompletion();
                try {
                    JSONObject response = execute(request, completion);
                    if (OwnerProtocol.OP_STATUS.equals(request.op)) {
                        try { response.put("mutationUncertain", mutationUncertain || response.optBoolean("mutationUncertain", false)); }
                        catch (org.json.JSONException ex) { throw new IOException("status serialization failed"); }
                    }
                    try { writeLine(socket, out, response); }
                    catch (IOException ex) { mutationUncertain = true; throw ex; }
                } finally {
                    // Success, failed flush and abandoned waiters all complete the delivery attempt.
                    // If dispatch is still running, it will observe this and stop only after release
                    // is verified. The existing write watchdog bounds a stalled response attempt.
                    if (completion.responseFinished()) stopAndQuit();
                }
            }
        }

        /** Runs the op on the owner looper and waits for its response. */
        private JSONObject execute(OwnerProtocol.Request request,
                final OwnerReleaseCompletion completion) throws IOException {
            final ResultBox box = new ResultBox(request.payload.optLong("timeoutMs",15000L));
            Runnable action = new Runnable() {
                @Override
                public void run() {
                    synchronized (box) {
                        if (box.cancelled || SystemClock.elapsedRealtime() >= box.deadline) { box.cancelled=true; return; }
                        box.started = true;
                    }
                    try {
                        JSONObject response;
                        try {
                            response = dispatcher.dispatch(request);
                        } catch (Throwable unexpected) {
                            response = OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL,
                                    unexpected.getClass().getSimpleName());
                        }
                        box.complete(response);
                    } finally {
                        // A running release may finish after its waiter timed out/disconnected.
                        // Response delivery is not evidence of release; only the owner is.
                        if (completion.dispatchFinished(dispatcher.shouldStop())) stopAndQuit();
                    }
                }
            };
            boolean posted = handler.post(action);
            if (!posted) {
                throw new IOException("owner looper stopped");
            }
            JSONObject response = box.await();
            if (response == null) {
                synchronized (box) { box.cancelled = true; mutationUncertain = true; }
                handler.removeCallbacks(action);
                throw new IOException("owner did not answer");
            }
            return response;
        }

        private void stopAndQuit() {
            if (!dispatcher.shouldStop() || !quitPosted.compareAndSet(false, true)) return;
            stop();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onStop.run();
                    // Main callback terminates ONLY after confirmed release.
                }
            });
        }
    }

    private static final class ResultBox {
        final long deadline;
        ResultBox(long timeout) { deadline=SystemClock.elapsedRealtime()+Math.max(1000L,Math.min(120000L,timeout)); }
        private JSONObject response;
        private boolean done;
        private boolean started;
        private boolean cancelled;

        synchronized void complete(JSONObject value) {
            response = value;
            done = true;
            notifyAll();
        }

        synchronized JSONObject await() {
            while (!done) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    cancelled=true;
                    return null;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancelled=true;
                    return null;
                }
            }
            return response;
        }
    }

    private static String readLine(LocalSocket socket, boolean authenticated) throws IOException {
        InputStream in=socket.getInputStream();
        socket.setSoTimeout(authenticated?0:10000);
        int next=in.read();
        if(next<0)return null;
        long deadline=SystemClock.elapsedRealtime()+10000L;
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        while(next>=0 && next!='\n') {
            if(buffer.size()>=READ_LIMIT)throw new IOException("request too large");
            if(next!='\r')buffer.write(next);
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining<=0)throw new IOException("frame deadline");
            socket.setSoTimeout((int)remaining);
            next=in.read();
        }
        if(next<0)throw new IOException("incomplete frame");
        return new String(buffer.toByteArray(),StandardCharsets.UTF_8);
    }

    private static void writeLine(LocalSocket socket, JSONObject response) {
        try {
            writeLine(socket.getOutputStream(), response);
        } catch (IOException ignored) {
            // peer gone
        }
    }

    private static void writeLine(final LocalSocket socket, OutputStream out, JSONObject response) throws IOException {
        final AtomicBoolean finished = new AtomicBoolean();
        Thread watchdog = new Thread(new Runnable() { public void run() {
            try { Thread.sleep(10_000L); } catch (InterruptedException e) { return; }
            if (!finished.get()) closeQuietly(socket);
        }}, "vd-write-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try { writeLine(out, response); }
        finally { finished.set(true); watchdog.interrupt(); }
    }

    private static void writeLine(OutputStream out, JSONObject response) throws IOException {
        out.write(OwnerProtocol.encodeLine(response).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
