package com.flowscope.service;

import com.flowscope.dto.BusinessLogic;
import com.flowscope.dto.FlowDescription;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Infers a flow's business shape offline, with no LLM.
 *
 * <p>Each step of the {@link FlowDescription} is classified into a business
 * concern by the vocabulary of the code it came from — a call to
 * {@code repository.findById} is a lookup, {@code producer.send} is an event
 * publication, a branch that throws is a rule. Runs of the same concern collapse
 * into a named phase, so the output reads as stages of work rather than as
 * statements.
 *
 * <p>This is pattern recognition, not comprehension. It reports what the code
 * <em>touches</em>; it cannot explain <em>why</em> the business needs it. The
 * caveats it emits say so, and callers surface them.
 */
@Service
public class BusinessLogicHeuristic {

    /** A business concern a step can belong to, most specific first. */
    private enum Concern {
        VALIDATE("Validate input", "validation", "Rejects requests that don't meet preconditions"),
        AUTHORIZE("Check permissions", "authorization", "Confirms the caller is allowed to proceed"),
        LOOKUP("Load data", "lookup", "Reads the records the operation needs"),
        PERSIST("Save changes", "persistence", "Writes the operation's result to storage"),
        PUBLISH("Publish event", "messaging", "Notifies other services that something happened"),
        EXTERNAL("Call external service", "external", "Delegates to a service outside this application"),
        TRANSFORM("Transform data", "transform", "Converts between internal and external shapes"),
        ITERATE("Process each item", "iteration", "Repeats work across a collection"),
        FAIL("Handle failure", "error", "Reports or recovers from an error"),
        RESPOND("Return result", "response", "Produces the value handed back to the caller"),
        OTHER("Perform work", "other", "Application logic that didn't match a known pattern");

        final String label;
        final String kind;
        final String purpose;

        Concern(String label, String kind, String purpose) {
            this.label = label;
            this.kind = kind;
            this.purpose = purpose;
        }
    }

    // Vocabulary is matched against the step text, lower-cased. Ordered by
    // specificity: the first concern that matches wins.
    private static final Pattern VALIDATE_RE = Pattern.compile(
            "\\b(valid|validate|verify|check|assert|require|ensure|isempty|isblank|isnull|"
            + "notnull|nonnull|matches|constraint|precondition|sanitiz)");
    private static final Pattern AUTHORIZE_RE = Pattern.compile(
            "\\b(auth|authoriz|authenticat|permission|role|scope|token|credential|principal|"
            + "forbidden|unauthoriz|jwt|oauth)");
    private static final Pattern LOOKUP_RE = Pattern.compile(
            "\\b(find|get|load|fetch|read|query|select|search|lookup|retriev|exists|count|"
            + "list|getitem|scan|describe)");
    private static final Pattern PERSIST_RE = Pattern.compile(
            "\\b(save|persist|store|insert|update|delete|remove|put|putitem|write|upsert|"
            + "merge|flush|commit|createtable|"
            // State transitions — the write half of outbox/queue/lease patterns.
            + "mark|claim|ack|acknowledge|reserve|lock|release|complete|settle)");
    private static final Pattern PUBLISH_RE = Pattern.compile(
            "\\b(publish|emit|producer|kafkatemplate|sendmessage|topic|event|notify|broadcast|"
            + "enqueue|sqs|sns|dispatch)");
    private static final Pattern EXTERNAL_RE = Pattern.compile(
            "\\b(resttemplate|webclient|httpclient|feign|axios|requests\\.|http\\.|client\\.|"
            + "exchange|getforobject|postfor|apiclient|invoke|url)");
    private static final Pattern TRANSFORM_RE = Pattern.compile(
            "\\b(map|convert|transform|tod|todto|toentity|tomodel|builder|build\\(|parse|"
            + "serializ|deserializ|format|marshal|unmarshal|json)");
    private static final Pattern FAIL_RE = Pattern.compile(
            "\\b(throw|raise|exception|error|fail|fatal|panic|reject|abort|log\\.error)");

    /** Reads like a 4xx/5xx rejection rather than an internal failure. */
    private static final Pattern REJECTION_RE = Pattern.compile(
            "\\b(400|401|403|404|409|422|badrequest|notfound|conflict|unprocessable|"
            + "illegalargument|validationexception|responsestatus)");

    /**
     * Builds a business-logic view from a flow description. Never throws; a flow
     * it can't characterize yields an honest "no phases identified" result.
     */
    public BusinessLogic infer(FlowDescription description, boolean llmConfigured) {
        List<FlowDescription.Step> steps = description == null ? List.of() : description.steps();
        List<String> rules = new ArrayList<>();

        // One phase per concern, ordered by first appearance, evidence accumulated.
        // Grouping by *consecutive runs* instead would shatter a guard-clause chain
        // into a validate/return phase per clause — that's the step list relabelled,
        // not a summary. Phases answer "what concerns does this flow have", so
        // interleaving collapses; the caveat notes ordering is by first appearance.
        Map<Concern, List<String>> evidence = new LinkedHashMap<>();
        Map<Concern, Integer> firstLine = new LinkedHashMap<>();
        int ruleChainPairs = 0;
        Concern previous = null;

        for (FlowDescription.Step step : steps) {
            if (step.index() == 0) {
                continue; // structural note, not a code step
            }
            String text = step.text();
            String lower = text.toLowerCase(Locale.ROOT);
            Concern concern = classify(step, lower);
            collectRule(step, rules);

            // A decision immediately followed by a return is one link of a
            // rule chain (guard clauses, dispatch tables, classifiers).
            if (previous == Concern.VALIDATE && concern == Concern.RESPOND) {
                ruleChainPairs++;
            }
            previous = concern;

            evidence.computeIfAbsent(concern, k -> new ArrayList<>());
            List<String> items = evidence.get(concern);
            if (items.size() < 8) {
                items.add(text);
            }
            if (step.line() != null) {
                firstLine.putIfAbsent(concern, step.line());
            }
        }

        List<BusinessLogic.Phase> phases = new ArrayList<>();
        for (Map.Entry<Concern, List<String>> entry : evidence.entrySet()) {
            Concern concern = entry.getKey();
            // ≥3 condition→return links means the flow *is* a rule chain; say so
            // rather than calling it generic input validation.
            String label = concern == Concern.VALIDATE && ruleChainPairs >= 3
                    ? "Apply classification rules"
                    : concern.label;
            String purpose = concern == Concern.VALIDATE && ruleChainPairs >= 3
                    ? "Selects an outcome by testing conditions in order (" + ruleChainPairs
                            + " condition/result pairs)"
                    : concern.purpose;
            phases.add(new BusinessLogic.Phase(
                    phases.size() + 1, label, concern.kind, purpose,
                    List.copyOf(entry.getValue()), firstLine.get(concern)));
        }

        return new BusinessLogic(
                description == null ? null : description.functionId(),
                "heuristic",
                null,
                llmConfigured,
                summarize(description, phases),
                phases,
                dedupe(rules),
                caveats(llmConfigured, phases));
    }

    // --- classification -----------------------------------------------------

    private Concern classify(FlowDescription.Step step, String lower) {
        String kind = step.kind() == null ? "" : step.kind();
        // Structural kinds are unambiguous — trust them over vocabulary.
        if ("throw".equals(kind)) {
            return Concern.FAIL;
        }
        if ("return".equals(kind)) {
            return Concern.RESPOND;
        }
        if ("loop".equals(kind)) {
            return Concern.ITERATE;
        }
        if ("branch".equals(kind)) {
            // A decision is a rule check; which kind depends on what it tests.
            if (AUTHORIZE_RE.matcher(lower).find()) {
                return Concern.AUTHORIZE;
            }
            return Concern.VALIDATE;
        }
        if (AUTHORIZE_RE.matcher(lower).find()) {
            return Concern.AUTHORIZE;
        }
        if (VALIDATE_RE.matcher(lower).find()) {
            return Concern.VALIDATE;
        }
        // Error handling is checked before the data verbs: a line like
        // log.Printf("claim error: %v") mentions a persistence verb only inside
        // its message string, and reading it as a write would be wrong.
        if (FAIL_RE.matcher(lower).find()) {
            return Concern.FAIL;
        }
        if (PUBLISH_RE.matcher(lower).find()) {
            return Concern.PUBLISH;
        }
        if (PERSIST_RE.matcher(lower).find()) {
            return Concern.PERSIST;
        }
        if (EXTERNAL_RE.matcher(lower).find()) {
            return Concern.EXTERNAL;
        }
        if (LOOKUP_RE.matcher(lower).find()) {
            return Concern.LOOKUP;
        }
        if (TRANSFORM_RE.matcher(lower).find()) {
            return Concern.TRANSFORM;
        }
        return Concern.OTHER;
    }

    /**
     * A decision that determines an outcome is a business rule worth naming.
     *
     * <p>Rejections (4xx, throw) are the obvious case, but a branch that returns
     * a value is equally a rule — "annotated @RestController ⇒ controller" is
     * the business logic of a classifier, and reading only rejections misses it
     * entirely. Uses the branch labels the flow description already carries.
     */
    private void collectRule(FlowDescription.Step step, List<String> rules) {
        if (!"branch".equals(step.kind()) || step.branches() == null) {
            return;
        }
        String decisive = null;
        for (String branch : step.branches()) {
            String branchLower = branch.toLowerCase(Locale.ROOT);
            boolean rejects = REJECTION_RE.matcher(branchLower).find()
                    || FAIL_RE.matcher(branchLower).find();
            boolean returns = branchLower.contains("return");
            if (rejects) {
                decisive = branch; // a rejection outranks a plain return
                break;
            }
            if (returns && decisive == null) {
                decisive = branch;
            }
        }
        if (decisive != null && rules.size() < 40) {
            rules.add(condition(step.text()) + " → " + outcome(decisive));
        }
    }

    /** Strips the "Decides on if " prefix the description adds. */
    private String condition(String text) {
        String stripped = text.replaceFirst("^Decides on\\s+", "").replaceFirst("^if\\s+", "");
        return stripped.length() > 120 ? stripped.substring(0, 119) + "…" : stripped;
    }

    /** Strips the "yes → " / "no → " label from a branch. */
    private String outcome(String branch) {
        String stripped = branch.replaceFirst("^(yes|no|default|body)\\s*→\\s*", "");
        return stripped.length() > 120 ? stripped.substring(0, 119) + "…" : stripped;
    }

    // --- assembly -----------------------------------------------------------

    private String summarize(FlowDescription description, List<BusinessLogic.Phase> phases) {
        String name = description == null || description.functionId() == null
                ? "This flow"
                : shortName(description.functionId());
        if (phases.isEmpty()) {
            return name + " has no steps that match a recognizable business pattern.";
        }
        List<String> stages = new ArrayList<>();
        for (BusinessLogic.Phase phase : phases) {
            String lowered = phase.name().substring(0, 1).toLowerCase(Locale.ROOT)
                    + phase.name().substring(1);
            if (!stages.contains(lowered)) {
                stages.add(lowered);
            }
        }
        return name + " appears to " + joinReadably(stages) + ".";
    }

    private String joinReadably(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", then ", parts.subList(0, parts.size() - 1))
                + ", then " + parts.get(parts.size() - 1);
    }

    private String shortName(String functionId) {
        String name = functionId;
        int hash = name.indexOf('#');
        if (hash >= 0 && hash + 1 < name.length()) {
            name = name.substring(hash + 1);
        }
        int at = name.lastIndexOf('@');
        if (at > 0) {
            name = name.substring(0, at);
        }
        return name;
    }

    private List<String> dedupe(List<String> items) {
        Set<String> seen = new LinkedHashSet<>(items);
        return seen.isEmpty() ? null : List.copyOf(seen);
    }

    private List<String> caveats(boolean llmConfigured, List<BusinessLogic.Phase> phases) {
        List<String> caveats = new ArrayList<>();
        caveats.add("Inferred offline from naming and control flow — it reports what the code "
                + "touches, not why the business needs it.");
        if (phases.size() > 1) {
            caveats.add("Phases are grouped by concern and ordered by first appearance, so a "
                    + "flow that revisits a concern shows it once.");
        }
        boolean allOther = !phases.isEmpty()
                && phases.stream().allMatch(p -> "other".equals(p.kind()));
        if (allOther) {
            caveats.add("No step matched a known pattern, so the phases above are ordering only.");
        }
        if (!llmConfigured) {
            caveats.add("Configure an LLM provider (flowscope.llm.*) for an interpretation of intent.");
        }
        return caveats;
    }
}
