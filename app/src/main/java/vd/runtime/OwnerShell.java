package vd.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Runs one child process from an explicit argv array.
 *
 * <p>There is no shell: the first element is the executable and the rest are literal arguments.
 * Callers must pass already-validated tokens, so no user text can become a shell or {@code am}
 * option. Output is bounded and the pipe keeps draining after the cap so a chatty child cannot
 * block. Only this child is ever terminated; no other process is signalled.
 */
final class OwnerShell {
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 32 * 1024;

    static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean timedOut;
        final boolean truncated;
        final boolean exited;

        Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated,
                boolean exited) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.truncated = truncated;
            this.exited = exited;
        }

        boolean success() {
            return exited && !timedOut && exitCode == 0;
        }

        /** Short combined diagnostic: exit code plus the first output line that looks useful. */
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("exit=").append(exited ? Integer.toString(exitCode) : "none");
            if (timedOut) {
                sb.append(" timeout");
            }
            if (truncated) {
                sb.append(" truncated");
            }
            String detail = firstLine(stderr);
            if (detail.isEmpty()) {
                detail = firstLine(stdout);
            }
            if (!detail.isEmpty()) {
                sb.append(' ').append(detail);
            }
            return sb.toString();
        }

        private static String firstLine(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            int end = text.indexOf('\n');
            String line = end < 0 ? text : text.substring(0, end);
            return line.trim();
        }
    }

    private OwnerShell() {
    }

    static Result run(String[] argv, long timeoutMillis, int maxOutputBytes) throws OwnerException {
        if (argv == null || argv.length == 0 || argv[0] == null || argv[0].isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "empty argv");
        }
        for (int i = 0; i < argv.length; i++) {
            if (argv[i] == null) {
                throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, "null argv");
            }
        }
        long timeout = Math.max(500L, Math.min(timeoutMillis, 120_000L));
        int cap = Math.max(1024, Math.min(maxOutputBytes, 4 * 1024 * 1024));

        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(false).start();
        } catch (IOException ex) {
            throw new OwnerException(OwnerProtocol.ERROR_INTERNAL, argv[0] + " unavailable");
        }
        try {
            closeQuietly(process.getOutputStream());
        } catch (Throwable ignored) {
            // the child may not read stdin at all
        }

        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "vd-owner-shell-io");
                thread.setDaemon(true);
                return thread;
            }
        };
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2, factory);
        Future<Bounded> outFuture = pool.submit(new ReadTask(process.getInputStream(), cap));
        Future<Bounded> errFuture = pool.submit(new ReadTask(process.getErrorStream(), cap));

        boolean completed;
        try {
            completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        if (!completed) {
            terminate(process);
        }
        Bounded stdout = take(outFuture);
        Bounded stderr = take(errFuture);
        pool.shutdownNow();

        int exitCode = -1;
        boolean exited = false;
        if (completed) {
            try {
                exitCode = process.exitValue();
                exited = true;
            } catch (IllegalThreadStateException ex) {
                exited = false;
            }
        }
        return new Result(exitCode, stdout.text, stderr.text, !completed,
                stdout.truncated || stderr.truncated, exited);
    }

    private static Bounded take(Future<Bounded> future) {
        try {
            return future.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return Bounded.EMPTY;
        }
    }

    private static final long IO_JOIN_TIMEOUT_MS = 2_000L;

    private static void terminate(Process process) {
        try {
            if (process.isAlive()) {
                process.destroy(); // the child this call started, never a user task
                if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static final class ReadTask implements java.util.concurrent.Callable<Bounded> {
        private final InputStream stream;
        private final int cap;

        ReadTask(InputStream stream, int cap) {
            this.stream = stream;
            this.cap = cap;
        }

        @Override
        public Bounded call() {
            if (stream == null) {
                return Bounded.EMPTY;
            }
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            boolean truncated = false;
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (kept.size() < cap) {
                        int room = cap - kept.size();
                        kept.write(buffer, 0, Math.min(room, read));
                        if (read > room) {
                            truncated = true;
                        }
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // keep whatever was read
            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            return new Bounded(new String(kept.toByteArray(), StandardCharsets.UTF_8), truncated);
        }
    }

    private static final class Bounded {
        static final Bounded EMPTY = new Bounded("", false);
        final String text;
        final boolean truncated;

        Bounded(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
