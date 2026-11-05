package com.flowscope.service;

import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.service.ServerlessScanner.TriggerDecl;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Lambda triggers from AWS SAM {@code template.yaml} ({@code AWS::Serverless::Function}
 * {@code Events}) and Serverless Framework {@code serverless.yml} ({@code functions.*.events}).
 * CloudFormation short tags ({@code !GetAtt}, {@code !Ref}, {@code !Sub}) are stripped
 * before parsing so the safe YAML loader accepts the file.
 */
final class IacYaml {

    private IacYaml() {
    }

    static List<TriggerDecl> parse(Path file, String text, Path relBase) {
        List<TriggerDecl> out = new ArrayList<>();
        String clean = text.replaceAll("!(?:Ref|GetAtt|Sub|Join|If|Select|Split|FindInMap|ImportValue|Base64"
                + "|Cidr|GetAZs|Equals|Not|And|Or|Condition)\\b\\s*", "");
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(clean);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map<?, ?> map)) {
            return out;
        }
        String rel = relBase.relativize(file).toString().replace('\\', '/');
        List<String> lines = List.of(text.split("\n", -1));
        if (map.get("functions") instanceof Map<?, ?> fns) {
            serverless(fns, file, rel, lines, out);
        }
        if (map.get("Resources") instanceof Map<?, ?> res) {
            sam(res, file, rel, lines, out);
        }
        return out;
    }

    private static void serverless(Map<?, ?> fns, Path file, String rel, List<String> lines, List<TriggerDecl> out) {
        fns.forEach((name, def) -> {
            if (!(def instanceof Map<?, ?> fn)) {
                return;
            }
            String export = ServerlessScanner.export(str(fn.get("handler")));
            String fname = str(fn.get("name")) != null ? str(fn.get("name")) : String.valueOf(name);
            Evidence ev = ev(rel, lines, String.valueOf(name), "config (serverless function " + name + ")");
            if (!(fn.get("events") instanceof List<?> events)) {
                return;
            }
            for (Object e : events) {
                if (!(e instanceof Map<?, ?> em)) {
                    continue;
                }
                em.forEach((type, body) -> {
                    String t = String.valueOf(type);
                    Map<?, ?> b = body instanceof Map<?, ?> bm ? bm : Map.of();
                    switch (t) {
                        case "kafka", "msk" -> out.add(new TriggerDecl(fname, export, "kafka",
                                names(b.get("topic"), b.get("topics")), ev, file));
                        case "sqs" -> out.add(new TriggerDecl(fname, export, "sqs",
                                List.of(arnName(body instanceof Map ? str(b.get("arn")) : str(body))), ev, file));
                        case "sns" -> out.add(new TriggerDecl(fname, export, "sns",
                                List.of(body instanceof Map ? first(str(b.get("topicName")), arnName(str(b.get("arn"))))
                                        : arnName(str(body))), ev, file));
                        case "schedule" -> out.add(new TriggerDecl(fname, export, "schedule",
                                List.of(body instanceof Map ? String.valueOf(b.get("rate")) : String.valueOf(body)),
                                ev, file));
                        case "eventBridge" -> out.add(new TriggerDecl(fname, export, "schedule",
                                List.of(first(str(b.get("schedule")), "EventBridge rule")), ev, file));
                        case "stream" -> out.add(new TriggerDecl(fname, export, "dynamodb",
                                List.of(arnName(body instanceof Map ? str(b.get("arn")) : str(body))), ev, file));
                        case "s3" -> out.add(new TriggerDecl(fname, export, "s3",
                                List.of(body instanceof Map ? String.valueOf(b.get("bucket")) : String.valueOf(body)),
                                ev, file));
                        default -> {
                        }
                    }
                });
            }
        });
    }

    private static void sam(Map<?, ?> resources, Path file, String rel, List<String> lines, List<TriggerDecl> out) {
        resources.forEach((logical, def) -> {
            if (!(def instanceof Map<?, ?> r) || !"AWS::Serverless::Function".equals(str(r.get("Type")))) {
                return;
            }
            Map<?, ?> props = r.get("Properties") instanceof Map<?, ?> p ? p : Map.of();
            String export = ServerlessScanner.export(str(props.get("Handler")));
            String fname = first(str(props.get("FunctionName")), String.valueOf(logical));
            Evidence ev = ev(rel, lines, String.valueOf(logical), "config (SAM function " + logical + ")");
            if (!(props.get("Events") instanceof Map<?, ?> events)) {
                return;
            }
            events.forEach((en, edef) -> {
                if (!(edef instanceof Map<?, ?> e)) {
                    return;
                }
                String type = str(e.get("Type"));
                Map<?, ?> ep = e.get("Properties") instanceof Map<?, ?> m ? m : Map.of();
                if (type == null) {
                    return;
                }
                switch (type) {
                    case "Kafka", "MSK", "SelfManagedKafka" -> out.add(new TriggerDecl(fname, export, "kafka",
                            names(ep.get("Topics"), null), ev, file));
                    case "SQS" -> out.add(new TriggerDecl(fname, export, "sqs",
                            List.of(logicalName(resources, str(ep.get("Queue")), "QueueName")), ev, file));
                    case "SNS" -> out.add(new TriggerDecl(fname, export, "sns",
                            List.of(logicalName(resources, str(ep.get("Topic")), "TopicName")), ev, file));
                    case "Schedule", "ScheduleV2", "EventBridgeRule", "CloudWatchEvent" ->
                            out.add(new TriggerDecl(fname, export, "schedule",
                                    List.of(first(str(ep.get("Schedule")), first(str(ep.get("ScheduleExpression")),
                                            String.valueOf(en)))), ev, file));
                    case "DynamoDB" -> out.add(new TriggerDecl(fname, export, "dynamodb",
                            List.of(logicalName(resources, str(ep.get("Stream")), "TableName")), ev, file));
                    default -> {
                    }
                }
            });
        });
    }

    /** Resource name for a GetAtt/Ref value ("MyQueue.Arn" / "MyQueue"), or the ARN's name. */
    private static String logicalName(Map<?, ?> resources, String ref, String nameProp) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("arn:")) {
            return arnName(ref);
        }
        String logical = ref.split("\\.")[0];
        if (resources.get(logical) instanceof Map<?, ?> r && r.get("Properties") instanceof Map<?, ?> p) {
            String n = str(p.get(nameProp));
            if (n != null) {
                return n;
            }
        }
        return logical;
    }

    private static String arnName(String arn) {
        if (arn == null) {
            return null;
        }
        if (!arn.startsWith("arn:")) {
            return arn.split("\\.")[0];
        }
        String last = arn.substring(arn.lastIndexOf(':') + 1);
        return last.contains("/") ? last.substring(last.indexOf('/') + 1).split("/")[0] : last;
    }

    private static List<String> names(Object a, Object b) {
        List<String> out = new ArrayList<>();
        for (Object o : new Object[]{a, b}) {
            if (o instanceof List<?> l) {
                l.forEach(x -> out.add(String.valueOf(x)));
            } else if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static Evidence ev(String rel, List<String> lines, String key, String via) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(key + ":")) {
                return new Evidence(rel, i + 1, SourceIndex.clip(lines.get(i).trim()), via);
            }
        }
        return new Evidence(rel, 0, key, via);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
