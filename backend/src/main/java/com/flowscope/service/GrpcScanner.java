package com.flowscope.service;

import static com.flowscope.service.CommScan.CODE;

import com.flowscope.service.SourceIndex.Src;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gRPC client/server detection across Go, Java, Python (and Node best-effort).
 * Modelled like Kafka: the RPC service is its own node, with client → service →
 * server edges. Gated per file on a real gRPC marker, and generated stub files
 * are skipped (they define the symbols for everyone).
 */
final class GrpcScanner {

    private static final Pattern GRPC_SERVER_RE = Pattern.compile(
            "Register([A-Za-z0-9_]+?)Server\\b"
                    + "|([A-Za-z0-9_]+?)ImplBase\\b"
                    + "|add_([A-Za-z0-9_]+?)Servicer_to_server"
                    + "|\\(\\s*[A-Za-z0-9_.]*?([A-Za-z0-9_]+?)Servicer\\s*\\)");
    private static final Pattern GRPC_CLIENT_RE = Pattern.compile(
            "New([A-Za-z0-9_]+?)Client\\b"
                    + "|([A-Za-z0-9_]+?)Grpc\\s*\\.\\s*new[A-Za-z]*Stub"
                    + "|([A-Za-z0-9_]+?)Stub\\s*\\(");

    private GrpcScanner() {
    }

    static void scan(CommScan scan) {
        for (Src f : scan.src.prod()) {
            String name = f.name;
            if (name.endsWith("_grpc.pb.go") || name.endsWith("_pb2_grpc.py")
                    || name.endsWith(".pb.go") || name.endsWith("_pb2.py")
                    || name.endsWith("Grpc.java")) {
                continue;
            }
            String text = f.text;
            boolean grpcMarker = text.contains("google.golang.org/grpc") || text.contains("grpc.Dial")
                    || text.contains("grpc.NewClient") || text.contains("grpc.NewServer")
                    || text.contains("io.grpc") || text.contains("import grpc")
                    || text.contains("grpc.aio") || text.contains("@grpc/grpc-js")
                    || text.contains("_pb2_grpc") || text.contains("ManagedChannel");
            if (!grpcMarker) {
                continue;
            }
            Matcher srv = GRPC_SERVER_RE.matcher(text);
            while (srv.find()) {
                String n = firstGroup(srv);
                if (isGrpcName(n)) {
                    String id = "grpc:" + strip(n);
                    scan.node(id, strip(n), "grpc", "grpc", Map.of("kind", "gRPC service"));
                    scan.link("grpc", id, "serves", CODE, f.evidence(srv.start(), "code"), true);
                }
            }
            Matcher cli = GRPC_CLIENT_RE.matcher(text);
            while (cli.find()) {
                String n = firstGroup(cli);
                if (isGrpcName(n)) {
                    String id = "grpc:" + strip(n);
                    scan.node(id, strip(n), "grpc", "grpc", Map.of("kind", "gRPC service"));
                    scan.link("grpc", id, "gRPC", CODE, f.evidence(cli.start(), "code"), false);
                }
            }
        }
    }

    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return null;
    }

    private static boolean isGrpcName(String n) {
        if (n == null || n.length() < 3) {
            return false;
        }
        return !Set.of("Grpc", "Client", "Server", "Base", "Service", "Stub").contains(n);
    }

    private static String strip(String n) {
        return n.endsWith("Grpc") ? n.substring(0, n.length() - 4) : n;
    }
}
