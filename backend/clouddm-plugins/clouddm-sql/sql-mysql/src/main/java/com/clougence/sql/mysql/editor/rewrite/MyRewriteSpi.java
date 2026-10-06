/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.sql.mysql.editor.rewrite;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.dslpaser.antlr.DslHelper;
import com.clougence.dslpaser.parse.AstSplitScript;
import com.clougence.sql.common.antlr.AntlrAstUtils;
import com.clougence.sql.mysql.parser.MyDslProvider;
import com.clougence.sql.mysql.parser.MySqlParserConfig;
import com.clougence.sql.mysql.parser.antlr.MySqlLexer;
import com.clougence.sql.mysql.parser.antlr.MySqlParser;
import com.clougence.utils.StringUtils;

public class MyRewriteSpi implements RewriteSpi {

    private final MyDslProvider provider;

    public MyRewriteSpi(MySqlParserConfig config){
        this.provider = new MyDslProvider(config);
    }

    protected MyDslProvider dslProvider() {
        return this.provider;
    }

    @Override
    public String rewriteLimit(String queryId, String queryStr, RewriteContext context) {
        List<AstSplitScript> scripts = DslHelper.splitDsl(dslProvider(), new StringReader(queryStr));
        Parser parser = scripts.get(0).getParser();
        ParseTree astTree = scripts.get(0).getAstTree();

        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokens);

        long maxLimit = context.getFetchLimit();
        if (maxLimit > 0) {
            this.rewriterLimit(rewriter, astTree, maxLimit);
        }

        return rewriter.getText();
    }

    private boolean rewriterLimit(TokenStreamRewriter rewriter, ParseTree astTree, long maxLimit) {
        MySqlParser.DmlStatementContext dmlStat = ((MySqlParser.SqlStatementContext) astTree).dmlStatement();
        if (dmlStat.selectStatement() != null) {
            MySqlParser.SelectStatementContext s = dmlStat.selectStatement();
            if (s instanceof MySqlParser.QuerySpecificationSelectContext) {
                return rewriterLimit(rewriter, maxLimit, (MySqlParser.QuerySpecificationSelectContext) s);
            } else {
                // TODO: other select type
            }
        } else if (dmlStat.withSelectStatement() != null) {
            MySqlParser.SelectStatementContext s = dmlStat.withSelectStatement().selectStatement();
            if (s instanceof MySqlParser.QuerySpecificationSelectContext) {
                return rewriterLimit(rewriter, maxLimit, (MySqlParser.QuerySpecificationSelectContext) s);
            } else {
                // TODO: other select type
            }
        }
        return false;
    }

    private static boolean rewriterLimit(TokenStreamRewriter rewriter, long maxLimit, MySqlParser.QuerySpecificationSelectContext s) {
        if (!s.querySpecificationSelectTail().unionStatement().isEmpty()) {
            return false;
        }

        MySqlParser.QuerySpecificationContext querySpec = s.querySpecification();
        if (querySpec.fromClause() == null) {
            return false;
        }

        if (querySpec.limitClause() != null) {
            MySqlParser.LimitClauseContext limitClause = querySpec.limitClause();
            MySqlParser.UnsignedDecimalIntegerLiteralContext integerLiteralCtx = limitClause.limit.unsignedDecimalIntegerLiteral();
            if (integerLiteralCtx == null) {
                return false;
            }

            BigInteger sqlLimit = new BigInteger(integerLiteralCtx.getText());
            if (sqlLimit.compareTo(BigInteger.valueOf(maxLimit)) > 0) {
                rewriter.replace(integerLiteralCtx.getStart(), integerLiteralCtx.getStop(), maxLimit);
                return true;
            } else {
                return false;
            }
        } else {
            rewriter.insertAfter(querySpec.getStop(), " LIMIT " + maxLimit);
            return true;
        }
    }

    @Override
    public String rewriteToExplain(String queryId, String queryStr, RewriteContext context) {
        List<AstSplitScript> scripts = DslHelper.splitDsl(this.dslProvider(), new StringReader(queryStr));
        if (scripts.size() != 1) {
            return null;
        }

        ParseTree astTree = scripts.get(0).getAstTree();
        if (isExplainStatement(astTree)) {
            return queryStr;
        }
        if (!(astTree instanceof MySqlParser.SqlStatementContext statement) || statement.dmlStatement() == null) {
            return null;
        }

        return "EXPLAIN FORMAT=TRADITIONAL " + queryStr;
    }

    private static boolean isExplainStatement(ParseTree tree) {
        if (tree instanceof MySqlParser.SimpleDescribeStatementContext statement) {
            return "EXPLAIN".equalsIgnoreCase(statement.command.getText());
        }
        if (tree instanceof MySqlParser.FullDescribeStatementContext statement) {
            return "EXPLAIN".equalsIgnoreCase(statement.command.getText());
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (isExplainStatement(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================
    // B7 lock-free DML — batch shape gate + keyset rewrite (design 09-17-lockfree-dml §3).
    //
    // Discipline (B6 GhostAlterStmtChecker heritage, 宁可误拒不可误放):
    //   * analyze NEVER throws — anything unexpected degrades to a not-supported shape;
    //   * the original statement text is never string-spliced: every rewrite is a
    //     TokenStreamRewriter node operation (rewriteLimit is the in-file precedent);
    //   * literals (loLiteral/hiLiteral) arrive PRE-RENDERED from the console (single escaping
    //     point, R18) and are injected verbatim; only the key column identifier is quoted here
    //     (backticks with doubling) after a defensive [\w$]+ whitelist re-check.
    // =====================================================================================

    /** time/random-ish simple functions (specificFunction #simpleFunctionCall first token). */
    private static final Set<String> NONDET_SIMPLE_FUNCTIONS = Set.of(
            "NOW", "SYSDATE", "CURDATE", "CURTIME",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "LOCALTIME", "LOCALTIMESTAMP", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP");

    /** non-deterministic / session-state functions reached via genericFunction (name in functionNameBase). */
    private static final Set<String> NONDET_GENERIC_FUNCTIONS = Set.of(
            "RAND", "RANDOM", "UUID", "UUID_SHORT", "UNIX_TIMESTAMP",
            "CONNECTION_ID", "LAST_INSERT_ID", "ROW_COUNT", "FOUND_ROWS");

    /** B6 validateIdentifier heritage: bare ASCII identifier only (probe result, never user text). */
    private static final Pattern     IDENTIFIER_WHITELIST    = Pattern.compile("[\\w$]+");

    @Override
    public DmlBatchShapeMO analyzeDmlForBatch(String queryId, String sql, RewriteContext context) {
        try {
            return doAnalyzeDmlForBatch(sql, context);
        } catch (RuntimeException e) {
            // degradation contract: a broken evaluation must never block ticket submission
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_PARSE_ERROR);
        }
    }

    private DmlBatchShapeMO doAnalyzeDmlForBatch(String sql, RewriteContext context) {
        if (StringUtils.isBlank(sql)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_PARSE_ERROR);
        }
        List<AstSplitScript> scripts = DslHelper.splitDsl(dslProvider(), new StringReader(sql));
        if (scripts.isEmpty()) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
        }
        if (scripts.size() != 1) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_STATEMENT);
        }
        AstSplitScript script = scripts.get(0);
        CommonTokenStream tokens = (CommonTokenStream) script.getParser().getTokenStream();
        if (containsComment(tokens)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_COMMENT);
        }
        if (!(script.getAstTree() instanceof MySqlParser.SqlStatementContext statement) || statement.dmlStatement() == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
        }
        MySqlParser.DmlStatementContext dml = statement.dmlStatement();
        String expectedSchema = context == null ? null : context.getSchemaName();
        if (dml.updateStatement() != null) {
            MySqlParser.SingleUpdateStatementContext single = dml.updateStatement().singleUpdateStatement();
            if (single == null) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_TABLE);
            }
            if (containsSessionVariable(tokens)) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
            }
            return analyzeSingleUpdate(single, expectedSchema);
        }
        if (dml.deleteStatement() != null) {
            MySqlParser.SingleDeleteStatementContext single = dml.deleteStatement().singleDeleteStatement();
            if (single == null) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_TABLE);
            }
            if (containsSessionVariable(tokens)) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
            }
            return analyzeSingleDelete(single, expectedSchema);
        }
        return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
    }

    private DmlBatchShapeMO analyzeSingleUpdate(MySqlParser.SingleUpdateStatementContext single, String expectedSchema) {
        if (single.withClause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_CTE);
        }
        if (single.PARTITION() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_PARTITION);
        }
        if (!single.indexHint().isEmpty()) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_INDEX_HINT);
        }
        MyTableRef tableRef = myTableRef(single.tableName());
        if (tableRef == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        if (isSchemaMismatch(tableRef.schema, expectedSchema)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH);
        }
        List<MySqlParser.UpdatedElementContext> elements = single.updatedElement();
        if (elements.isEmpty()) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        for (MySqlParser.UpdatedElementContext element : elements) {
            if (element.expression() != null
                    && !AntlrAstUtils.descendants(element.expression(), MySqlParser.SubqueryStatementContext.class).isEmpty()) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SET_SUBQUERY);
            }
        }
        if (single.orderByClause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_ORDER_BY);
        }
        if (single.limitClause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_LIMIT);
        }
        if (single.whereClause() == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_NO_WHERE);
        }

        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.setSupported(true);
        mo.setStatementType(SplitQueryType.UPDATE);
        mo.setSchemaName(tableRef.schema);
        mo.setTableName(tableRef.table);
        mo.setAlias(single.uid() != null ? unquoteIdentifier(single.uid().getText()) : null);
        mo.setHasWhere(true);

        boolean selfReferencing = false;
        Set<String> nondeterministic = new LinkedHashSet<>();
        for (MySqlParser.UpdatedElementContext element : elements) {
            String target = lastColumnName(element.fullColumnName());
            if (target != null) {
                mo.getSetColumns().add(target);
            }
            if (element.expression() == null) {
                continue; // SET x = DEFAULT — deterministic
            }
            if (target != null && referencesColumn(element.expression(), target)) {
                selfReferencing = true;
            }
            collectNondeterministicFunctions(element.expression(), nondeterministic);
        }
        mo.setSelfReferencingSet(selfReferencing);
        mo.getNondeterministicFunctions().addAll(nondeterministic);
        return mo;
    }

    private DmlBatchShapeMO analyzeSingleDelete(MySqlParser.SingleDeleteStatementContext single, String expectedSchema) {
        if (single.withClause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_CTE);
        }
        if (single.PARTITION() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_PARTITION);
        }
        MyTableRef tableRef = myTableRef(single.tableName());
        if (tableRef == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        if (isSchemaMismatch(tableRef.schema, expectedSchema)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH);
        }
        if (single.orderByClause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_ORDER_BY);
        }
        if (single.LIMIT() != null || single.limitClauseAtom() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_LIMIT);
        }
        if (single.whereClause() == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_NO_WHERE);
        }

        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.setSupported(true);
        mo.setStatementType(SplitQueryType.DELETE);
        mo.setSchemaName(tableRef.schema);
        mo.setTableName(tableRef.table);
        mo.setAlias(single.deleteTableAlias() != null && single.deleteTableAlias().uid() != null
                ? unquoteIdentifier(single.deleteTableAlias().uid().getText())
                : null);
        mo.setHasWhere(true);
        // DELETE is naturally idempotent (re-deleting an already-deleted row is a no-op)
        return mo;
    }

    @Override
    public String rewriteToBatchBoundarySelect(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        validateRangeSpec(spec, false);
        try {
            MyBatchTarget target = locateBatchTarget(sql);
            if (target == null) {
                return null;
            }
            String quotedKey = quoteIdentifier(spec.getKeyColumn());
            String keyRef = target.aliasRawText != null ? target.aliasRawText + "." + quotedKey : quotedKey;
            String tableText = target.tokens.getText(Interval.of(
                    target.tableRefStart.getTokenIndex(), target.tableRefStop.getTokenIndex()));
            String innerPrefix = "SELECT MAX(" + quotedKey + ") FROM (SELECT " + keyRef + " FROM " + tableText + " ";
            String orderByLimit = "ORDER BY " + keyRef + " LIMIT " + spec.getLimit();

            if (target.where == null) {
                // defensive bare form (the analyze gate excludes it): the whole statement up to the
                // bare anchor (last SET element / table reference) becomes the inner SELECT
                String bareWhere = StringUtils.isBlank(spec.getLoLiteral()) ? "" : "WHERE " + keyRef + " > " + spec.getLoLiteral() + " ";
                target.rewriter.replace(target.statement.getStart(), target.bareAnchorStop, innerPrefix + bareWhere + orderByLimit + ") bnd");
                return target.rewriter.getText();
            }
            MySqlParser.ExpressionContext expr = target.where.expression();
            if (expr == null) {
                return null;
            }
            Token beforeWhere = target.tokens.get(target.where.getStart().getTokenIndex() - 1);
            target.rewriter.replace(target.statement.getStart(), beforeWhere, innerPrefix);
            target.rewriter.insertBefore(expr.getStart(), "(");
            String lowerBound = StringUtils.isBlank(spec.getLoLiteral()) ? "" : " AND " + keyRef + " > " + spec.getLoLiteral();
            target.rewriter.insertAfter(expr.getStop(), ")" + lowerBound + " " + orderByLimit + ") bnd");
            return target.rewriter.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String rewriteToBatchRange(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        validateRangeSpec(spec, true);
        try {
            MyBatchTarget target = locateBatchTarget(sql);
            if (target == null) {
                return null;
            }
            String quotedKey = quoteIdentifier(spec.getKeyColumn());
            String keyRef = target.aliasRawText != null ? target.aliasRawText + "." + quotedKey : quotedKey;
            String lowerBound = StringUtils.isBlank(spec.getLoLiteral()) ? "" : keyRef + " > " + spec.getLoLiteral() + " AND ";
            String range = lowerBound + keyRef + " <= " + spec.getHiLiteral();

            if (target.where == null) {
                // defensive bare injection (the analyze gate excludes no-WHERE statements)
                target.rewriter.insertAfter(target.bareAnchorStop, " WHERE " + range);
                return target.rewriter.getText();
            }
            MySqlParser.ExpressionContext expr = target.where.expression();
            if (expr == null) {
                return null;
            }
            target.rewriter.insertBefore(expr.getStart(), "((");
            target.rewriter.insertAfter(expr.getStop(), ") AND " + range + ")");
            return target.rewriter.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** parsed + minimally shape-gated rewrite target (defensive gate: correctness-critical items only). */
    private static final class MyBatchTarget {

        private CommonTokenStream  tokens;
        private TokenStreamRewriter rewriter;
        private ParserRuleContext  statement;
        private MySqlParser.WhereClauseContext where;
        private Token              tableRefStart;
        private Token              tableRefStop;
        private Token              bareAnchorStop;
        private String             aliasRawText;
    }

    /**
     * Locate + defensive gate for the rewrite methods. Returns null when the statement is not a
     * transformable single-table UPDATE/DELETE (unparsable, multi-statement, multi-table, CTE,
     * native ORDER BY/LIMIT). Comments / schema prefix / indexHint are NOT re-checked here — the
     * analyze gate owns those decisions and the rewrite stays text-faithful either way.
     */
    private MyBatchTarget locateBatchTarget(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        List<AstSplitScript> scripts = DslHelper.splitDsl(dslProvider(), new StringReader(sql));
        if (scripts.size() != 1) {
            return null;
        }
        AstSplitScript script = scripts.get(0);
        if (!(script.getAstTree() instanceof MySqlParser.SqlStatementContext statement) || statement.dmlStatement() == null) {
            return null;
        }
        MySqlParser.DmlStatementContext dml = statement.dmlStatement();
        CommonTokenStream tokens = (CommonTokenStream) script.getParser().getTokenStream();

        MyBatchTarget target = new MyBatchTarget();
        target.tokens = tokens;
        target.rewriter = new TokenStreamRewriter(tokens);
        if (dml.updateStatement() != null && dml.updateStatement().singleUpdateStatement() != null) {
            MySqlParser.SingleUpdateStatementContext single = dml.updateStatement().singleUpdateStatement();
            if (single.withClause() != null || single.orderByClause() != null || single.limitClause() != null
                    || single.tableName() == null || single.updatedElement().isEmpty()) {
                return null;
            }
            target.statement = single;
            target.where = single.whereClause();
            target.tableRefStart = single.tableName().getStart();
            Token stop = single.tableName().getStop();
            if (single.RR_BRACKET() != null) {
                stop = single.RR_BRACKET().getSymbol(); // PARTITION ( ... ) — keep inside the table reference
            }
            if (single.uid() != null) {
                stop = single.uid().getStop();
                target.aliasRawText = single.uid().getText();
            }
            target.tableRefStop = stop;
            target.bareAnchorStop = single.updatedElement(single.updatedElement().size() - 1).getStop();
            return target;
        }
        if (dml.deleteStatement() != null && dml.deleteStatement().singleDeleteStatement() != null) {
            MySqlParser.SingleDeleteStatementContext single = dml.deleteStatement().singleDeleteStatement();
            if (single.withClause() != null || single.orderByClause() != null || single.LIMIT() != null
                    || single.limitClauseAtom() != null || single.tableName() == null) {
                return null;
            }
            target.statement = single;
            target.where = single.whereClause();
            target.tableRefStart = single.tableName().getStart();
            // grammar order: tableName deleteTableAlias? (PARTITION '(' uidList ')')? — the
            // partition close bracket, when present, is always the last table-reference token
            Token stop = single.tableName().getStop();
            if (single.deleteTableAlias() != null) {
                stop = single.deleteTableAlias().getStop();
                if (single.deleteTableAlias().uid() != null) {
                    target.aliasRawText = single.deleteTableAlias().uid().getText();
                }
            }
            if (single.RR_BRACKET() != null) {
                stop = single.RR_BRACKET().getSymbol(); // PARTITION ( ... )
            }
            target.tableRefStop = stop;
            target.bareAnchorStop = stop;
            return target;
        }
        return null;
    }

    private static void validateRangeSpec(DmlBatchRangeSpec spec, boolean requireHiLiteral) {
        if (spec == null) {
            throw new IllegalArgumentException("DmlBatchRangeSpec is required");
        }
        String keyColumn = spec.getKeyColumn();
        if (keyColumn == null || !IDENTIFIER_WHITELIST.matcher(keyColumn).matches()) {
            throw new IllegalArgumentException("keyColumn must be a bare [\\w$]+ identifier: " + keyColumn);
        }
        if (spec.getLimit() <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + spec.getLimit());
        }
        if (requireHiLiteral && StringUtils.isBlank(spec.getHiLiteral())) {
            throw new IllegalArgumentException("hiLiteral is required for the batch range rewrite");
        }
    }

    /** schema/prefix comparison follows the B6 GHOST_SCHEMA_MISMATCH discipline: exact, case-sensitive after unquoting. */
    private static boolean isSchemaMismatch(String statementSchema, String expectedSchema) {
        if (statementSchema == null) {
            return false;
        }
        if (StringUtils.isBlank(expectedSchema)) {
            // fail-safe: a prefix we cannot validate is rejected (宁可误拒不可误放)
            return true;
        }
        return !statementSchema.equals(expectedSchema);
    }

    /** zero-comment discipline (B6): any comment token anywhere in the stream rejects. */
    private static boolean containsComment(CommonTokenStream tokens) {
        for (Token token : tokens.getTokens()) {
            int type = token.getType();
            if (type == MySqlLexer.COMMENT_INPUT || type == MySqlLexer.LINE_COMMENT
                    || type == MySqlLexer.EXEC_COMMENT_LEFT || type == MySqlLexer.EXEC_COMMENT_RIGHT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Session-state guard (宁可误拒不可误放, R1 silent-corruption class): user/system variables
     * ({@code @v} / {@code @@v}) are per-connection state which the batch executor does NOT carry —
     * every batch runs in a FRESH session (design §5.2/R10), where an unset {@code @v} reads as
     * NULL: {@code SET a = @v} would silently null the column and {@code WHERE x > @cutoff} would
     * silently match nothing (boundary SELECT NULL = immediate clean termination = a silent no-op
     * "SUCCESS"). The direct chain today evaluates the whole ticket group inside ONE sidecar
     * session, so a preceding {@code SET @v = ...} statement feeds later statements — batching
     * breaks that continuity. Statements referencing variables therefore stay on the direct chain.
     * Note {@code SET col := expr} (VAR_ASSIGN on a column target) carries NO session state and
     * stays allowed — only the {@code @}-prefixed variable tokens are rejected.
     */
    private static boolean containsSessionVariable(CommonTokenStream tokens) {
        for (Token token : tokens.getTokens()) {
            int type = token.getType();
            if (type == MySqlLexer.LOCAL_ID || type == MySqlLexer.GLOBAL_ID) {
                return true;
            }
        }
        return false;
    }

    /** normalized schema/table pair; null = exotic form outside the v1 whitelist. */
    private static final class MyTableRef {

        private final String schema;
        private final String table;

        private MyTableRef(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    private static MyTableRef myTableRef(MySqlParser.TableNameContext tableName) {
        if (tableName == null || tableName.fullId() == null) {
            return null; // delphi '.uid' form (<=5.7) — outside the whitelist
        }
        MySqlParser.FullIdContext fullId = tableName.fullId();
        if (fullId.identifierAfterDot != null) {
            if (fullId.uid().isEmpty()) {
                return null;
            }
            return new MyTableRef(unquoteIdentifier(fullId.uid(0).getText()), unquoteIdentifier(fullId.identifierAfterDot.getText()));
        }
        List<MySqlParser.UidContext> uids = fullId.uid();
        if (uids.size() == 1) {
            return new MyTableRef(null, unquoteIdentifier(uids.get(0).getText()));
        }
        if (uids.size() == 2) {
            return new MyTableRef(unquoteIdentifier(uids.get(0).getText()), unquoteIdentifier(uids.get(1).getText()));
        }
        return null;
    }

    /** last dotted segment of a fullColumnName, unquoted; case kept (MySQL columns are compared case-insensitively at the call site). */
    private static String lastColumnName(MySqlParser.FullColumnNameContext columnName) {
        if (columnName == null) {
            return null;
        }
        List<MySqlParser.DottedIdContext> dotted = columnName.dottedId();
        if (!dotted.isEmpty()) {
            MySqlParser.DottedIdContext last = dotted.get(dotted.size() - 1);
            if (last.uid() != null) {
                return unquoteIdentifier(last.uid().getText());
            }
            String text = last.getText();
            return unquoteIdentifier(text.startsWith(".") ? text.substring(1) : text);
        }
        if (columnName.uid() != null) {
            return unquoteIdentifier(columnName.uid().getText());
        }
        return unquoteIdentifier(columnName.getText());
    }

    private static boolean referencesColumn(ParserRuleContext expression, String columnName) {
        for (MySqlParser.FullColumnNameContext reference
                : AntlrAstUtils.descendants(expression, MySqlParser.FullColumnNameContext.class)) {
            String name = lastColumnName(reference);
            if (name != null && name.equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
    }

    private static void collectNondeterministicFunctions(ParserRuleContext expression, Set<String> sink) {
        for (MySqlParser.SimpleFunctionCallContext call
                : AntlrAstUtils.descendants(expression, MySqlParser.SimpleFunctionCallContext.class)) {
            if (call.getChildCount() > 0 && call.getChild(0) instanceof TerminalNode nameNode) {
                String name = nameNode.getText().toUpperCase(Locale.ROOT);
                if (NONDET_SIMPLE_FUNCTIONS.contains(name)) {
                    sink.add(name);
                }
            }
        }
        for (MySqlParser.GenericFunctionContext call
                : AntlrAstUtils.descendants(expression, MySqlParser.GenericFunctionContext.class)) {
            if (call.name == null) {
                continue;
            }
            String raw = call.name.getText();
            int dot = raw.lastIndexOf('.');
            String name = unquoteIdentifier(dot >= 0 ? raw.substring(dot + 1) : raw).toUpperCase(Locale.ROOT);
            if (NONDET_GENERIC_FUNCTIONS.contains(name)) {
                sink.add(name);
            }
        }
    }

    /** strip one layer of MySQL identifier quoting (backtick or ANSI double-quote) with doubling unescape. */
    private static String unquoteIdentifier(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
            String inner = text.substring(1, text.length() - 1);
            return inner.replace("" + first + first, "" + first);
        }
        return text;
    }

    /** dialect quoting for the console-validated key column (doubling is defensive — the whitelist excludes quote chars). */
    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
