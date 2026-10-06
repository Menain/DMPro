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
package com.clougence.clouddm.sdk.sql.editor.rewrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * B7 lock-free DML — pins the SDK contract surface of the RewriteSpi batch extension
 * (design 09-17-lockfree-dml §3.1): the three new methods are DEFAULT methods returning null
 * (= "dialect does not support batch rewriting" → the caller treats it exactly like
 * supported=false and keeps the statement on the direct chain), so all 17 pre-existing plugin
 * implementations plus the in-SDK PrefixRewriteSpi compile and behave UNCHANGED.
 */
public class RewriteSpiBatchDefaultsTest {

    /** an existing implementation exercising the defaults without any code change. */
    private final RewriteSpi legacySpi = new PrefixRewriteSpi("/* p */ ");

    @Test
    public void defaultsReturnNullForUnsupportedDialects() {
        RewriteContext context = new RewriteContext();
        assertNull(legacySpi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2", context));
        assertNull(legacySpi.rewriteToBatchBoundarySelect("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", "10", 100), context));
        assertNull(legacySpi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", "10", 100), context));
        // null arguments must not blow up on the defaults either
        assertNull(legacySpi.analyzeDmlForBatch(null, null, null));
        assertNull(legacySpi.rewriteToBatchBoundarySelect(null, null, null, null));
        assertNull(legacySpi.rewriteToBatchRange(null, null, null, null));
    }

    @Test
    public void existingPrefixBehaviorIsUntouched() {
        RewriteContext context = new RewriteContext();
        assertEquals("/* p */ SELECT 1", legacySpi.rewriteLimit("q1", "SELECT 1", context));
        assertEquals("/* p */ SELECT 1", legacySpi.rewriteToExplain("q1", "SELECT 1", context));
    }

    @Test
    public void rejectFactoryCarriesCodeAndStaysUnsupported() {
        DmlBatchShapeMO mo = DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_NO_WHERE);
        assertFalse(mo.isSupported());
        assertEquals("DML_BATCH_NO_WHERE", mo.getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE, mo.getRejectCode());
        assertTrue(mo.getNondeterministicFunctions().isEmpty());
        assertTrue(mo.getSetColumns().isEmpty());
    }

    @Test
    public void rangeSpecFactoryCarriesAllFields() {
        DmlBatchRangeSpec spec = DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_STRING, "'a'", "'b'", 5000L);
        assertEquals("id", spec.getKeyColumn());
        assertEquals("STRING", spec.getKeyType());
        assertEquals("'a'", spec.getLoLiteral());
        assertEquals("'b'", spec.getHiLiteral());
        assertEquals(5000L, spec.getLimit());
        // key type constants are wire values consumed by the console literal renderer
        assertEquals("INT", DmlBatchRangeSpec.KEY_TYPE_INT);
        assertEquals("DECIMAL0", DmlBatchRangeSpec.KEY_TYPE_DECIMAL0);
        assertEquals("DATETIME", DmlBatchRangeSpec.KEY_TYPE_DATETIME);
    }

    @Test
    public void contextSchemaNameDefaultsToNull() {
        RewriteContext context = new RewriteContext();
        assertNull(context.getSchemaName());
        context.setSchemaName("mydb");
        assertEquals("mydb", context.getSchemaName());
        // the pre-existing fields keep their defaults
        assertEquals(0L, context.getFetchLimit());
        assertEquals(com.clougence.clouddm.sdk.sql.SqlParserParameters.empty().values(), context.getParameters().values());
    }
}
