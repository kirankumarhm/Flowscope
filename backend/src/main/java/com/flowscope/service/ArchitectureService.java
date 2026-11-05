package com.flowscope.service;

import com.flowscope.config.AllowedRoots;
import com.flowscope.dto.Architecture;
import com.flowscope.dto.ComponentMap;
import com.flowscope.dto.ServiceMap;
import com.flowscope.exception.ApiExceptions;
import com.flowscope.util.PathParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Architecture view. The path decides the scope:
 * <ul>
 *   <li>a folder holding several services shows the whole project;</li>
 *   <li>a folder holding one service (or any folder inside it) shows that application:
 *       its components by layer and everything it connects to.</li>
 * </ul>
 * For one application, the surrounding project is still scanned so that the services
 * calling it, or sharing its topics and queues, can be shown. Reuses the service map
 * cache, so switching between the views is cheap.
 */
@Service
public class ArchitectureService {

    /** How far above the given path to look for the project the application belongs to. */
    private static final int MAX_LEVELS_UP = 2;

    private final ServiceMapService serviceMaps;
    private final Function<Path, ComponentMap> components;

    @Autowired
    public ArchitectureService(ServiceMapService serviceMaps, FlowService flows) {
        this(serviceMaps, dir -> flows.componentMap(dir.toString()));
    }

    ArchitectureService(ServiceMapService serviceMaps, Function<Path, ComponentMap> components) {
        this.serviceMaps = serviceMaps;
        this.components = components;
    }

    public Architecture architecture(String pathParam, String exclude) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        Path path = PathParams.resolve(pathParam);
        if (!Files.exists(path)) {
            throw new ApiExceptions.InvalidPathException("path not found: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + path);
        }
        AllowedRoots.check(path);
        if (WorkspaceScanner.countChildServices(path) >= 2) {
            return ArchitectureBuilder.build(serviceMaps.serviceMap(path.toString(), exclude), null, path);
        }
        Path root = projectRoot(path);
        ServiceMap map = serviceMaps.serviceMap(root.toString(), exclude);
        String focus = focus(root, path, map);
        if (focus == null) {
            List<ServiceMap.Node> apps = map.nodes().stream()
                    .filter(n -> (n.type().equals("service") || n.type().equals("ui")) && n.path() != null
                            && !"library".equals(n.subtype()))
                    .toList();
            if (apps.size() != 1) {
                return ArchitectureBuilder.build(map, null, root);
            }
            focus = apps.get(0).id();
        }
        Architecture project = ArchitectureBuilder.build(map, focus, root);
        String appId = project.focus();
        Architecture.Node app = appId == null ? null
                : project.nodes().stream().filter(n -> n.id().equals(appId)).findFirst().orElse(null);
        if (app == null) {
            return ArchitectureBuilder.build(map, null, root);
        }
        Path appDir = app.meta() != null && app.meta().get("path") instanceof String p ? root.resolve(p) : path;
        return AppArchitectureBuilder.build(project, appId, componentsOf(appDir));
    }

    /** The application's components, or null when its runtime has no component view. */
    private ComponentMap componentsOf(Path appDir) {
        try {
            ComponentMap cmp = components.apply(appDir);
            return cmp == null || cmp.components().isEmpty() ? null : cmp;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The project a path belongs to: the path itself when it holds several
     * services, otherwise the highest ancestor (up to {@value #MAX_LEVELS_UP}
     * levels, never the home directory or above, never outside the allowed roots)
     * that directly contains several services. Falls back to the path.
     */
    static Path projectRoot(Path path) {
        if (WorkspaceScanner.countChildServices(path) >= 2) {
            return path;
        }
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path found = null;
        Path cur = path;
        for (int up = 0; up < MAX_LEVELS_UP; up++) {
            cur = cur.getParent();
            if (cur == null || cur.getParent() == null || home.startsWith(cur) || !AllowedRoots.allows(cur)) {
                break;
            }
            if (WorkspaceScanner.countChildServices(cur) >= 2) {
                found = cur;
            }
        }
        return found != null ? found : path;
    }

    /** Service-map id of the service containing {@code path}, or null when the path is the project. */
    static String focus(Path root, Path path, ServiceMap map) {
        if (root.equals(path)) {
            return null;
        }
        String rel = root.relativize(path).toString().replace('\\', '/');
        String best = null;
        for (ServiceMap.Node n : map.nodes()) {
            if (n.path() == null || !(n.type().equals("service") || n.type().equals("ui"))) {
                continue;
            }
            String p = n.path();
            if ((rel.equals(p) || rel.startsWith(p + "/")) && (best == null || p.length() > best.length())) {
                best = p;
            }
        }
        return best == null ? null : "svc:" + best;
    }
}
