/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.datastore.gorm.query.transform;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import org.grails.datastore.mapping.reflect.AstUtils;

/**
 * {@link ClassCodeVisitorSupport} that detects GORM HQL/Cypher query text built from a
 * {@link GStringExpression} that Groovy coerced to a plain {@code String} <em>before</em> it
 * reaches a GORM query method, e.g.:
 *
 * <pre>{@code
 * String query = "from Book where name = ${userInput}"   // coerced to String right here
 * Book.executeQuery(query)                                // -> raw, unescaped text, no binding
 * }</pre>
 *
 * <p>When a {@link groovy.lang.GString} is passed directly to a GORM query method, GORM binds
 * each interpolated value as a query parameter — safe. Once the {@code GString} has been coerced
 * to a {@code String} (an explicit {@code String}-typed local, a {@code .toString()} call, or an
 * {@code as String}/cast coercion), that information is gone: a {@code String} carries no trace
 * of ever having been a {@code GString}, so this can only be caught here, before the coercion
 * erases it — a runtime check at the query boundary is structurally blind to this case.
 *
 * <p>A {@code GString} does not have to be flattened directly at the call site to be unsafe —
 * aliasing it through one or more intermediate variables still loses the binding the moment it is
 * assigned to a {@code String}-typed variable, however many hops away that happens:
 *
 * <pre>{@code
 * def g = "from Book where name = ${userInput}"   // g: still a live GString
 * String q = g                                     // flattened HERE, not at the executeQuery call
 * Book.executeQuery(q)
 * }</pre>
 *
 * <p><strong>Query text vs. values.</strong> Only an interpolation that can carry runtime data is
 * a finding. An interpolated expression that is <em>constant text</em> cannot be influenced by
 * user input, so a {@code GString} whose interpolations are all constant text is treated exactly
 * like a plain {@code String} literal. Constant text is: a literal; a {@code static final} field
 * initialised from constant text; a ternary or Elvis expression choosing between constant text; a
 * {@code +} concatenation, {@code GString}, cast or {@code .toString()} of constant text; and a
 * local variable that only ever held constant text on every path reaching the use. This is what
 * lets query text be assembled from fixed HQL fragments chosen at runtime, which a {@code GString}
 * passed directly to GORM could not express (GORM would bind the fragment as a parameter value):
 *
 * <pre>{@code
 * String restriction = ""
 * if (params.title) {
 *     restriction = " and b.title = :title"      // constant text, chosen at runtime
 *     queryParams.title = params.title           // the value is bound, never interpolated
 * }
 * String query = "from Book b where 1 = 1 ${restriction}"   // not a finding
 * Book.executeQuery(query, queryParams)
 * }</pre>
 *
 * <p>Compound assignment with {@code +=} is tracked exactly like {@code x = x + y}: a constant
 * local stays constant when the appended text is constant, a live {@code GString} operand is
 * flattened (Groovy's {@code GString.plus} returns {@code String}), and an already-flattened one
 * stays flattened. The same holds for any {@code +} concatenation: a flattened or live
 * {@code GString} operand anywhere in it makes the whole result flattened, not merely a
 * lower-confidence concatenation.
 *
 * <p>This is a build-breaking error for the local-variable case above, because the detection is
 * precise: every flattening point is visible in the method being compiled. Two related patterns
 * are lower-confidence and instead reported as compile-time <em>warnings</em>, which do not fail
 * the build:
 *
 * <ul>
 *     <li><strong>Fields.</strong> A {@code String}-typed field initialized from an interpolated
 *     {@code GString}, or assigned one via {@code this.field = ...}, that later reaches a query
 *     method through {@code this.field}. Unlike locals, a field can be reassigned from a
 *     constructor, another method, or a subclass that this check never visits, so it is flagged
 *     rather than failed.</li>
 *     <li><strong>String concatenation.</strong> Query text built with {@code +} from a
 *     non-constant value and no {@code GString} involved at all, e.g.
 *     {@code "select ... " + userInput}. This is a real injection shape, but concatenation is
 *     common enough for benign, non-query purposes that a hard failure would be too blunt an
 *     instrument. Concatenating only constant text, as defined above, is not a finding.</li>
 * </ul>
 *
 * <p>Both warnings share the same {@link #SUPPRESS_WARNINGS_VALUE} suppression as the error case.
 * The suppression applies to the enclosing method or class, or - more narrowly - to a single
 * local variable or field declaration: {@code @SuppressWarnings("GormUnsafeQueryString") String
 * query = ...} marks that variable as reviewed for the rest of the method, whatever it is later
 * assigned, and leaves every other variable checked. A reviewed variable is also treated as
 * constant text wherever it is interpolated or concatenated, so the annotation may go either on
 * the assembled query or on the fragment it is built from.
 *
 * <p><strong>Known limitations (deliberate scope):</strong>
 * <ul>
 *     <li>Intraprocedural only — a flattened {@code String} built inside a helper method and
 *     returned to the caller is invisible to this check, and so is constant text returned from
 *     one (it is treated as data).</li>
 *     <li>Constant-text tracking follows locals, {@code static final} fields, ternaries and
 *     {@code +}/{@code +=}/{@code GString} composition only - not collections (for example
 *     fragments gathered in a {@code List} and joined), method calls, or non-final fields.</li>
 *     <li>Reassignment tracking for locals is branch-sensitive across a single {@code if}/{@code
 *     else} (a variable unsafe after either branch stays unsafe, and constant only if constant
 *     after both), but not across loops, {@code switch}, or {@code try}/{@code catch} - and is
 *     last-write-wins for fields, which are not branch-sensitive at all.</li>
 *     <li>Field tracking only recognizes a directly-interpolated {@code GString} initializer or
 *     {@code this.field = ...} assignment - it does not follow aliasing chains or
 *     {@code .toString()}/cast coercions the way local tracking does.</li>
 *     <li>Does not detect raw JDBC via {@code groovy.sql.Sql}, or any datastore whose query
 *     methods use names outside {@link #CANDIDATE_METHODS}.</li>
 * </ul>
 *
 * @since 8.0
 */
public class GormQuerySafetyTransformer extends ClassCodeVisitorSupport {

    /**
     * What a tracked local variable currently holds, from this check's point of view.
     */
    private enum Origin {
        /** Not derived from an interpolated GString or unsafe concatenation - nothing to track. */
        NONE,
        /** Constant text (see the class Javadoc) - can never carry runtime data, so safe anywhere. */
        CONSTANT,
        /** Still a real {@link groovy.lang.GString} - safe if passed directly to a query method. */
        LIVE_GSTRING,
        /** Already coerced to a plain {@code String} - unsafe if it reaches a query method. */
        FLATTENED,
        /** Built via {@code +} concatenation of a non-constant value, no GString involved. */
        CONCATENATED
    }

    /**
     * What kind of unsafe query argument was found at a candidate call site, and therefore how
     * severely (and with what message) to report it.
     */
    private enum Finding {
        NONE,
        FLATTENED_GSTRING,
        FLATTENED_FIELD,
        UNSAFE_CONCATENATION
    }

    /**
     * The {@code @SuppressWarnings} value that silences this check (both the error and the two
     * warnings below) on the enclosing method (or, for calls outside any method, the enclosing
     * class), or on a single local variable or field declaration.
     */
    public static final String SUPPRESS_WARNINGS_VALUE = "GormUnsafeQueryString";

    /**
     * Shared tail of every message: how to silence the check for a reviewed call site.
     */
    private static final String SUPPRESSION_HINT = "To suppress this check for a reviewed, safe call site, add " +
            "@SuppressWarnings(\"" + SUPPRESS_WARNINGS_VALUE + "\") to the declaration of the variable " +
            "that holds the query text, or to the enclosing method.";

    private static final Set<String> CANDIDATE_METHODS = new HashSet<>(Arrays.asList(
            "find", "findAll", "executeQuery", "executeUpdate",
            "findAllWithSql", "cypherStatic", "findPath", "findPathTo"));

    /**
     * The positional index of the query argument for each candidate method. Every candidate
     * method takes the query as its first argument except Neo4j's
     * {@code findPathTo(Class type, CharSequence query, Map params)}.
     */
    private static final Map<String, Integer> QUERY_ARGUMENT_INDEX = buildQueryArgumentIndex();

    private static Map<String, Integer> buildQueryArgumentIndex() {
        Map<String, Integer> indexes = new HashMap<>();
        for (String method : CANDIDATE_METHODS) {
            indexes.put(method, 0);
        }
        indexes.put("findPathTo", 1);
        return Collections.unmodifiableMap(indexes);
    }

    private final SourceUnit sourceUnit;
    private final Map<String, ASTNode> flattenedStringVars = new HashMap<>();
    private final Map<String, ASTNode> liveGStringVars = new HashMap<>();
    private final Map<String, ASTNode> concatenatedStringVars = new HashMap<>();
    private final Set<String> constantTextVars = new HashSet<>();
    private final Set<String> suppressedVars = new HashSet<>();
    private final Map<String, ASTNode> flattenedFields = new HashMap<>();
    private final Set<String> resolvingFields = new HashSet<>();
    private ClassNode currentClassNode;
    private MethodNode currentMethodNode;

    public GormQuerySafetyTransformer(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return this.sourceUnit;
    }

    @Override
    public void visitClass(ClassNode node) {
        try {
            this.currentClassNode = node;
            // Pre-scan field initializers so a field flattened here is already tracked no matter
            // which order the base class visits fields vs. methods in.
            for (FieldNode field : node.getFields()) {
                trackFieldInitializer(field);
            }
            super.visitClass(node);
        } finally {
            this.currentClassNode = null;
            clearTracking();
            flattenedFields.clear();
        }
    }

    @Override
    public void visitMethod(MethodNode node) {
        this.currentMethodNode = node;
        try {
            super.visitMethod(node);
        } finally {
            this.currentMethodNode = null;
            clearTracking();
        }
    }

    private void clearTracking() {
        flattenedStringVars.clear();
        liveGStringVars.clear();
        concatenatedStringVars.clear();
        constantTextVars.clear();
        suppressedVars.clear();
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        // getVariableExpression() is null for multiple-assignment declarations, e.g. def (a, b) = [...]
        VariableExpression variableExpression = expression.isMultipleAssignmentDeclaration() ?
                null : expression.getVariableExpression();
        if (variableExpression != null) {
            String name = variableExpression.getName();
            if (isSuppressedNode(expression)) {
                // The declaration has been reviewed: the variable stays unchecked for the rest of
                // the method, whatever it is later assigned.
                suppressedVars.add(name);
                untrack(name);
            }
            else {
                // A fresh, unannotated declaration of the same name re-arms the check for it.
                suppressedVars.remove(name);
                track(name, expression.getRightExpression(), variableExpression.getType(), expression);
            }
        }
        super.visitDeclarationExpression(expression);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        int operation = expression.getOperation().getType();
        Expression left = expression.getLeftExpression();
        if (operation == Types.ASSIGN) {
            if (left instanceof VariableExpression) {
                VariableExpression leftVariable = (VariableExpression) left;
                track(leftVariable.getName(), expression.getRightExpression(), leftVariable.getType(), expression);
            }
            else if (isThisFieldReference(left)) {
                trackField(fieldNameOf(left), expression.getRightExpression(), expression);
            }
        }
        else if (operation == Types.PLUS_EQUAL && left instanceof VariableExpression) {
            // x += y is x = x + y: classify the equivalent concatenation so constant text stays
            // constant, a live GString operand flattens, and a flattened one stays flattened.
            VariableExpression leftVariable = (VariableExpression) left;
            Token plus = Token.newSymbol(Types.PLUS, expression.getLineNumber(), expression.getColumnNumber());
            BinaryExpression concatenation = new BinaryExpression(left, plus, expression.getRightExpression());
            track(leftVariable.getName(), concatenation, leftVariable.getType(), expression);
        }
        else if (Types.ofType(operation, Types.ASSIGNMENT_OPERATOR) && left instanceof VariableExpression) {
            // Any other compound assignment is not string building this check understands, so
            // the variable can no longer be relied on as constant text.
            constantTextVars.remove(((VariableExpression) left).getName());
        }
        super.visitBinaryExpression(expression);
    }

    /**
     * Visits an {@code if}/{@code else} branch-sensitively: each branch is walked from the same
     * starting state, and the two resulting states are merged pessimistically afterwards - a
     * variable unsafe at the end of either branch stays unsafe after the statement, and one is
     * constant text only if it is constant at the end of both, since we don't know at compile
     * time which branch will actually run. Without this, whichever branch happens to be visited
     * last would silently win, e.g. a variable flattened only in the {@code if} branch would be
     * forgotten if the {@code else} branch reassigns it safely.
     */
    @Override
    public void visitIfElse(IfStatement ifElse) {
        ifElse.getBooleanExpression().visit(this);

        TrackingSnapshot beforeBranches = snapshot();

        ifElse.getIfBlock().visit(this);
        TrackingSnapshot afterIf = snapshot();

        restore(beforeBranches);
        ifElse.getElseBlock().visit(this);
        TrackingSnapshot afterElse = snapshot();

        restore(mergePessimistically(afterIf, afterElse));
    }

    private TrackingSnapshot snapshot() {
        return new TrackingSnapshot(flattenedStringVars, liveGStringVars, concatenatedStringVars,
                constantTextVars, suppressedVars);
    }

    private void restore(TrackingSnapshot state) {
        flattenedStringVars.clear();
        flattenedStringVars.putAll(state.flattened);
        liveGStringVars.clear();
        liveGStringVars.putAll(state.live);
        concatenatedStringVars.clear();
        concatenatedStringVars.putAll(state.concatenated);
        constantTextVars.clear();
        constantTextVars.addAll(state.constant);
        suppressedVars.clear();
        suppressedVars.addAll(state.suppressed);
    }

    private TrackingSnapshot mergePessimistically(TrackingSnapshot a, TrackingSnapshot b) {
        Set<String> names = new HashSet<>();
        names.addAll(a.flattened.keySet());
        names.addAll(a.live.keySet());
        names.addAll(a.concatenated.keySet());
        names.addAll(b.flattened.keySet());
        names.addAll(b.live.keySet());
        names.addAll(b.concatenated.keySet());

        Map<String, ASTNode> mergedFlattened = new HashMap<>();
        Map<String, ASTNode> mergedLive = new HashMap<>();
        Map<String, ASTNode> mergedConcatenated = new HashMap<>();

        for (String name : names) {
            // Unsafe states win pessimistically: if either branch leaves this variable unsafe,
            // that state survives past the if/else regardless of which branch actually runs.
            if (a.flattened.containsKey(name) || b.flattened.containsKey(name)) {
                mergedFlattened.put(name, a.flattened.containsKey(name) ? a.flattened.get(name) : b.flattened.get(name));
            }
            else if (a.concatenated.containsKey(name) || b.concatenated.containsKey(name)) {
                mergedConcatenated.put(name, a.concatenated.containsKey(name) ? a.concatenated.get(name) : b.concatenated.get(name));
            }
            else if (a.live.containsKey(name) || b.live.containsKey(name)) {
                mergedLive.put(name, a.live.containsKey(name) ? a.live.get(name) : b.live.get(name));
            }
        }

        // Constant text is the one state that must hold on both paths to survive the merge.
        Set<String> mergedConstant = new HashSet<>(a.constant);
        mergedConstant.retainAll(b.constant);

        // A declaration reviewed on either path stays reviewed: the name is either out of scope
        // after the statement or was declared (and so re-armed or re-suppressed) on both paths.
        Set<String> mergedSuppressed = new HashSet<>(a.suppressed);
        mergedSuppressed.addAll(b.suppressed);

        return new TrackingSnapshot(mergedFlattened, mergedLive, mergedConcatenated, mergedConstant, mergedSuppressed);
    }

    private static final class TrackingSnapshot {

        final Map<String, ASTNode> flattened;
        final Map<String, ASTNode> live;
        final Map<String, ASTNode> concatenated;
        final Set<String> constant;
        final Set<String> suppressed;

        TrackingSnapshot(Map<String, ASTNode> flattened, Map<String, ASTNode> live, Map<String, ASTNode> concatenated,
                Set<String> constant, Set<String> suppressed) {
            this.flattened = new HashMap<>(flattened);
            this.live = new HashMap<>(live);
            this.concatenated = new HashMap<>(concatenated);
            this.constant = new HashSet<>(constant);
            this.suppressed = new HashSet<>(suppressed);
        }
    }

    /**
     * Records what {@code variableName} now holds after being assigned {@code rightExpression},
     * resolving through any variable aliasing so a {@code GString} tracked several assignments
     * earlier is still recognised as unsafe once it (or an alias of it) reaches a
     * {@code String}-typed variable. A variable whose declaration carries the suppression is
     * never tracked, whatever it is assigned.
     */
    private void track(String variableName, Expression rightExpression, ClassNode declaredType, ASTNode locationNode) {
        // Classify against the state *before* this assignment, so self-references such as
        // q = q + "..." see what q held until now.
        Origin origin = classify(rightExpression, declaredType);
        // Any reassignment first clears prior tracking under every category - last write wins
        // for what follows, then the switch below re-establishes tracking if still relevant.
        untrack(variableName);
        if (suppressedVars.contains(variableName)) {
            return;
        }
        switch (origin) {
            case FLATTENED:
                flattenedStringVars.put(variableName, locationNode);
                break;
            case LIVE_GSTRING:
                liveGStringVars.put(variableName, locationNode);
                break;
            case CONCATENATED:
                concatenatedStringVars.put(variableName, locationNode);
                break;
            case CONSTANT:
                constantTextVars.add(variableName);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void untrack(String variableName) {
        flattenedStringVars.remove(variableName);
        liveGStringVars.remove(variableName);
        concatenatedStringVars.remove(variableName);
        constantTextVars.remove(variableName);
    }

    /**
     * Determines what {@code expression} evaluates to, from this check's point of view, resolving
     * one level of variable reference against the current tracking state so aliasing chains
     * (however many hops long) are followed correctly - each hop was itself already classified
     * when its own assignment was visited.
     */
    private Origin classify(Expression expression, ClassNode declaredType) {
        if (expression instanceof VariableExpression) {
            String name = ((VariableExpression) expression).getName();
            if (flattenedStringVars.containsKey(name)) {
                return Origin.FLATTENED; // already a plain String - stays unsafe regardless of declaredType
            }
            if (concatenatedStringVars.containsKey(name)) {
                return Origin.CONCATENATED; // already a plain String - stays unsafe regardless of declaredType
            }
            if (liveGStringVars.containsKey(name)) {
                return ClassHelper.STRING_TYPE.equals(declaredType) ? Origin.FLATTENED : Origin.LIVE_GSTRING;
            }
            return isConstantText(expression) ? Origin.CONSTANT : Origin.NONE;
        }
        if (isConstantText(expression)) {
            return Origin.CONSTANT;
        }
        if (isDataInterpolatedGString(expression)) {
            return ClassHelper.STRING_TYPE.equals(declaredType) ? Origin.FLATTENED : Origin.LIVE_GSTRING;
        }
        if (expression instanceof CastExpression) {
            CastExpression cast = (CastExpression) expression;
            if (ClassHelper.STRING_TYPE.equals(cast.getType()) && isUnsafeSource(cast.getExpression())) {
                return Origin.FLATTENED;
            }
            return Origin.NONE;
        }
        if (expression instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) expression;
            if ("toString".equals(call.getMethodAsString()) && isUnsafeSource(call.getObjectExpression())) {
                return Origin.FLATTENED; // .toString() always yields a String, regardless of declaredType
            }
            return Origin.NONE;
        }
        return classifyConcatenation(expression);
    }

    /**
     * Classifies a {@code +} concatenation. One with a flattened or live {@code GString} source
     * anywhere in the tree is {@link Origin#FLATTENED}, not merely {@link Origin#CONCATENATED} -
     * concatenating a GString with anything else immediately converts it to a plain {@code String}
     * at runtime (Groovy's {@code GString.plus} returns {@code String}), the same irreversible
     * coercion a {@code .toString()} call causes, and a flattened operand stays flattened. A
     * concatenation with no such source, but at least one operand that is not constant text, is
     * the lower-confidence {@link Origin#CONCATENATED} case.
     */
    private Origin classifyConcatenation(Expression expression) {
        if (!isConcatenation(expression)) {
            return Origin.NONE;
        }
        if (containsUnsafeSource(expression)) {
            return Origin.FLATTENED;
        }
        if (hasNonConstantOperand(expression)) {
            return Origin.CONCATENATED;
        }
        return Origin.NONE;
    }

    /**
     * True when {@code expression} is itself a GString interpolating data, or a variable reference
     * already tracked as a live GString or an already-flattened String - i.e. anything a cast or
     * {@code .toString()} applied on top of would still be unsafe to hand to a query method.
     */
    private boolean isUnsafeSource(Expression expression) {
        if (isDataInterpolatedGString(expression)) {
            return true;
        }
        if (expression instanceof VariableExpression) {
            String name = ((VariableExpression) expression).getName();
            return flattenedStringVars.containsKey(name) || liveGStringVars.containsKey(name);
        }
        return false;
    }

    /**
     * A {@code GString} with at least one interpolation that is not constant text - the only kind
     * of {@code GString} that can carry runtime data into a query.
     */
    private boolean isDataInterpolatedGString(Expression expression) {
        return expression instanceof GStringExpression &&
                !allConstantText(((GStringExpression) expression).getValues());
    }

    private boolean allConstantText(List<Expression> expressions) {
        for (Expression expression : expressions) {
            if (!isConstantText(expression)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code expression} is constant text as defined in the class Javadoc: something that
     * is fully determined at compile time (or chosen at runtime from such things) and therefore
     * cannot carry user input into a query. A variable whose declaration carries the suppression
     * counts as reviewed, trusted text.
     */
    private boolean isConstantText(Expression expression) {
        if (expression instanceof ConstantExpression) {
            return true;
        }
        if (expression instanceof GStringExpression) {
            return allConstantText(((GStringExpression) expression).getValues());
        }
        if (expression instanceof VariableExpression) {
            return isConstantTextVariable((VariableExpression) expression);
        }
        if (expression instanceof PropertyExpression) {
            return isConstantTextField(staticFieldOf((PropertyExpression) expression));
        }
        if (expression instanceof TernaryExpression) {
            // Covers the Elvis operator too, whose "true" expression is its left operand.
            TernaryExpression ternary = (TernaryExpression) expression;
            return isConstantText(ternary.getTrueExpression()) && isConstantText(ternary.getFalseExpression());
        }
        if (isConcatenation(expression)) {
            BinaryExpression binary = (BinaryExpression) expression;
            return isConstantText(binary.getLeftExpression()) && isConstantText(binary.getRightExpression());
        }
        if (expression instanceof CastExpression) {
            return isConstantText(((CastExpression) expression).getExpression());
        }
        if (expression instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) expression;
            return "toString".equals(call.getMethodAsString()) && isConstantText(call.getObjectExpression());
        }
        return false;
    }

    /**
     * Resolves a bare name through what the compiler bound it to, so a {@code static final}
     * constant is recognised as such while a parameter or local that merely shadows one is not.
     */
    private boolean isConstantTextVariable(VariableExpression variable) {
        if (variable.isThisExpression() || variable.isSuperExpression()) {
            return false;
        }
        Variable accessed = variable.getAccessedVariable();
        if (accessed instanceof FieldNode) {
            return isConstantTextField((FieldNode) accessed);
        }
        if (accessed instanceof PropertyNode) {
            return isConstantTextField(((PropertyNode) accessed).getField());
        }
        if (accessed instanceof DynamicVariable) {
            return false;
        }
        return constantTextVars.contains(variable.getName()) || suppressedVars.contains(variable.getName());
    }

    /**
     * A {@code static final} field whose initializer is itself constant text. Instance fields and
     * non-final statics can be assigned from anywhere this check does not see, so they never
     * qualify; a field whose initializer refers back to itself (directly or through another
     * field) is not constant either.
     */
    private boolean isConstantTextField(FieldNode field) {
        if (field == null || !field.isStatic() || !field.isFinal() || field.getInitialValueExpression() == null) {
            return false;
        }
        ClassNode owner = field.getOwner();
        String key = (owner != null ? owner.getName() : "") + "." + field.getName();
        if (!resolvingFields.add(key)) {
            return false;
        }
        try {
            return isConstantText(field.getInitialValueExpression());
        } finally {
            resolvingFields.remove(key);
        }
    }

    /**
     * The field a {@code this.NAME} or {@code SomeClass.NAME} property reference denotes, or
     * {@code null} when the receiver is anything else (an instance, a method result, ...).
     */
    private FieldNode staticFieldOf(PropertyExpression property) {
        String name = property.getPropertyAsString();
        if (name == null) {
            return null;
        }
        Expression object = property.getObjectExpression();
        if (object instanceof ClassExpression) {
            return object.getType().getField(name);
        }
        if (isThisFieldReference(property) && currentClassNode != null) {
            return currentClassNode.getField(name);
        }
        return null;
    }

    private boolean isConcatenation(Expression expression) {
        return expression instanceof BinaryExpression &&
                ((BinaryExpression) expression).getOperation().getType() == Types.PLUS;
    }

    private boolean hasNonConstantOperand(Expression expression) {
        if (isConcatenation(expression)) {
            BinaryExpression binary = (BinaryExpression) expression;
            return hasNonConstantOperand(binary.getLeftExpression()) || hasNonConstantOperand(binary.getRightExpression());
        }
        return !isConstantText(expression);
    }

    private boolean containsUnsafeSource(Expression expression) {
        if (isConcatenation(expression)) {
            BinaryExpression binary = (BinaryExpression) expression;
            return containsUnsafeSource(binary.getLeftExpression()) || containsUnsafeSource(binary.getRightExpression());
        }
        return isUnsafeSource(expression);
    }

    /**
     * Seeds {@link #flattenedFields} from a field's own initializer, e.g.
     * {@code String query = "...${x}..."} declared directly on the class. Unlike local tracking,
     * this only recognises a bare interpolated GString initializer - not a {@code .toString()} or
     * cast coercion - to keep the (already lower-confidence) field check simple. A field whose
     * declaration carries the suppression is never tracked.
     */
    private void trackFieldInitializer(FieldNode field) {
        Expression initial = field.getInitialValueExpression();
        if (initial != null && isFlatteningFieldAssignment(field, initial)) {
            flattenedFields.put(field.getName(), field);
        }
    }

    /**
     * Records a {@code this.field = ...} assignment. A safe reassignment clears prior tracking
     * for that field - last-write-wins, with no branch-sensitivity (see class Javadoc).
     */
    private void trackField(String fieldName, Expression rightExpression, ASTNode locationNode) {
        FieldNode field = currentClassNode != null ? currentClassNode.getField(fieldName) : null;
        if (isFlatteningFieldAssignment(field, rightExpression)) {
            flattenedFields.put(fieldName, locationNode);
        }
        else {
            flattenedFields.remove(fieldName);
        }
    }

    private boolean isFlatteningFieldAssignment(FieldNode field, Expression value) {
        return field != null && !isSuppressedNode(field) &&
                ClassHelper.STRING_TYPE.equals(field.getType()) && isDataInterpolatedGString(value);
    }

    private boolean isThisFieldReference(Expression expression) {
        if (!(expression instanceof PropertyExpression)) {
            return false;
        }
        PropertyExpression property = (PropertyExpression) expression;
        return property.getObjectExpression() instanceof VariableExpression &&
                ((VariableExpression) property.getObjectExpression()).isThisExpression() &&
                property.getPropertyAsString() != null;
    }

    private String fieldNameOf(Expression expression) {
        return ((PropertyExpression) expression).getPropertyAsString();
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        String methodName = call.getMethodAsString();
        if (methodName != null && CANDIDATE_METHODS.contains(methodName) &&
                isGormReceiver(call.getObjectExpression()) && !isSuppressed()) {
            report(findUnsafeArgument(methodName, call.getArguments()), call, methodName);
        }
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        String methodName = call.getMethod();
        if (CANDIDATE_METHODS.contains(methodName) &&
                AstUtils.isDomainClass(call.getOwnerType()) && !isSuppressed()) {
            report(findUnsafeArgument(methodName, call.getArguments()), call, methodName);
        }
        super.visitStaticMethodCallExpression(call);
    }

    private boolean isGormReceiver(Expression objectExpression) {
        if (objectExpression instanceof ClassExpression) {
            return AstUtils.isDomainClass(((ClassExpression) objectExpression).getType());
        }
        if (objectExpression instanceof VariableExpression && ((VariableExpression) objectExpression).isThisExpression()) {
            return currentClassNode != null && AstUtils.isDomainClass(currentClassNode);
        }
        return false;
    }

    private Finding findUnsafeArgument(String methodName, Expression arguments) {
        if (!(arguments instanceof ArgumentListExpression)) {
            return Finding.NONE;
        }
        List<Expression> args = ((ArgumentListExpression) arguments).getExpressions();
        Integer index = QUERY_ARGUMENT_INDEX.get(methodName);
        if (index == null || args.size() <= index) {
            return Finding.NONE;
        }
        Expression argument = args.get(index);

        if (argument instanceof VariableExpression) {
            String name = ((VariableExpression) argument).getName();
            if (flattenedStringVars.containsKey(name)) {
                return Finding.FLATTENED_GSTRING;
            }
            if (concatenatedStringVars.containsKey(name)) {
                return Finding.UNSAFE_CONCATENATION;
            }
            return Finding.NONE;
        }
        if (isThisFieldReference(argument) && flattenedFields.containsKey(fieldNameOf(argument))) {
            return Finding.FLATTENED_FIELD;
        }
        if (argument instanceof CastExpression) {
            CastExpression cast = (CastExpression) argument;
            if (ClassHelper.STRING_TYPE.equals(cast.getType()) && isUnsafeSource(cast.getExpression())) {
                return Finding.FLATTENED_GSTRING;
            }
        }
        if (argument instanceof MethodCallExpression) {
            MethodCallExpression flatteningCall = (MethodCallExpression) argument;
            if ("toString".equals(flatteningCall.getMethodAsString()) && isUnsafeSource(flatteningCall.getObjectExpression())) {
                return Finding.FLATTENED_GSTRING;
            }
        }
        Origin concatOrigin = classifyConcatenation(argument);
        if (concatOrigin == Origin.FLATTENED) {
            return Finding.FLATTENED_GSTRING;
        }
        if (concatOrigin == Origin.CONCATENATED) {
            return Finding.UNSAFE_CONCATENATION;
        }
        return Finding.NONE;
    }

    private void report(Finding finding, ASTNode node, String methodName) {
        switch (finding) {
            case FLATTENED_GSTRING:
                reportUnsafeQuery(node, methodName);
                break;
            case FLATTENED_FIELD:
                reportFlattenedFieldQuery(node, methodName);
                break;
            case UNSAFE_CONCATENATION:
                reportUnsafeConcatenation(node, methodName);
                break;
            case NONE:
            default:
                break;
        }
    }

    private boolean isSuppressed() {
        if (currentMethodNode != null && isSuppressedNode(currentMethodNode)) {
            return true;
        }
        return currentClassNode != null && isSuppressedNode(currentClassNode);
    }

    private boolean isSuppressedNode(AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations(ClassHelper.make(SuppressWarnings.class))) {
            Expression value = annotation.getMember("value");
            if (containsSuppressionValue(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSuppressionValue(Expression value) {
        if (value instanceof ConstantExpression) {
            return SUPPRESS_WARNINGS_VALUE.equals(((ConstantExpression) value).getValue());
        }
        if (value instanceof ListExpression) {
            for (Expression element : ((ListExpression) value).getExpressions()) {
                if (containsSuppressionValue(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reportUnsafeQuery(ASTNode node, String methodName) {
        String message = "[GORM] The query string passed to '" + methodName + "' was built from a " +
                "GString that Groovy already coerced to a plain String, so any interpolated " +
                "values are now embedded as raw, unescaped text - this is a query injection " +
                "risk. If the interpolated expressions are values, keep the query a GString when " +
                "calling '" + methodName + "' (GORM turns GString interpolations into bound query " +
                "parameters automatically), or pass named/positional parameters explicitly. If " +
                "they are query text instead - HQL fragments such as restrictions or an order-by " +
                "clause - a GString is the wrong tool, because GORM would bind the fragment as a " +
                "parameter value: build the text from constant fragments (string literals, static " +
                "final constants, and locals that only ever hold such text, which this check " +
                "recognises as safe) using '+', '+=' or a StringBuilder, and bind the values through " +
                "the params argument. " + SUPPRESSION_HINT;
        sourceUnit.getErrorCollector().addErrorAndContinue(message, node, sourceUnit);
    }

    private void reportFlattenedFieldQuery(ASTNode node, String methodName) {
        String message = "[GORM] The query string passed to '" + methodName + "' was built from a " +
                "field that was assigned a GString-interpolated value coerced to a plain String, " +
                "so any interpolated values may be embedded as raw, unescaped text - this is a " +
                "query injection risk if that field can be influenced by user input. This is a " +
                "warning rather than a build failure because field assignments outside this method " +
                "(other methods, constructors, subclasses) aren't visible to this check. Prefer " +
                "keeping the value as a GString or passing named/positional parameters. " +
                SUPPRESSION_HINT;
        reportWarning(node, message);
    }

    private void reportUnsafeConcatenation(ASTNode node, String methodName) {
        String message = "[GORM] The query string passed to '" + methodName + "' is built using " +
                "'+' string concatenation with a non-constant value, so no automatic parameter " +
                "binding is possible - this is a query injection risk if that value can be " +
                "influenced by user input. Prefer a GString (GORM binds interpolated values " +
                "automatically) or named/positional parameters for values; query text should be " +
                "built only from constant fragments, which this check does not flag. " +
                SUPPRESSION_HINT;
        reportWarning(node, message);
    }

    /**
     * Emits a compile-time warning (not a build failure) located at {@code node}.
     * {@link org.codehaus.groovy.control.ErrorCollector#addWarning} predates {@link ASTNode} and
     * still takes the older {@link org.codehaus.groovy.syntax.CSTNode} for location, so a minimal
     * {@link Token} carrying just the line/column is built to satisfy it.
     */
    private void reportWarning(ASTNode node, String message) {
        Token location = new Token(Types.UNKNOWN, "", node.getLineNumber(), node.getColumnNumber());
        sourceUnit.getErrorCollector().addWarning(WarningMessage.LIKELY_ERRORS, message, location, sourceUnit);
    }
}
