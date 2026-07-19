package com.flowscope.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolves the full set of source roots to analyze for a project. Given the
 * primary directory, it auto-detects sibling modules the project depends on —
 * so base classes and shared code in another module (e.g. a {@code cm-commons}
 * library) are parsed too and cross-module calls/inheritance resolve.
 *
 * <p>Detection is conservative: it reads the primary module's {@code pom.xml},
 * takes only the dependencies sharing the project's own groupId (internal
 * modules), and includes a sibling directory when its name matches such an
 * artifactId. Unrelated sibling projects are not pulled in.
 */
public final class ProjectRoots {

    private ProjectRoots() {
    }

    /** A resolved source root and the module name it represents. */
    public record Root(Path path, String module) {
    }

    /**
     * The primary root plus any auto-detected sibling modules it depends on.
     * The primary is always first.
     */
    public static List<Root> resolve(Path primary) {
        Path root = primary.toAbsolutePath().normalize();
        List<Root> roots = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        roots.add(new Root(root, root.getFileName().toString()));
        seen.add(root);

        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return roots;
        }
        try {
            Document doc = parse(pom);
            String groupId = projectGroupId(doc);
            Set<String> internalArtifacts = internalDependencyArtifacts(doc, groupId);
            Path parent = root.getParent();
            if (parent != null) {
                for (String artifact : internalArtifacts) {
                    Path candidate = parent.resolve(artifact);
                    if (Files.isDirectory(candidate) && seen.add(candidate.normalize())) {
                        roots.add(new Root(candidate.normalize(), artifact));
                    }
                }
            }
        } catch (Exception e) {
            // Auto-detection is best-effort; fall back to the primary root only.
        }
        return roots;
    }

    private static Document parse(Path pom) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder().parse(pom.toFile());
    }

    /** Project groupId, falling back to the parent POM's groupId when inherited. */
    private static String projectGroupId(Document doc) {
        Element project = doc.getDocumentElement();
        String gid = childText(project, "groupId");
        if (gid != null) {
            return gid;
        }
        Element parent = firstChildElement(project, "parent");
        return parent != null ? childText(parent, "groupId") : null;
    }

    /** ArtifactIds of dependencies whose groupId matches the project's (internal modules). */
    private static Set<String> internalDependencyArtifacts(Document doc, String groupId) {
        Set<String> artifacts = new LinkedHashSet<>();
        if (groupId == null) {
            return artifacts;
        }
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String g = childText(dep, "groupId");
            String a = childText(dep, "artifactId");
            if (a != null && g != null && g.equals(groupId)) {
                artifacts.add(a);
            }
        }
        return artifacts;
    }

    private static String childText(Element parent, String tag) {
        Element e = firstChildElement(parent, tag);
        return e != null && e.getTextContent() != null ? e.getTextContent().trim() : null;
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                return (Element) n;
            }
        }
        return null;
    }
}
