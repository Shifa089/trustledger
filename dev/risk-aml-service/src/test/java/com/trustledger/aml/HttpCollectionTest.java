package com.trustledger.aml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M16: the {@code http/} request collection covers every risk-aml-service endpoint (port 8081). Each controller mapping
 * (main code only) must appear as a request line {@code METHOD {{aml}}/api/v1<path>} in some {@code http/*.http} file,
 * with any value in place of a path variable. Same guard as core-app's {@code HttpCollectionTest}.
 */
class HttpCollectionTest {

    static final Path HTTP_DIR = Path.of("..", "http");

    @Test
    void everyAmlEndpointHasARequestInTheHttpCollection() throws IOException {
        List<String> endpoints = endpoints("com.trustledger.aml");
        assertThat(endpoints).as("controllers found").hasSizeGreaterThanOrEqualTo(6);

        assertThat(missing(endpoints, "{{aml}}", collection())).as("endpoints without a request in http/").isEmpty();
    }

    @Test
    void theGuardItselfNoticesAMissingRequest() {
        assertThat(missing(List.of("GET /aml/cases/{id}/nonexistent"), "{{aml}}", collection()))
                .containsExactly("GET /aml/cases/{id}/nonexistent");
    }

    /** "METHOD /path" for every mapping of every main-code {@code @RestController} under the package. */
    static List<String> endpoints(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = load(definition.getBeanClassName());
            if (type.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes")) {
                continue; // test probes
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = classMapping == null ? "" : first(classMapping.path());
            for (Method method : type.getDeclaredMethods()) {
                for (Class<? extends Annotation> annotation : List.of(GetMapping.class, PostMapping.class,
                        PutMapping.class, DeleteMapping.class, PatchMapping.class)) {
                    if (method.isAnnotationPresent(annotation)) {
                        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                        result.add(mapping.method()[0].name() + " " + prefix + first(mapping.path()));
                    }
                }
            }
        }
        return result;
    }

    /** The endpoints that no request line in the collection matches. */
    static List<String> missing(List<String> endpoints, String baseVariable, String collection) {
        return endpoints.stream().filter(endpoint -> {
            String[] parts = endpoint.split(" ", 2);
            String path = Stream.of(parts[1].split("(?=\\{)|(?<=\\})"))
                    .map(piece -> piece.startsWith("{") ? "[^\\s/?]+" : Pattern.quote(piece))
                    .collect(Collectors.joining());
            Pattern line = Pattern.compile("^" + parts[0] + " " + Pattern.quote(baseVariable + "/api/v1") + path
                    + "(\\?\\S*)?\\s*$", Pattern.MULTILINE);
            return !line.matcher(collection).find();
        }).toList();
    }

    static String collection() {
        try (Stream<Path> files = Files.list(HTTP_DIR)) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.filter(f -> f.toString().endsWith(".http")).sorted().toList()) {
                all.append(Files.readString(file)).append('\n');
            }
            return all.toString();
        } catch (IOException e) {
            throw new IllegalStateException("can't read " + HTTP_DIR, e);
        }
    }

    private static String first(String[] paths) {
        return paths.length == 0 ? "" : paths[0];
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
