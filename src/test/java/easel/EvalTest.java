package easel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalTest {

    private Eval eval;

    @BeforeEach
    void open() {
        eval = new Eval(Eval.class.getClassLoader(), classpath());
    }

    @AfterEach
    void close() {
        if (eval != null) {
            eval.close();
        }
    }

    @Test
    void evaluatesExpression() {
        Eval.Result r = eval.eval("2 + 2");
        assertEquals("4", r.value());
        assertNull(r.exception());
        assertFalse(r.failed());
    }

    @Test
    void variablesSurvive() {
        eval.eval("int x = 7;");
        Eval.Result r = eval.eval("x");
        assertEquals("7", r.value());
        assertNull(r.exception());
    }

    @Test
    void multipleSnippetsInOneCall() {
        Eval.Result r = eval.eval("int a = 1; int b = 2; a + b");
        assertEquals("3", r.value());
        assertNull(r.exception());
        assertTrue(r.diagnostics().isEmpty());
    }

    @Test
    void capturesStdout() {
        Eval.Result r = eval.eval("System.out.print(\"hi\"); 3");
        assertEquals("3", r.value());
        assertEquals("hi", r.stdout());
    }

    @Test
    void capturesException() {
        Eval.Result r = eval.eval("throw new RuntimeException(\"boom\");");
        assertNotNull(r.exception());
        assertTrue(r.exception().contains("RuntimeException"));
        assertTrue(r.exception().contains("boom"));
        assertTrue(r.failed());
    }

    @Test
    void diagnosticsOnBadCode() {
        Eval.Result r = eval.eval("int x = ;");
        assertTrue(r.failed());
        assertFalse(r.diagnostics().isEmpty());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.startsWith("error")));
    }

    @Test
    void runsOnCallingThread() {
        String name = Thread.currentThread().getName();
        Eval.Result r = eval.eval("Thread.currentThread().getName()");
        assertEquals("\"" + name + "\"", r.value());
        assertNull(r.exception());
    }

    @Test
    void preludeIsEmpty() {
        Eval.Result missing = eval.eval("List.of(1)");
        assertTrue(missing.failed());
        Eval.Result ok = eval.eval("import java.util.List; List.of(1)");
        assertFalse(ok.failed());
        assertNull(ok.exception());
        assertNotNull(ok.value());
    }

    static List<String> classpath() {
        List<String> paths = new ArrayList<>();
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String part : cp.split(File.pathSeparator)) {
                if (!part.isBlank()) {
                    paths.add(part);
                }
            }
        }
        return paths;
    }
}
