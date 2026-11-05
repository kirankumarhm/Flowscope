package com.flowscope.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flowscope.dto.ServiceMap.Evidence;
import static com.flowscope.service.CommScan.CODE;
import static com.flowscope.service.CommScan.CONFIG;
import com.flowscope.service.CommScan.HostRef;
import com.flowscope.service.CommScan.PathRef;
import static com.flowscope.service.CommScan.UNRESOLVED;
import com.flowscope.service.SourceIndex.Src;

/**
 * Outbound HTTP/SOAP calls and SDK-level cloud dependencies of one service.
 *
 * <ul>
 *   <li>Config URLs become {@link HostRef}s only when code reads their key
 *       ({@link References}); an unread base-url key is reported as
 *       "configured but unused" instead of drawn.</li>
 *   <li>Base-url keys match whole key segments: {@code x.base-url} and
 *       {@code apiBaseUrl} qualify, {@code x.database-url} does not.</li>
 *   <li>A URL that only reaches string formatting (links in e-mails, redirects)
 *       is marked {@code reference}, not a call.</li>
 *   <li>Cloud SDK clients (Secrets Manager, SNS, Firebase, …) are detected from
 *       code, not config.</li>
 * </ul>
 */
final class HttpScanner {

    static final Pattern URL_RE = Pattern.compile(
            "https?://([A-Za-z0-9._-]+)(?::\\d+)?(/[^\\s'\"`?#<>)\\\\]*)?");

    private static final Set<String> EXCLUDED_PROP_PREFIXES = Set.of(
            "management.", "logging.", "server.", "springdoc.", "info.", "eureka.", "spring.datasource",
            "spring.jpa", "spring.flyway", "spring.liquibase", "spring.kafka", "spring.data.", "spring.redis",
            "spring.elasticsearch", "spring.cloud.config", "spring.boot.admin", "spring.zipkin", "spring.r2dbc",
            "spring.rabbitmq", "spring.mail", "spring.cassandra");
    private static final Pattern DATASTORE_WORDS = Pattern.compile(
            "(?i)datasource|database|jdbc|r2dbc|mongo|redis|cassandra|elasticsearch|opensearch|kafka"
                    + "|bootstrap[.-]servers|schema-registry|broker|sqs|queue|\\bs3\\b|bucket|dynamodb|rabbit|amqp"
                    + "|activemq|\\bjms\\b|ldap|smtp|mail|flyway|liquibase");
    private static final Pattern LINK_WORDS = Pattern.compile(
            "(?i)callback|redirect|return|success|failure|cancel|logo|image|icon|favicon|docs|swagger|privacy"
                    + "|terms|help|support|portal|website|homepage|link|ui-url|web-url|frontend|public-url");
    private static final Pattern ENV_BASE = Pattern.compile("(?i)(?:^|_)(?:BASEURL|BASE_URL|BASEURI|BASE_URI)$");
    private static final Pattern ENV_GENERIC = Pattern.compile("(?i)(?:_URL|_URI|_ENDPOINT|_HOST)$");
    private static final Pattern ENV_DATASTORE = Pattern.compile(
            "(?i)(?:^|_)(?:DB|DATABASE|PG|POSTGRES|MYSQL|RDS|REDIS|MONGO|MONGODB|CASSANDRA|ELASTICSEARCH|ES"
                    + "|OPENSEARCH|KAFKA|BROKER|BROKERS|SQS|QUEUE|S3|BUCKET|DYNAMO|DYNAMODB|TABLE|SMTP|MAIL|AMQP"
                    + "|RABBIT|SCHEMA_REGISTRY)(?:_|$)");
    private static final Pattern ENV_LINK = Pattern.compile(
            "(?i)CALLBACK|REDIRECT|RETURN|LOGO|IMAGE|ICON|DOCS|SWAGGER|PUBLIC_URL|ASSET|CDN|PORTAL|WEBSITE");

    /** A file that performs HTTP calls (client libraries, browser fetch, GraphQL clients). */
    private static final Pattern HTTP_CLIENT = Pattern.compile(
            "RestTemplate|WebClient|RestClient|HttpClient|HttpRequest|OkHttpClient|Retrofit|FeignClient"
                    + "|HttpURLConnection|CloseableHttpClient|WebServiceTemplate|axios|fetch\\(|\\bgot\\(|superagent"
                    + "|node-fetch|undici|\\bky\\(|\\brequest\\(|https?\\.request|this\\.http\\.|HttpService"
                    + "|\\$http|requests\\.|httpx|aiohttp|urllib|http\\.Get|http\\.Post|http\\.NewRequest|resty"
                    + "|GraphQLClient|graphql-request|ApolloClient|HttpLink|WebSocket");
    private static final Pattern SOAP_CLIENT = Pattern.compile(
            "WebServiceTemplate|WebServiceGatewaySupport|marshalSendAndReceive|@WebServiceClient|javax\\.xml\\.ws"
                    + "|org\\.springframework\\.ws\\.|Jaxb2Marshaller|SoapFaultClientException"
                    + "|jakarta\\.xml\\.ws|org\\.apache\\.axis|org\\.apache\\.cxf|JaxWsProxyFactoryBean"
                    + "|SOAPConnection|soap\\.createClient|from\\s+['\"]soap['\"]|zeep\\.Client");
    private static final Pattern NODE_PROP_BIND = Pattern.compile("(\\w+)\\s*:\\s*process\\.env\\.(\\w+)");
    private static final Pattern NODE_VAR_BIND = Pattern.compile(
            "(?:(?:const|let|var)\\s+|this\\.)(\\w+)\\s*(?::\\s*[\\w<>\\[\\], |]+?)?\\s*=\\s*process\\.env\\.(\\w+)");

    /** An SDK client that names a cloud service. */
    private record Sdk(Pattern marker, String name) {
    }

    private static final List<Sdk> SDKS = List.of(
            new Sdk(Pattern.compile("FirebaseMessaging|com\\.google\\.firebase|firebase-admin|firebase/messaging"
                    + "|getMessaging\\("), "Firebase FCM"),
            new Sdk(Pattern.compile("SecretsManagerClient|GetSecretValueCommand|AWSSecretsManager"
                    + "|client-secrets-manager|['\"]secretsmanager['\"]"), "AWS Secrets Manager"),
            new Sdk(Pattern.compile("\\bSNSClient\\b|\\bSnsClient\\b|AmazonSNS|client-sns|['\"]sns['\"]\\s*\\)"),
                    "AWS SNS"),
            new Sdk(Pattern.compile("\\bSFNClient\\b|\\bSfnClient\\b|AWSStepFunctions|client-sfn|StartExecutionCommand"
                    + "|['\"]stepfunctions['\"]"), "AWS Step Functions"),
            new Sdk(Pattern.compile("\\bSSMClient\\b|\\bSsmClient\\b|AWSSimpleSystemsManagement|client-ssm"
                    + "|GetParameters?(?:ByPath)?Command|['\"]ssm['\"]\\s*\\)"), "AWS Parameter Store"),
            new Sdk(Pattern.compile("\\bKMSClient\\b|\\bKmsClient\\b|AWSKMS\\b|client-kms|['\"]kms['\"]\\s*\\)"),
                    "AWS KMS"),
            new Sdk(Pattern.compile("EventBridgeClient|client-eventbridge|PutEventsCommand"), "AWS EventBridge"),
            new Sdk(Pattern.compile("KinesisClient|AmazonKinesis|client-kinesis|['\"]kinesis['\"]\\s*\\)"),
                    "AWS Kinesis"),
            new Sdk(Pattern.compile("\\bSESClient\\b|\\bSesClient\\b|SesV2Client|AmazonSimpleEmailService|client-ses"
                    + "|['\"]ses['\"]\\s*\\)"), "AWS SES"),
            new Sdk(Pattern.compile("\\bLambdaClient\\b|AWSLambdaClient|client-lambda\\b|InvokeCommand\\b"),
                    "AWS Lambda (invoke)"),
            new Sdk(Pattern.compile("CognitoIdentityProviderClient|client-cognito-identity-provider"
                    + "|AWSCognitoIdentityProvider"), "AWS Cognito"),
            new Sdk(Pattern.compile("SecretManagerServiceClient|@google-cloud/secret-manager"
                    + "|google\\.cloud\\.secretmanager|cloud\\.google\\.com/go/secretmanager"), "GCP Secret Manager"),
            new Sdk(Pattern.compile("@google-cloud/pubsub|google\\.cloud\\.pubsub|com\\.google\\.cloud\\.pubsub"),
                    "GCP Pub/Sub"),
            new Sdk(Pattern.compile("@azure/keyvault-secrets|azure\\.keyvault\\.secrets|SecretClientBuilder"),
                    "Azure Key Vault"),
            new Sdk(Pattern.compile("@azure/service-bus|ServiceBusClient|azure\\.servicebus"), "Azure Service Bus"));

    // UI relative API paths: a literal on a line that performs (or names) an HTTP call.
    private static final Pattern UI_LITERAL = Pattern.compile(
            "['\"`]((?:\\$\\{[^}]+})?/?[A-Za-z][\\w.-]*(?:/[\\w.{}$:-]*)+)['\"`]");
    private static final Pattern UI_CALL_LINE = Pattern.compile(
            "\\.(?:get|post|put|delete|patch|request|head)\\s*[<(]|fetch\\(|axios|[Uu]rl|URL|[Uu]ri\\b|URI|[Ee]ndpoint"
                    + "|[Pp]ath\\b|api");
    /** A field/property holding an API path: {@code deviceUri = '...'}, {@code ordersUrl: '...'}. */
    private static final Pattern UI_PATH_FIELD = Pattern.compile(
            "\\w*(?:Uri|URI|Url|URL|Path|Endpoint|Api|API)\\s*(?::\\s*string\\s*)?[=:]\\s*[^=;\\n]*['\"`]");
    private static final Pattern ASSET = Pattern.compile("(?i).*\\.(html|css|scss|png|jpe?g|svg|gif|json|ico|woff2?|ttf|js|ts)$");

    private HttpScanner() {
    }

    /**
     * @param refs       keys this scan's code reads (decides which config URLs become arrows)
     * @param unusedRefs keys the whole package reads, for "configured but unused" notes;
     *                   null to skip the notes (other handlers of a split package)
     */
    static void scan(CommScan scan, References refs, References unusedRefs, Externals externals,
                     OverrideFile overrides) {
        configHosts(scan, refs, unusedRefs);
        literalUrls(scan, externals, overrides);
        sdks(scan);
        soapPlaceholder(scan);
        if (isUi(scan.svc.subtype)) {
            uiPaths(scan);
        }
    }

    static boolean isUi(String subtype) {
        return Set.of("react", "nextjs", "angular", "vue").contains(subtype);
    }

    // --- config URLs ----------------------------------------------------------------

    private static void configHosts(CommScan scan, References refs, References unusedRefs) {
        ConfigIndex cfg = scan.cfg;
        for (Map.Entry<String, String> e : cfg.props().entrySet()) {
            String key = e.getKey();
            if (excludedProp(key)) {
                continue;
            }
            boolean base = isBaseUrlKey(key);
            boolean generic = !base && isGenericUrlKey(key);
            if (!base && !generic) {
                continue;
            }
            if (DATASTORE_WORDS.matcher(key).find() || (generic && LINK_WORDS.matcher(lastSegment(key)).find())) {
                continue;
            }
            String value = cfg.expand(e.getValue());
            String[] hp = hostPath(value);
            if (generic && hp[0] == null) {
                continue;
            }
            if (!refs.prop(key)) {
                if (base && unusedRefs != null && !unusedRefs.prop(key)) {
                    unused(scan, key, value, scan.propEvidence(key));
                }
                continue;
            }
            String usage = soapHint(usage(scan, key, false), key, hp[1]);
            scan.hosts.add(new HostRef(hp[0], hp[1], key.toLowerCase(Locale.ROOT), false, usage, CONFIG,
                    scan.propEvidence(key)));
        }
        for (Map.Entry<String, String> e : cfg.env().entrySet()) {
            String var = e.getKey();
            boolean base = ENV_BASE.matcher(var).find();
            boolean generic = !base && ENV_GENERIC.matcher(var).find();
            if (!base && !generic) {
                continue;
            }
            if (ENV_DATASTORE.matcher(var).find() || ENV_LINK.matcher(var).find()) {
                continue;
            }
            String[] hp = hostPath(e.getValue());
            if (generic && hp[0] == null) {
                continue;
            }
            if (!refs.env(var)) {
                if (base && unusedRefs != null && !unusedRefs.env(var)) {
                    unused(scan, var, e.getValue(), scan.envEvidence(var));
                }
                continue;
            }
            String usage = soapHint(usage(scan, var, true), var, hp[1]);
            scan.hosts.add(new HostRef(hp[0], hp[1], var.toLowerCase(Locale.ROOT), false, usage, CONFIG,
                    scan.envEvidence(var)));
        }
    }

    private static final Pattern SOAP_KEY = Pattern.compile("(?i)soap|wsdl");
    private static final Pattern SOAP_PATH = Pattern.compile("(?i)/axis2?/|wsdl|/soap\\b|\\.asmx|\\.svc\\b|/ws/");

    /** A call whose key or URL path names SOAP (partnersoap.url, /axis2/services/X, ?wsdl) is a SOAP call. */
    static String soapHint(String usage, String key, String path) {
        if ("call".equals(usage) && (SOAP_KEY.matcher(key).find() || (path != null && SOAP_PATH.matcher(path).find()))) {
            return "soap";
        }
        return usage;
    }

    private static void unused(CommScan scan, String key, String value, Evidence ev) {
        String where = ev.file() == null ? "" : " (" + ev.file() + ":" + ev.line() + ")";
        scan.notes.add(scan.svc.displayName + ": config key " + key
                + (value == null || value.isBlank() ? "" : " = " + SourceIndex.clip(value))
                + " is configured but never read by code" + where);
    }

    private static boolean excludedProp(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String p : EXCLUDED_PROP_PREFIXES) {
            if (k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static String lastSegment(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot + 1) : key;
    }

    /** {@code base-url}, {@code baseUrl}, {@code x-base-url}, {@code apiBaseUrl}; not {@code database-url}. */
    static boolean isBaseUrlKey(String key) {
        String seg = lastSegment(key);
        String lower = seg.toLowerCase(Locale.ROOT);
        if (Set.of("base-url", "baseurl", "base_url", "base-uri", "baseuri", "base_uri").contains(lower)) {
            return true;
        }
        for (String suffix : List.of("-base-url", "_base_url", "-baseurl", "_baseurl", "-base-uri", "_base_uri")) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return seg.matches(".*[a-z0-9]Base(?:Url|URL|Uri|URI)");
    }

    /** Any URL-ish key segment: {@code url}, {@code x-url}, {@code wsdlUrl}, {@code endpoint}, … */
    static boolean isGenericUrlKey(String key) {
        String seg = lastSegment(key);
        String lower = seg.toLowerCase(Locale.ROOT);
        if (Set.of("url", "uri", "endpoint", "wsdl", "address", "location").contains(lower)) {
            return true;
        }
        for (String suffix : List.of("-url", "_url", "-uri", "_uri", "-endpoint", "_endpoint")) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return seg.matches(".*[a-z0-9](?:Url|URL|Uri|URI|Endpoint)");
    }

    /** [host, path] of a URL or bare hostname value; nulls when it names no real host. */
    static String[] hostPath(String value) {
        String host = null;
        String path = null;
        if (value != null) {
            Matcher m = URL_RE.matcher(value);
            if (m.find()) {
                host = m.group(1).toLowerCase(Locale.ROOT);
                path = m.group(2);
            } else if (value.contains(".") && !value.contains(" ") && !value.contains("/")
                    && !value.contains("${") && value.matches("[A-Za-z0-9.-]+(:\\d+)?")) {
                host = value.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            }
        }
        if (host != null && isInfraHost(host)) {
            host = null;
            path = null;
        }
        return new String[]{host, path == null || path.equals("/") ? null : path};
    }

    /** Infrastructure/loopback hosts that are not meaningful systems. */
    static boolean isInfraHost(String host) {
        return host == null || host.isBlank() || host.contains("localhost")
                || host.startsWith("127.") || host.startsWith("0.0.0.0")
                || host.equals("example.com") || host.equals("www.example.com")
                || host.contains("amazonaws") || host.endsWith(".rds")
                || host.contains("kafka")
                || host.equals("www.w3.org") || host.endsWith("apache.org") || host.endsWith("springframework.org")
                || host.endsWith("schemas.xmlsoap.org") || host.endsWith("json-schema.org");
    }

    /**
     * How code uses a config value: {@code soap} or {@code call} when it reaches an
     * HTTP/SOAP client, {@code reference} when it is only formatted into strings.
     * Unknown (no visible use, e.g. bound through {@code @ConfigurationProperties})
     * counts as a call.
     */
    static String usage(CommScan scan, String key, boolean env) {
        List<String> needles = new ArrayList<>();
        List<Pattern> idents = new ArrayList<>();
        if (env) {
            needles.add("process.env." + key);
            needles.add("'" + key + "'");
            needles.add("\"" + key + "\"");
        } else {
            needles.add("${" + key);
            needles.add("\"" + key + "\"");
            if (key.startsWith("environment.")) {
                needles.add(key);
            }
            scan.cfg.valueFieldKeys().forEach((field, k) -> {
                if (k.equals(key)) {
                    idents.add(word(field));
                    idents.add(Pattern.compile("\\.get" + Pattern.quote(capitalize(field)) + "\\s*\\("));
                }
            });
        }
        Set<Src> files = new LinkedHashSet<>();
        for (Src f : scan.src.prod()) {
            boolean hit = needles.stream().anyMatch(f.text::contains);
            if (env) {
                for (Pattern bind : List.of(NODE_VAR_BIND, NODE_PROP_BIND)) {
                    Matcher b = bind.matcher(f.text);
                    while (b.find()) {
                        if (b.group(2).equals(key)) {
                            idents.add(word(b.group(1)));
                        }
                    }
                }
            }
            if (hit) {
                files.add(f);
            }
        }
        for (Src f : scan.src.prod()) {
            for (Pattern p : idents) {
                if (p.matcher(f.text).find()) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty()) {
            return "call";
        }
        boolean client = false;
        for (Src f : files) {
            if (SOAP_CLIENT.matcher(f.text).find()) {
                return "soap";
            }
            if (HTTP_CLIENT.matcher(f.text).find() || f.text.contains("@FeignClient")) {
                client = true;
            }
        }
        return client ? "call" : "reference";
    }

    private static Pattern word(String ident) {
        return Pattern.compile("(?<![\\w$])" + Pattern.quote(ident) + "(?![\\w$])");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // --- literal URLs in code -----------------------------------------------------------

    private static void literalUrls(CommScan scan, Externals externals, OverrideFile overrides) {
        for (Src f : scan.src.prod()) {
            Matcher m = URL_RE.matcher(f.text);
            while (m.find()) {
                String host = m.group(1).toLowerCase(Locale.ROOT);
                if (isInfraHost(host)) {
                    continue;
                }
                // Only trusted when it names a known system (avoids stray links in comments).
                if (externals.classify(host, m.group(2)) != null || overrides.host(host, m.group(2)) != null) {
                    scan.hosts.add(new HostRef(host, m.group(2), null, true, "call", CODE,
                            f.evidence(m.start(), "code")));
                }
            }
        }
    }

    // --- SDK clients ---------------------------------------------------------------------

    private static void sdks(CommScan scan) {
        for (Src f : scan.src.prod()) {
            for (Sdk sdk : SDKS) {
                Matcher m = sdk.marker().matcher(f.text);
                if (m.find()) {
                    scan.external(sdk.name(), "cloud", "uses", "SDK", CODE, f.evidence(m.start(), "code"));
                }
            }
        }
    }

    /** SOAP client code whose endpoint never resolved: draw a placeholder, not nothing. */
    private static void soapPlaceholder(CommScan scan) {
        if (scan.hosts.stream().anyMatch(h -> "soap".equals(h.usage()))) {
            return;
        }
        for (Src f : scan.src.prod()) {
            Matcher m = SOAP_CLIENT.matcher(f.text);
            if (m.find() && !f.inImport(m.start())) {
                String id = "ext:soap:?" + scan.svc.id;
                scan.node(id, "SOAP service (endpoint not resolved)", "external", "soap",
                        Map.of("kind", "Unresolved SOAP endpoint", "unresolved", true));
                scan.link("soap", id, "SOAP", UNRESOLVED, f.evidence(m.start(), "code (endpoint not resolved)"), false);
                return;
            }
        }
    }

    // --- UI relative API paths ----------------------------------------------------------------

    private static void uiPaths(CommScan scan) {
        boolean uiCallsHttp = scan.src.prod().stream().anyMatch(f -> HTTP_CLIENT.matcher(f.text).find());
        if (!uiCallsHttp) {
            return;
        }
        for (Src f : scan.src.prod()) {
            // Paths are often kept in a configuration class (deviceUri = 'api/3.0.0/devices/')
            // that the HTTP services import, so such files count even without a client call.
            boolean client = HTTP_CLIENT.matcher(f.text).find();
            if (!client && !UI_PATH_FIELD.matcher(f.text).find()) {
                continue;
            }
            Matcher m = UI_LITERAL.matcher(f.text);
            while (m.find()) {
                if (f.inImport(m.start())) {
                    continue;
                }
                String raw = m.group(1);
                String path = raw.replaceAll("^\\$\\{[^}]+}", "").replaceAll("^/+", "");
                if (path.startsWith("http") || path.startsWith(".") || path.startsWith("@")
                        || ASSET.matcher(path).matches() || !path.contains("/")) {
                    continue;
                }
                int line = f.line(m.start());
                String lineText = f.lineText(line) + " " + f.lineText(line - 1);
                boolean ok = client ? UI_CALL_LINE.matcher(lineText).find()
                        : UI_PATH_FIELD.matcher(f.lineText(line)).find();
                if (!ok) {
                    continue;
                }
                scan.apiPaths.add(new PathRef(path, f.evidence(m.start(), "code")));
            }
        }
    }
}
