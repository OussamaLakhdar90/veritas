package ca.bnc.qe.veritas.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Emits the three TestNG suites (smoke / regression / full) deterministically from the generated test classes
 * — no LLM. Per the template's Suite XML section the suites differ only by the group filter:
 * smoke = P0, regression = P0+P1, full = all groups. Returns {@code {suiteFileName -> xml}} (written under
 * {@code suites/}). Test classes are detected from the written paths (…Test.java under a test source dir).
 */
@Component
public class SuiteXmlEmitter {

    public Map<String, String> emit(String serviceName, List<String> writtenPaths) {
        List<String> classes = new ArrayList<>();
        for (String p : writtenPaths == null ? List.<String>of() : writtenPaths) {
            if (isTestClass(p)) {
                classes.add(fqcn(p));
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (classes.isEmpty()) {
            return out;   // nothing to run → no suites
        }
        String svc = serviceName.replaceAll("[^A-Za-z0-9._-]", "-");
        out.put(svc + "-smoke.xml", suite(serviceName, "Smoke (P0)",
                "<groups><run><include name=\"P0\"/></run></groups>", classes));
        out.put(svc + "-regression.xml", suite(serviceName, "Regression (P0+P1)",
                "<groups><run><include name=\"P0\"/><include name=\"P1\"/></run></groups>", classes));
        out.put(svc + ".xml", suite(serviceName, "API Tests — Full", "", classes));
        return out;
    }

    private String suite(String serviceName, String label, String groupFilter, List<String> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE suite SYSTEM \"https://testng.org/testng-1.0.dtd\">\n");
        sb.append("<suite name=\"").append(esc(serviceName)).append(' ').append(esc(label)).append("\" verbose=\"1\">\n");
        sb.append("  <test name=\"").append(esc(serviceName)).append(" Tests\">\n");
        if (!groupFilter.isEmpty()) {
            sb.append("    ").append(groupFilter).append('\n');
        }
        sb.append("    <classes>\n");
        for (String c : classes) {
            sb.append("      <class name=\"").append(esc(c)).append("\"/>\n");
        }
        sb.append("    </classes>\n  </test>\n</suite>\n");
        return sb.toString();
    }

    private boolean isTestClass(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replace('\\', '/');
        if (!(p.endsWith("Test.java") || p.endsWith("Tests.java")) || !p.contains("test")) {
            return false;
        }
        // Abstract base classes (BaseApiTest, AbstractApiTest) are not runnable suite entries.
        String simple = p.substring(p.lastIndexOf('/') + 1);
        return !simple.startsWith("Base") && !simple.startsWith("Abstract");
    }

    /** "src/test/java/pkg/FooTest.java" -> "pkg.FooTest". */
    private String fqcn(String path) {
        String p = path.replace('\\', '/');
        for (String root : new String[] {"src/test/java/", "src/main/java/"}) {
            int idx = p.indexOf(root);
            if (idx >= 0) {
                p = p.substring(idx + root.length());
                break;
            }
        }
        if (p.endsWith(".java")) {
            p = p.substring(0, p.length() - 5);
        }
        return p.replace('/', '.');
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
