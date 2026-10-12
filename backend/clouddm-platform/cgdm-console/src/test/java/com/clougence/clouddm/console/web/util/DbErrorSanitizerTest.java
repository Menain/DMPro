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
package com.clougence.clouddm.console.web.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * C6/M7 (task 10-11-masking-fail-closed, design §7): the error-message value-segment
 * sanitizer — pure function, no Spring context. Contract under test: KNOWN data-value
 * patterns (PG {@code Key (col)=(val)}, MySQL {@code Duplicate entry 'val' for key}) lose the
 * VALUE and keep the column/key names + full error semantics; UNKNOWN shapes pass through
 * byte-identical (宁可漏删不误伤 — the conservative ruling); never throws.
 */
public class DbErrorSanitizerTest {

    // -- null-safety / pass-through --

    @Test
    public void sanitize_null_returnsNull() {
        assertNull(DbErrorSanitizer.sanitize(null));
    }

    @Test
    public void sanitize_empty_returnsEmpty() {
        assertEquals("", DbErrorSanitizer.sanitize(""));
    }

    @Test
    public void sanitize_unknownPattern_unchanged() {
        String error = "ERROR 1205 (HY000): Lock wait timeout exceeded; try restarting transaction";
        assertEquals(error, DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_deadlock_unchanged() {
        String error = "ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction";
        assertEquals(error, DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_syntaxError_unchanged() {
        String error = "ERROR: syntax error at or near \"SELEC\" Position: 1";
        assertEquals(error, DbErrorSanitizer.sanitize(error));
    }

    // -- PG duplicate-key family --

    @Test
    public void sanitize_pgKeyTuple_valueDroppedColumnsKept() {
        String error = "ERROR: duplicate key value violates unique constraint \"uk_email\" "
                       + "Detail: Key (email)=(foo@bar.com) already exists.";
        String sanitized = DbErrorSanitizer.sanitize(error);
        assertEquals("ERROR: duplicate key value violates unique constraint \"uk_email\" "
                     + "Detail: Key (email)=(***) already exists.", sanitized);
        // the value must not survive anywhere
        assertFalse(sanitized.contains("foo@bar.com"));
    }

    @Test
    public void sanitize_pgMultiColumnKey_valueDroppedColumnsKept() {
        String error = "Detail: Key (tenant_id, email)=(42, a@b.c) already exists.";
        assertEquals("Detail: Key (tenant_id, email)=(***) already exists.", DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_pgNumericKey_valueDropped() {
        String error = "Detail: Key (id)=(12345678901234567890) already exists.";
        assertEquals("Detail: Key (id)=(***) already exists.", DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_pgNestedParensValue_passThrough() {
        // conservative direction: a value containing parens breaks the match → unchanged
        String error = "Detail: Key (expr)=(fn(1)) already exists.";
        assertEquals(error, DbErrorSanitizer.sanitize(error));
    }

    // -- MySQL duplicate-entry family --

    @Test
    public void sanitize_mysqlDuplicateEntry_valueDroppedKeyKept() {
        String error = "Duplicate entry 'foo@bar.com' for key 'uk_email'";
        String sanitized = DbErrorSanitizer.sanitize(error);
        assertEquals("Duplicate entry '***' for key 'uk_email'", sanitized);
        assertFalse(sanitized.contains("foo@bar.com"));
    }

    @Test
    public void sanitize_mysqlQualifiedKey_valueDropped() {
        String error = "Duplicate entry 'abc' for key 'users.uk_name'";
        assertEquals("Duplicate entry '***' for key 'users.uk_name'", DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_mysqlFullErrorText_wrapped() {
        String error = "ERROR 1062 (23000): Duplicate entry 'x-y' for key 'PRIMARY'";
        assertEquals("ERROR 1062 (23000): Duplicate entry '***' for key 'PRIMARY'", DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_mysqlQuotedValue_passThrough() {
        // conservative direction: an embedded quote breaks the match → unchanged (宁可漏删)
        String error = "Duplicate entry 'it''s' for key 'uk'";
        assertEquals(error, DbErrorSanitizer.sanitize(error));
    }

    // -- composite / edge shapes --

    @Test
    public void sanitize_bothFamiliesInOneText_allSanitized() {
        String error = "Duplicate entry 'a' for key 'k1'; Detail: Key (c)=(b) already exists.";
        String sanitized = DbErrorSanitizer.sanitize(error);
        assertEquals("Duplicate entry '***' for key 'k1'; Detail: Key (c)=(***) already exists.", sanitized);
    }

    @Test
    public void sanitize_multipleKeyTuples_allSanitized() {
        String error = "Key (a)=(1) conflicts. Key (b)=(2) conflicts.";
        assertEquals("Key (a)=(***) conflicts. Key (b)=(***) conflicts.", DbErrorSanitizer.sanitize(error));
    }

    @Test
    public void sanitize_neverThrowsOnExoticInput() {
        // exotic bytes / unbalanced shapes must degrade to pass-through, never throw
        assertEquals("Key (=", DbErrorSanitizer.sanitize("Key (="));
        assertEquals(")))(((", DbErrorSanitizer.sanitize(")))((("));
        assertEquals("Duplicate entry '", DbErrorSanitizer.sanitize("Duplicate entry '"));
    }
}
