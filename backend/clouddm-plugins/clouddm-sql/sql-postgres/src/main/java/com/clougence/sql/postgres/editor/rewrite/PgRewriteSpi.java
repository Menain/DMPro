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
package com.clougence.sql.postgres.editor.rewrite;

import java.io.StringReader;
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
import com.clougence.sql.postgres.parser.PgDslProvider;
import com.clougence.sql.postgres.parser.PgSplitVisitor;
import com.clougence.sql.postgres.parser.PostgresVersion;
import com.clougence.sql.postgres.parser.antlr.PgSqlLexer;
import com.clougence.sql.postgres.parser.antlr.PgSqlParser;
import com.clougence.utils.StringUtils;

public class PgRewriteSpi implements RewriteSpi {

    private final PgDslProvider provider;

    public PgRewriteSpi(PostgresVersion version){
        this.provider = new PgDslProvider(version);
    }

    public PostgresVersion version() {
        return provider.version();
    }

    @Override
    public String rewriteLimit(String queryId, String queryStr, RewriteContext context) {
        List<AstSplitScript> scripts = DslHelper.splitDsl(provider, new StringReader(queryStr));
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
        PgSqlParser.SelectstmtContext selectStmt = ((PgSqlParser.StmtContext) astTree).selectstmt();
        PgSqlParser.Select_clauseContext clauseContext = selectStmt.select_no_parens().select_clause();

        List<PgSqlParser.Simple_select_pramaryContext> contexts = clauseContext.simple_select_pramary();
        if (contexts.size() > 1) {
            return false;
        }

        PgSqlParser.Simple_select_pramaryContext simple = contexts.get(0);
        if (simple.from_clause() == null) {
            return false;
        }

        PgSqlParser.Select_limitContext limitContext = simple.select_limit();
        if (limitContext != null) {
            PgSqlParser.Select_limit_valueContext limit = limitContext.limit_clause().select_limit_value();

            long sqlLimit = Long.parseLong(limit.getText());
            long newLimit = Math.min(maxLimit, sqlLimit);
            if (sqlLimit != newLimit) {
                rewriter.replace(limit.getStart(), limit.getStop(), newLimit);
                return true;
            } else {
                return false;
            }
        } else {
            rewriter.insertAfter(simple.getStop(), " LIMIT " + maxLimit);
            return true;
        }
    }

    @Override
    public String rewriteToExplain(String queryId, String queryStr, RewriteContext context) {
        List<AstSplitScript> scripts = DslHelper.splitDsl(provider, new StringReader(queryStr));
        if (scripts.size() != 1) {
            return null;
        }

        ParseTree astTree = scripts.get(0).getAstTree();
        if (containsExplain(astTree)) {
            return queryStr;
        }
        SplitQueryType type = new PgSplitVisitor(provider.version()).visit(astTree);
        if (type == null || !type.isAllowPlan()) {
            return null;
        }

        return "EXPLAIN " + queryStr;
    }

    private static boolean containsExplain(ParseTree tree) {
        if (tree instanceof PgSqlParser.ExplainstmtContext) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (containsExplain(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================
    // B7 lock-free DML — batch shape gate + keyset rewrite (design 09-17-lockfree-dml §3).
    //
    // Same discipline as the MySQL twin (MyRewriteSpi): analyze NEVER throws; the original
    // statement text is never string-spliced (TokenStreamRewriter node surgery only, the
    // rewriteLimit method above is the in-file precedent); lo/hi literals arrive PRE-RENDERED
    // from the console (single escaping point, R18) and are injected verbatim; the key column
    // identifier is quoted here with PG double quotes (doubling) after a defensive [\w$]+
    // whitelist re-check.
    //
    // PG-specific shape facts (PgSqlParser.g4 desk-check 2026-10-06):
    //   updatestmt  : with_clause_? UPDATE relation_expr_opt_alias SET set_clause_list
    //                 from_clause? where_or_current_clause? returning_clause?   (:3097)
    //   deletestmt  : with_clause_? DELETE_P FROM relation_expr_opt_alias
    //                 using_clause? where_or_current_clause? returning_clause?  (:3061)
    // PG DML has NO PARTITION clause and NO native ORDER BY/LIMIT (those reject codes are
    // MySQL-only); the multi-table forms are UPDATE...FROM and DELETE...USING; WHERE CURRENT OF
    // carries no injectable predicate (rejected as NO_WHERE); RETURNING stays outside the v1
    // whitelist (UNSUPPORTED_SHAPE — the boundary SELECT would have to strip it).
    //
    // Identifier case folding: unquoted identifiers fold to lower case (catalog semantics),
    // quoted "Identifiers" keep their case — normalizePgIdentifier is the single authority.
    // =====================================================================================

    /** non-deterministic / stateful functions reached via func_application (folded lower case). */
    private static final Set<String>  NONDET_FUNCTIONS = Set.of(
            "now", "statement_timestamp", "transaction_timestamp", "clock_timestamp", "timeofday",
            "random", "sysdate", "gen_random_uuid",
            "uuid_generate_v1", "uuid_generate_v1mc", "uuid_generate_v2", "uuid_generate_v3",
            "uuid_generate_v4", "uuid_generate_v5",
            "nextval", "currval", "lastval", "setval");

    /** paren-less time constants of func_expr_common_subexpr (CURRENT_USER & co are session-stable, not flagged). */
    private static final Set<Integer> NONDET_COMMON_SUBEXPR_TOKENS = Set.of(
            PgSqlParser.CURRENT_DATE, PgSqlParser.CURRENT_TIME, PgSqlParser.CURRENT_TIMESTAMP,
            PgSqlParser.LOCALTIME, PgSqlParser.LOCALTIMESTAMP);

    /** B6 validateIdentifier heritage: bare ASCII identifier only (probe result, never user text). */
    private static final Pattern      IDENTIFIER_WHITELIST = Pattern.compile("[\\w$]+");

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
        List<AstSplitScript> scripts = DslHelper.splitDsl(provider, new StringReader(sql));
        if (scripts.isEmpty()) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
        }
        if (scripts.size() != 1) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_STATEMENT);
        }
        AstSplitScript script = scripts.get(0);
        if (containsComment((CommonTokenStream) script.getParser().getTokenStream())) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_COMMENT);
        }
        if (!(script.getAstTree() instanceof PgSqlParser.StmtContext statement)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
        }
        String expectedSchema = context == null ? null : context.getSchemaName();
        if (statement.updatestmt() != null) {
            return analyzeUpdate(statement.updatestmt(), expectedSchema);
        }
        if (statement.deletestmt() != null) {
            return analyzeDelete(statement.deletestmt(), expectedSchema);
        }
        return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT);
    }

    private DmlBatchShapeMO analyzeUpdate(PgSqlParser.UpdatestmtContext update, String expectedSchema) {
        if (update.with_clause_() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_CTE);
        }
        if (update.from_clause() != null) {
            // UPDATE ... FROM other — multi-table semantics
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_TABLE);
        }
        if (update.returning_clause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        PgTableRef tableRef = pgTableRef(update.relation_expr_opt_alias());
        if (tableRef == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        if (isSchemaMismatch(tableRef.schema, expectedSchema)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH);
        }
        if (update.set_clause_list() == null || update.set_clause_list().set_clause().isEmpty()) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        for (PgSqlParser.Set_clauseContext clause : update.set_clause_list().set_clause()) {
            if (clause.set_target_list() != null || clause.set_target() == null || clause.a_expr() == null) {
                // row assignment SET (a,b) = ... — outside the v1 whitelist
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
            }
            PgSqlParser.Set_targetContext target = clause.set_target();
            if (target.colid() == null
                    || (target.opt_indirection() != null && target.opt_indirection().getChildCount() > 0)) {
                // SET (t.col) / SET arr[1] — exotic assignment targets
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
            }
            if (!AntlrAstUtils.descendants(clause.a_expr(), PgSqlParser.Select_with_parensContext.class).isEmpty()) {
                return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SET_SUBQUERY);
            }
        }
        PgSqlParser.A_exprContext whereExpr = injectableWhereExpr(update.where_or_current_clause());
        if (whereExpr == null) {
            // covers both no-WHERE and WHERE CURRENT OF (no injectable predicate)
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_NO_WHERE);
        }

        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.setSupported(true);
        mo.setStatementType(SplitQueryType.UPDATE);
        mo.setSchemaName(tableRef.schema);
        mo.setTableName(tableRef.table);
        mo.setAlias(aliasOf(update.relation_expr_opt_alias()));
        mo.setHasWhere(true);

        boolean selfReferencing = false;
        Set<String> nondeterministic = new LinkedHashSet<>();
        for (PgSqlParser.Set_clauseContext clause : update.set_clause_list().set_clause()) {
            String target = normalizePgIdentifier(clause.set_target().colid().getText());
            mo.getSetColumns().add(target);
            if (referencesColumn(clause.a_expr(), target)) {
                selfReferencing = true;
            }
            collectNondeterministicFunctions(clause.a_expr(), nondeterministic);
        }
        mo.setSelfReferencingSet(selfReferencing);
        mo.getNondeterministicFunctions().addAll(nondeterministic);
        return mo;
    }

    private DmlBatchShapeMO analyzeDelete(PgSqlParser.DeletestmtContext delete, String expectedSchema) {
        if (delete.with_clause_() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_CTE);
        }
        if (delete.using_clause() != null) {
            // DELETE ... USING other — multi-table semantics
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_TABLE);
        }
        if (delete.returning_clause() != null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        PgTableRef tableRef = pgTableRef(delete.relation_expr_opt_alias());
        if (tableRef == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE);
        }
        if (isSchemaMismatch(tableRef.schema, expectedSchema)) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH);
        }
        PgSqlParser.A_exprContext whereExpr = injectableWhereExpr(delete.where_or_current_clause());
        if (whereExpr == null) {
            return DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_NO_WHERE);
        }

        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.setSupported(true);
        mo.setStatementType(SplitQueryType.DELETE);
        mo.setSchemaName(tableRef.schema);
        mo.setTableName(tableRef.table);
        mo.setAlias(aliasOf(delete.relation_expr_opt_alias()));
        mo.setHasWhere(true);
        // DELETE is naturally idempotent (re-deleting an already-deleted row is a no-op)
        return mo;
    }

    @Override
    public String rewriteToBatchBoundarySelect(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        validateRangeSpec(spec, false);
        try {
            PgBatchTarget target = locateBatchTarget(sql);
            if (target == null) {
                return null;
            }
            String quotedKey = quoteIdentifier(spec.getKeyColumn());
            String keyRef = target.aliasRawText != null ? target.aliasRawText + "." + quotedKey : quotedKey;
            String tableText = target.tokens.getText(Interval.of(
                    target.tableRefStart.getTokenIndex(), target.tableRefStop.getTokenIndex()));
            String innerPrefix = "SELECT MAX(" + quotedKey + ") FROM (SELECT " + keyRef + " FROM " + tableText + " ";
            String orderByLimit = "ORDER BY " + keyRef + " LIMIT " + spec.getLimit();

            if (target.whereExpr == null) {
                // defensive bare form (the analyze gate excludes it): the whole statement up to the
                // bare anchor (SET list end / table reference end) becomes the inner SELECT
                String bareWhere = StringUtils.isBlank(spec.getLoLiteral()) ? "" : "WHERE " + keyRef + " > " + spec.getLoLiteral() + " ";
                target.rewriter.replace(target.statement.getStart(), target.bareAnchorStop, innerPrefix + bareWhere + orderByLimit + ") bnd");
                return target.rewriter.getText();
            }
            Token beforeWhere = target.tokens.get(target.where.getStart().getTokenIndex() - 1);
            target.rewriter.replace(target.statement.getStart(), beforeWhere, innerPrefix);
            target.rewriter.insertBefore(target.whereExpr.getStart(), "(");
            String lowerBound = StringUtils.isBlank(spec.getLoLiteral()) ? "" : " AND " + keyRef + " > " + spec.getLoLiteral();
            target.rewriter.insertAfter(target.whereExpr.getStop(), ")" + lowerBound + " " + orderByLimit + ") bnd");
            return target.rewriter.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String rewriteToBatchRange(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        validateRangeSpec(spec, true);
        try {
            PgBatchTarget target = locateBatchTarget(sql);
            if (target == null) {
                return null;
            }
            String quotedKey = quoteIdentifier(spec.getKeyColumn());
            String keyRef = target.aliasRawText != null ? target.aliasRawText + "." + quotedKey : quotedKey;
            String lowerBound = StringUtils.isBlank(spec.getLoLiteral()) ? "" : keyRef + " > " + spec.getLoLiteral() + " AND ";
            String range = lowerBound + keyRef + " <= " + spec.getHiLiteral();

            if (target.whereExpr == null) {
                // defensive bare injection (the analyze gate excludes no-WHERE statements)
                target.rewriter.insertAfter(target.bareAnchorStop, " WHERE " + range);
                return target.rewriter.getText();
            }
            target.rewriter.insertBefore(target.whereExpr.getStart(), "((");
            target.rewriter.insertAfter(target.whereExpr.getStop(), ") AND " + range + ")");
            return target.rewriter.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** parsed + minimally shape-gated rewrite target (defensive gate: correctness-critical items only). */
    private static final class PgBatchTarget {

        private CommonTokenStream   tokens;
        private TokenStreamRewriter rewriter;
        private ParserRuleContext   statement;
        private PgSqlParser.Where_or_current_clauseContext where;
        private PgSqlParser.A_exprContext whereExpr;
        private Token               tableRefStart;
        private Token               tableRefStop;
        private Token               bareAnchorStop;
        private String              aliasRawText;
    }

    /**
     * Locate + defensive gate for the rewrite methods. Returns null when the statement is not a
     * transformable single-table UPDATE/DELETE (unparsable, multi-statement, CTE, UPDATE...FROM,
     * DELETE...USING, RETURNING, WHERE CURRENT OF, ONLY-star relation forms). Comments / schema
     * prefix are NOT re-checked here — the analyze gate owns those decisions and the rewrite
     * stays text-faithful either way.
     */
    private PgBatchTarget locateBatchTarget(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        List<AstSplitScript> scripts = DslHelper.splitDsl(provider, new StringReader(sql));
        if (scripts.size() != 1) {
            return null;
        }
        AstSplitScript script = scripts.get(0);
        if (!(script.getAstTree() instanceof PgSqlParser.StmtContext statement)) {
            return null;
        }
        CommonTokenStream tokens = (CommonTokenStream) script.getParser().getTokenStream();

        PgBatchTarget target = new PgBatchTarget();
        target.tokens = tokens;
        target.rewriter = new TokenStreamRewriter(tokens);
        if (statement.updatestmt() != null) {
            PgSqlParser.UpdatestmtContext update = statement.updatestmt();
            if (update.with_clause_() != null || update.from_clause() != null || update.returning_clause() != null
                    || update.relation_expr_opt_alias() == null || update.set_clause_list() == null) {
                return null;
            }
            if (!plainRelation(update.relation_expr_opt_alias())) {
                return null;
            }
            target.statement = update;
            target.where = update.where_or_current_clause();
            target.whereExpr = injectableWhereExpr(target.where);
            if (target.where != null && target.whereExpr == null) {
                return null; // WHERE CURRENT OF — a bare injection would produce a second WHERE
            }
            target.tableRefStart = update.relation_expr_opt_alias().getStart();
            target.tableRefStop = update.relation_expr_opt_alias().getStop();
            target.bareAnchorStop = update.set_clause_list().getStop();
            target.aliasRawText = rawAliasText(update.relation_expr_opt_alias());
            return target;
        }
        if (statement.deletestmt() != null) {
            PgSqlParser.DeletestmtContext delete = statement.deletestmt();
            if (delete.with_clause_() != null || delete.using_clause() != null || delete.returning_clause() != null
                    || delete.relation_expr_opt_alias() == null) {
                return null;
            }
            if (!plainRelation(delete.relation_expr_opt_alias())) {
                return null;
            }
            target.statement = delete;
            target.where = delete.where_or_current_clause();
            target.whereExpr = injectableWhereExpr(target.where);
            if (target.where != null && target.whereExpr == null) {
                return null;
            }
            target.tableRefStart = delete.relation_expr_opt_alias().getStart();
            target.tableRefStop = delete.relation_expr_opt_alias().getStop();
            target.bareAnchorStop = delete.relation_expr_opt_alias().getStop();
            target.aliasRawText = rawAliasText(delete.relation_expr_opt_alias());
            return target;
        }
        return null;
    }

    /** the relation reference must be a plain qualified_name with an optional alias (no ONLY, no t.* star form). */
    private static boolean plainRelation(PgSqlParser.Relation_expr_opt_aliasContext relation) {
        if (relation == null || relation.relation_expr() == null || relation.relation_expr().qualified_name() == null) {
            return false;
        }
        if (relation.relation_expr().ONLY() != null || relation.relation_expr().STAR() != null) {
            // ONLY changes the inheritance/partition row set — outside the v1 whitelist
            return false;
        }
        PgSqlParser.Qualified_nameContext qualifiedName = relation.relation_expr().qualified_name();
        if (qualifiedName.indirection() != null) {
            for (PgSqlParser.Indirection_elContext element : qualifiedName.indirection().indirection_el()) {
                if (element.star_context() != null || element.OPEN_BRACKET() != null) {
                    return false;
                }
            }
        }
        return true;
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

    /** WHERE with an injectable boolean predicate, or null (absent clause / WHERE CURRENT OF cursor). */
    private static PgSqlParser.A_exprContext injectableWhereExpr(PgSqlParser.Where_or_current_clauseContext where) {
        return where == null ? null : where.a_expr();
    }

    /** schema/prefix comparison follows the B6 GHOST_SCHEMA_MISMATCH discipline: exact after PG normalization. */
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
            if (type == PgSqlLexer.LineComment || type == PgSqlLexer.BlockComment
                    || type == PgSqlLexer.UnterminatedBlockComment) {
                return true;
            }
        }
        return false;
    }

    /** normalized schema/table pair; null = exotic relation form outside the v1 whitelist. */
    private static final class PgTableRef {

        private final String schema;
        private final String table;

        private PgTableRef(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    private static PgTableRef pgTableRef(PgSqlParser.Relation_expr_opt_aliasContext relation) {
        if (!plainRelation(relation)) {
            return null;
        }
        PgSqlParser.Qualified_nameContext qualifiedName = relation.relation_expr().qualified_name();
        if (qualifiedName.colid() == null) {
            return null;
        }
        String base = normalizePgIdentifier(qualifiedName.colid().getText());
        PgSqlParser.IndirectionContext indirection = qualifiedName.indirection();
        if (indirection == null) {
            return new PgTableRef(null, base);
        }
        List<PgSqlParser.Indirection_elContext> elements = indirection.indirection_el();
        if (elements.size() != 1 || elements.get(0).attr_name() == null) {
            // 3-part names / array subscripts — PG DML has no database qualification
            return null;
        }
        return new PgTableRef(base, normalizePgIdentifier(elements.get(0).attr_name().getText()));
    }

    /** normalized alias name, or null when the statement declares none. */
    private static String aliasOf(PgSqlParser.Relation_expr_opt_aliasContext relation) {
        String raw = rawAliasText(relation);
        return raw == null ? null : normalizePgIdentifier(raw);
    }

    private static String rawAliasText(PgSqlParser.Relation_expr_opt_aliasContext relation) {
        return relation != null && relation.colid() != null ? relation.colid().getText() : null;
    }

    private static boolean referencesColumn(PgSqlParser.A_exprContext expression, String normalizedTarget) {
        for (PgSqlParser.ColumnrefContext reference : AntlrAstUtils.descendants(expression, PgSqlParser.ColumnrefContext.class)) {
            String name = lastColumnName(reference);
            if (name != null && name.equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    /** normalized last segment of a columnref; an array subscript falls back to the base column (conservative). */
    private static String lastColumnName(PgSqlParser.ColumnrefContext columnref) {
        if (columnref.colid() == null) {
            return null;
        }
        String base = normalizePgIdentifier(columnref.colid().getText());
        if (columnref.indirection() == null) {
            return base;
        }
        List<PgSqlParser.Indirection_elContext> elements = columnref.indirection().indirection_el();
        if (elements.isEmpty()) {
            return base;
        }
        PgSqlParser.Indirection_elContext last = elements.get(elements.size() - 1);
        if (last.attr_name() != null) {
            return normalizePgIdentifier(last.attr_name().getText());
        }
        if (last.OPEN_BRACKET() != null) {
            return base; // arr[1] still references arr
        }
        return null; // star form — not a column reference
    }

    private static void collectNondeterministicFunctions(PgSqlParser.A_exprContext expression, Set<String> sink) {
        for (PgSqlParser.Func_applicationContext call
                : AntlrAstUtils.descendants(expression, PgSqlParser.Func_applicationContext.class)) {
            if (call.func_name() == null) {
                continue;
            }
            String raw = call.func_name().getText();
            int dot = raw.lastIndexOf('.');
            String name = normalizePgIdentifier(dot >= 0 ? raw.substring(dot + 1) : raw);
            if (NONDET_FUNCTIONS.contains(name)) {
                sink.add(name);
            }
        }
        for (PgSqlParser.Func_expr_common_subexprContext call
                : AntlrAstUtils.descendants(expression, PgSqlParser.Func_expr_common_subexprContext.class)) {
            if (call.getChildCount() > 0 && call.getChild(0) instanceof TerminalNode nameNode) {
                if (NONDET_COMMON_SUBEXPR_TOKENS.contains(nameNode.getSymbol().getType())) {
                    sink.add(nameNode.getText().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /**
     * PG identifier normalization (single authority): quoted forms keep their case (quotes
     * stripped, doubling unescaped), U&"..." unicode-quoted forms are treated like quoted (the
     * \xxxx escapes are NOT decoded — exotic, comparison stays conservative), bare identifiers
     * fold to lower case per PG catalog semantics.
     */
    private static String normalizePgIdentifier(String text) {
        if (text == null) {
            return null;
        }
        String value = text;
        if (value.length() > 3 && (value.startsWith("U&\"") || value.startsWith("u&\"")) && value.endsWith("\"")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** dialect quoting for the console-validated key column (doubling is defensive — the whitelist excludes quote chars). */
    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
