package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M13: {@code docs/security-checklist.md} covers all ten OWASP API Security Top 10 (2023) items, and every test it
 * cites as proof exists. A test class or method that is renamed or deleted fails here, so the checklist can't
 * quietly point at evidence that is no longer there.
 */
class SecurityChecklistTest {

    private static final Path CHECKLIST = Path.of("..", "docs", "security-checklist.md");
    private static final List<Path> TEST_ROOTS = List.of(
            Path.of("src", "test", "java"),
            Path.of("..", "risk-aml-service", "src", "test", "java"),
            Path.of("..", "event-contracts", "src", "test", "java"));

    private static final List<String> OWASP_2023 = List.of(
            "API1:2023 Broken Object Level Authorization",
            "API2:2023 Broken Authentication",
            "API3:2023 Broken Object Property Level Authorization",
            "API4:2023 Unrestricted Resource Consumption",
            "API5:2023 Broken Function Level Authorization",
            "API6:2023 Unrestricted Access to Sensitive Business Flows",
            "API7:2023 Server Side Request Forgery",
            "API8:2023 Security Misconfiguration",
            "API9:2023 Improper Inventory Management",
            "API10:2023 Unsafe Consumption of APIs");

    /** {@code `FooIT`}, optionally followed by {@code (`methodA`, `methodB`)}. */
    private static final Pattern CITATION = Pattern.compile("`([A-Z]\\w*(?:Test|IT))`(?: \\(([^)]*)\\))?");
    private static final Pattern METHOD = Pattern.compile("`(\\w+)`");

    private final String checklist = read(CHECKLIST);

    @Test
    void coversAllTenItemsWithControlsTestsAndGaps() {
        for (String item : OWASP_2023) {
            String section = section(item);
            assertThat(section).as(item).contains("**Risk here:**", "**Controls**", "**Tests**", "**Gaps");
        }
    }

    @Test
    void everyItemCitesAtLeastOneTest() {
        for (String item : OWASP_2023) {
            String tests = section(item).split("\\*\\*Tests\\*\\*", 2)[1];
            assertThat(CITATION.matcher(tests).find()).as("%s cites a test", item).isTrue();
        }
    }

    @Test
    void everyCitedTestClassAndMethodExists() {
        Map<String, Path> testClasses = testClasses();
        List<String> missing = new ArrayList<>();
        Matcher citation = CITATION.matcher(checklist);
        while (citation.find()) {
            String type = citation.group(1);
            Path file = testClasses.get(type);
            if (file == null) {
                missing.add(type);
                continue;
            }
            if (citation.group(2) != null) {
                String source = read(file);
                Matcher method = METHOD.matcher(citation.group(2));
                while (method.find()) {
                    if (!source.contains("void " + method.group(1) + "(")) {
                        missing.add(type + "." + method.group(1));
                    }
                }
            }
        }
        assertThat(missing).as("tests cited in %s that do not exist", CHECKLIST).isEmpty();
    }

    /** From the item's heading to the next {@code ## } heading. */
    private String section(String item) {
        int start = checklist.indexOf("\n## " + item + "\n");
        assertThat(start).as("heading for %s", item).isNotNegative();
        int end = checklist.indexOf("\n## ", start + 1);
        return checklist.substring(start, end < 0 ? checklist.length() : end);
    }

    private static Map<String, Path> testClasses() {
        return TEST_ROOTS.stream().filter(Files::isDirectory).flatMap(SecurityChecklistTest::javaFiles)
                .collect(Collectors.toMap(p -> p.getFileName().toString().replace(".java", ""), p -> p,
                        (first, second) -> first));
    }

    private static Stream<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
