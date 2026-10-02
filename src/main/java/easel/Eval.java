package easel;

import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.Completeness;
import jdk.jshell.execution.DirectExecutionControl;
import jdk.jshell.execution.LoaderDelegate;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControl.ClassBytecodes;
import jdk.jshell.spi.ExecutionControl.ClassInstallException;
import jdk.jshell.spi.ExecutionControl.EngineTerminationException;
import jdk.jshell.spi.ExecutionControl.InternalException;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class Eval implements AutoCloseable {

    private static final Object STDOUT = new Object();

    private final JShell jshell;
    private final ParentLoader executionLoader;
    private boolean closed;

    public Eval(ClassLoader executionParent, Iterable<String> compilerClasspath) {
        ClassLoader parent = executionParent != null
                ? executionParent
                : Eval.class.getClassLoader();
        this.executionLoader = new ParentLoader(parent);
        this.jshell = JShell.builder()
                .executionEngine(new ExecutionControlProvider() {
                    @Override
                    public String name() {
                        return "local";
                    }

                    @Override
                    public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) {
                        // DirectExecutionControl = same JVM, calling thread.
                        // Stock LocalExecutionControl hops to a side thread.
                        return new DirectExecutionControl(executionLoader);
                    }
                }, Map.of())
                .build();
        for (String path : compilerClasspath) {
            if (path != null && !path.isBlank()) {
                jshell.addToClasspath(path);
            }
        }
    }

    public static Eval open(ClassLoader executionParent, File pluginJar) {
        return new Eval(executionParent, discover(executionParent, pluginJar));
    }

    @FunctionalInterface
    public interface Engine {
        Result eval(String code) throws Exception;
    }

    public record Result(String value, List<String> diagnostics, String exception, String stdout) {
        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            stdout = stdout == null ? "" : stdout;
        }

        static Result timeout() {
            return new Result(null, List.of(), "timed out after 15s", "");
        }

        static Result error(Throwable t) {
            return new Result(null, List.of(), stack(t), "");
        }

        boolean failed() {
            if (exception != null && !exception.isBlank()) {
                return true;
            }
            for (String d : diagnostics) {
                if (d.startsWith("error")) {
                    return true;
                }
            }
            return false;
        }
    }

    public synchronized Result eval(String code) {
        if (closed) {
            throw new IllegalStateException("JShell closed");
        }
        if (code == null || code.isBlank()) {
            return new Result(null, List.of(), null, "");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8);
        synchronized (STDOUT) {
            PrintStream prevOut = System.out;
            PrintStream prevErr = System.err;
            System.setOut(capture);
            System.setErr(capture);
            try {
                return run(code, buf);
            } finally {
                capture.flush();
                System.setOut(prevOut);
                System.setErr(prevErr);
            }
        }
    }

    private Result run(String code, ByteArrayOutputStream buf) {
        List<String> diagnostics = new ArrayList<>();
        String value = null;
        String exception = null;
        SourceCodeAnalysis analysis = jshell.sourceCodeAnalysis();
        String remaining = code;
        int guard = 0;
        while (guard++ < 10_000) {
            String stripped = remaining.strip();
            if (stripped.isEmpty()) {
                break;
            }
            SourceCodeAnalysis.CompletionInfo info = analysis.analyzeCompletion(remaining);
            Completeness completeness = info.completeness();
            if (completeness == Completeness.EMPTY) {
                break;
            }
            String source;
            String next;
            if (completeness.isComplete()) {
                source = info.source();
                next = info.remaining();
            } else {
                source = remaining;
                next = "";
            }
            if (source == null || source.isBlank()) {
                break;
            }
            List<SnippetEvent> events = jshell.eval(source);
            for (SnippetEvent event : events) {
                Snippet snippet = event.snippet();
                if (snippet != null) {
                    jshell.diagnostics(snippet).forEach(d -> diagnostics.add(formatDiag(d, source)));
                }
                if (event.exception() != null && exception == null) {
                    exception = formatException(event.exception());
                }
                if (event.status() == Snippet.Status.VALID && event.value() != null) {
                    value = event.value();
                }
            }
            if (next.equals(remaining)) {
                break;
            }
            remaining = next;
        }
        return new Result(value, diagnostics, exception, buf.toString(StandardCharsets.UTF_8));
    }

    private static String formatDiag(Diag d, String source) {
        String kind = d.isError() ? "error" : "warning";
        String loc = "";
        long pos = d.getPosition();
        if (pos != Diag.NOPOS && source != null) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < pos && i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            loc = line + ":" + col + ": ";
        }
        return kind + ": " + loc + d.getMessage(Locale.US);
    }

    private static String formatException(JShellException ex) {
        if (ex instanceof EvalException ee) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.print(ee.getExceptionClassName());
            if (ee.getMessage() != null && !ee.getMessage().isBlank()) {
                pw.print(": ");
                pw.print(ee.getMessage());
            }
            pw.println();
            for (StackTraceElement ste : ee.getStackTrace()) {
                pw.println("\tat " + ste);
            }
            pw.flush();
            return sw.toString().strip();
        }
        return stack(ex);
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().strip();
    }

    static List<String> discover(ClassLoader loader, File pluginJar) {
        Set<String> paths = new LinkedHashSet<>();
        addFile(paths, pluginJar);
        addClassLocation(paths, Eval.class);
        try {
            addClassLocation(paths, Class.forName("org.bukkit.Bukkit"));
        } catch (ClassNotFoundException ignored) {
            // unit tests run without Paper
        }
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String part : cp.split(File.pathSeparator)) {
                addFile(paths, new File(part));
            }
        }
        for (ClassLoader cl = loader; cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    addUrl(paths, url);
                }
            }
        }
        addTree(paths, Path.of("libraries"));
        addTree(paths, Path.of("cache", "libraries"));
        return List.copyOf(paths);
    }

    private static void addClassLocation(Set<String> paths, Class<?> type) {
        try {
            CodeSource cs = type.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                addUrl(paths, cs.getLocation());
            }
        } catch (Exception ignored) {
            // missing type or security manager
        }
    }

    private static void addUrl(Set<String> paths, URL url) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return;
        }
        try {
            addFile(paths, Path.of(url.toURI()).toFile());
        } catch (URISyntaxException e) {
            addFile(paths, new File(url.getPath()));
        }
    }

    private static void addFile(Set<String> paths, File file) {
        if (file == null) {
            return;
        }
        if (file.isFile() || file.isDirectory()) {
            paths.add(file.getAbsolutePath());
        }
    }

    private static void addTree(Set<String> paths, Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".jar")).forEach(p -> addFile(paths, p.toFile()));
        } catch (Exception ignored) {
            // optional Paper libraries folder
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        jshell.close();
        try {
            executionLoader.close();
        } catch (Exception ignored) {
            // already shutting down
        }
    }

    private static final class ParentLoader implements LoaderDelegate, AutoCloseable {
        private final Map<String, Class<?>> klasses = new HashMap<>();
        private final Inner loader;

        ParentLoader(ClassLoader parent) {
            this.loader = new Inner(parent);
        }

        @Override
        public void load(ClassBytecodes[] cbcs) throws ClassInstallException {
            boolean[] loaded = new boolean[cbcs.length];
            try {
                for (ClassBytecodes cbc : cbcs) {
                    loader.declare(cbc.name(), cbc.bytecodes());
                }
                for (int i = 0; i < cbcs.length; i++) {
                    Class<?> klass = loader.loadClass(cbcs[i].name());
                    klasses.put(cbcs[i].name(), klass);
                    loaded[i] = true;
                    klass.getDeclaredMethods();
                }
            } catch (Throwable ex) {
                throw new ClassInstallException("load: " + ex.getMessage(), loaded);
            }
        }

        @Override
        public void classesRedefined(ClassBytecodes[] cbcs) {
            for (ClassBytecodes cbc : cbcs) {
                loader.declare(cbc.name(), cbc.bytecodes());
            }
        }

        @Override
        public void addToClasspath(String cp) throws EngineTerminationException, InternalException {
            try {
                for (String path : cp.split(File.pathSeparator)) {
                    if (!path.isBlank()) {
                        loader.addPath(new File(path).toURI().toURL());
                    }
                }
            } catch (MalformedURLException ex) {
                throw new InternalException(ex.toString());
            }
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> klass = klasses.get(name);
            if (klass == null) {
                throw new ClassNotFoundException(name);
            }
            return klass;
        }

        private static final class Inner extends URLClassLoader {
            private final Map<String, byte[]> files = new HashMap<>();

            Inner(ClassLoader parent) {
                super(new URL[0], parent);
            }

            void declare(String name, byte[] bytes) {
                files.put(name, bytes);
            }

            void addPath(URL url) {
                addURL(url);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = files.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        }
    }
}
