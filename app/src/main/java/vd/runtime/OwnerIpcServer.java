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
import android.net.LocalSocketAddress;
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
    private Object activeConnection;
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
            this.server = new LocalServerSocket(
                    new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
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
                // peer vanished; the session is simply gone
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
            while (!stopped) {
                String line = readLine(in);
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
                JSONObject response = execute(request);
                writeLine(out, response);
                if (dispatcher.shouldStop()) {
                    stopAndQuit();
                    return;
                }
            }
        }

        /** Runs the op on the owner looper and waits for its response. */
        private JSONObject execute(OwnerProtocol.Request request) throws IOException {
            final ResultBox box = new ResultBox();
            boolean posted = handler.post(new Runnable() {
                @Override
                public void run() {
                    JSONObject response;
                    try {
                        response = dispatcher.dispatch(request);
                    } catch (Throwable unexpected) {
                        response = OwnerProtocol.fail(request.op, OwnerProtocol.ERROR_INTERNAL,
                                unexpected.getClass().getSimpleName());
                    }
                    box.complete(response);
                }
            });
            if (!posted) {
                throw new IOException("owner looper stopped");
            }
            JSONObject response = box.await();
            if (response == null) {
                throw new IOException("owner did not answer");
            }
            return response;
        }

        private void stopAndQuit() {
            stop();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onStop.run();
                    Looper.myLooper().quit();
                }
            });
        }
    }

    private static final class ResultBox {
        private JSONObject response;
        private boolean done;

        synchronized void complete(JSONObject value) {
            response = value;
            done = true;
            notifyAll();
        }

        synchronized JSONObject await() {
            long deadline = System.currentTimeMillis() + 120_000L;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return response;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            if (read == '\r') {
                continue;
            }
            if (buffer.size() < READ_LIMIT) {
                buffer.write(read);
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeLine(LocalSocket socket, JSONObject response) {
        try {
            writeLine(socket.getOutputStream(), response);
        } catch (IOException ignored) {
            // peer gone
        }
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
