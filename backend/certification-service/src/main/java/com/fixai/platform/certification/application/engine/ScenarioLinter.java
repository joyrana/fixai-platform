package com.fixai.platform.certification.application.engine;

import com.fixai.platform.certification.domain.evaluation.Invariant;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.DictionaryRegistry;
import com.fixai.platform.fixcore.FixMessageBuilder;
import com.fixai.platform.fixcore.FixVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import quickfix.DataDictionary;
import quickfix.MessageUtils;

/**
 * Static validation of a scenario against every FIX version it declares: message types and field names must exist in
 * the dictionary, invariants must parse, and every {@code ${var:x}} must be captured by an earlier step.
 */
public final class ScenarioLinter {

    private static final Pattern VAR = Pattern.compile("\\$\\{var:([A-Za-z0-9_.-]+)}");
    private static final Pattern OPERAND = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    public List<String> lint(Scenario scenario) {
        List<String> problems = new ArrayList<>();
        if (scenario.fixVersions().isEmpty()) {
            problems.add(scenario.reference() + ": declares no FIX versions");
        }
        for (FixVersion version : scenario.fixVersions()) {
            FixMessageBuilder builder = new FixMessageBuilder(version);
            DataDictionary transport = DictionaryRegistry.shared().transport(version);
            DataDictionary application = DictionaryRegistry.shared().application(version);
            Set<String> captured = new HashSet<>();
            int index = 0;
            for (Step step : scenario.steps()) {
                String where = scenario.reference() + " " + version + " step " + (++index) + ": ";
                try {
                    lintStep(step, builder, transport, application, captured, where, problems);
                } catch (IllegalArgumentException exception) {
                    problems.add(where + exception.getMessage());
                }
            }
        }
        return problems;
    }

    private void lintStep(
            Step step, FixMessageBuilder builder, DataDictionary transport, DataDictionary application,
            Set<String> captured, String where, List<String> problems) {
        switch (step) {
            case Step.Send send -> {
                builder.build(send.msgType(), send.fields());
                checkVars(send.fields().toString(), captured, where, problems);
            }
            case Step.Expect expect -> {
                checkMsgType(expect.msgType(), transport, application, where, problems);
                expect.match().keySet().forEach(builder::resolveTag);
                expect.assertions().keySet().forEach(builder::resolveTag);
                expect.capture().values().forEach(builder::resolveTag);
                for (String invariant : expect.invariants()) {
                    Invariant.parse(invariant);
                    Matcher operands = OPERAND.matcher(invariant);
                    while (operands.find()) {
                        builder.resolveTag(operands.group());
                    }
                }
                checkVars(expect.match().toString() + expect.assertions().toString(), captured, where, problems);
                captured.addAll(expect.capture().keySet());
            }
            case Step.ExpectNone none -> {
                checkMsgType(none.msgType(), transport, application, where, problems);
                none.match().keySet().forEach(builder::resolveTag);
                checkVars(none.match().toString(), captured, where, problems);
            }
            case Step.SkipOutboundSequence skip -> {
                if (skip.captureAs() != null) {
                    captured.add(skip.captureAs());
                }
            }
            default -> {
                // session actions need no dictionary checks
            }
        }
    }

    private static void checkMsgType(
            String msgType, DataDictionary transport, DataDictionary application, String where, List<String> problems) {
        DataDictionary dictionary = MessageUtils.isAdminMessage(msgType) ? transport : application;
        if (!dictionary.isMsgType(msgType)) {
            problems.add(where + "MsgType " + msgType + " not defined");
        }
    }

    private static void checkVars(String text, Set<String> captured, String where, List<String> problems) {
        Matcher matcher = VAR.matcher(text);
        while (matcher.find()) {
            if (!captured.contains(matcher.group(1))) {
                problems.add(where + "variable '" + matcher.group(1) + "' is used before it is captured");
            }
        }
    }

    /** Fails fast on any problem; used at catalogue load time. */
    public static void requireValid(List<Scenario> scenarios, Map<String, List<String>> suites) {
        ScenarioLinter linter = new ScenarioLinter();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : scenarios) {
            if (!ids.add(scenario.id())) {
                problems.add("Duplicate scenario id " + scenario.id());
            }
            problems.addAll(linter.lint(scenario));
        }
        suites.forEach((suite, members) -> members.stream().filter(id -> !ids.contains(id))
                .forEach(id -> problems.add("Suite " + suite + " references unknown scenario " + id)));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid scenario catalogue:\n  " + String.join("\n  ", problems));
        }
    }
}
