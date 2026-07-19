package com.flowscope.extract;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.Map;
import java.util.Optional;

/**
 * Spring MVC endpoint detection shared by the extractor and the flow builder.
 * Maps {@code @GetMapping}/{@code @PostMapping}/... plus the class-level
 * {@code @RequestMapping} base path to an "VERB /path" label.
 */
public final class SpringEndpoints {

    private static final Map<String, String> HTTP_VERB_BY_ANNOTATION = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "ANY");

    private SpringEndpoints() {
    }

    /** [verb, path] for a handler method, or empty when it is not an endpoint. */
    public static Optional<String[]> endpoint(CallableDeclaration<?> method, String basePath) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String verb = HTTP_VERB_BY_ANNOTATION.get(ann.getNameAsString());
            if (verb != null) {
                return Optional.of(new String[]{verb, joinPath(basePath, annotationPath(ann))});
            }
        }
        return Optional.empty();
    }

    /** Class-level {@code @RequestMapping} base path, or "" when absent. */
    public static String basePath(TypeDeclaration<?> type) {
        for (AnnotationExpr ann : type.getAnnotations()) {
            if (ann.getNameAsString().equals("RequestMapping")) {
                return annotationPath(ann);
            }
        }
        return "";
    }

    /** First string-literal argument of an annotation (covers ("/x") and (value="/x")). */
    public static String annotationPath(AnnotationExpr ann) {
        return ann.findFirst(StringLiteralExpr.class)
                .map(StringLiteralExpr::getValue)
                .orElse("");
    }

    public static String joinPath(String a, String b) {
        a = trim(a, true);
        b = trim(b, false);
        if (a.isEmpty()) {
            return "/" + b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + "/" + b;
    }

    private static String trim(String s, boolean trailing) {
        int start = 0;
        int end = s.length();
        if (trailing) {
            while (end > 0 && s.charAt(end - 1) == '/') {
                end--;
            }
        } else {
            while (start < end && s.charAt(start) == '/') {
                start++;
            }
        }
        return s.substring(start, end);
    }
}
