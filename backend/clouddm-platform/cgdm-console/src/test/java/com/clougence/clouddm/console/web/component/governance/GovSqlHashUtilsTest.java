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
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.*;

import org.junit.Test;

public class GovSqlHashUtilsTest {

    @Test
    public void determinism_sameTextSameHash() {
        String sql = "CREATE TABLE foo (id INT)";
        String h1 = GovSqlHashUtils.hash(sql);
        String h2 = GovSqlHashUtils.hash(sql);
        assertEquals(h1, h2);
    }

    @Test
    public void whitespaceInvariance_extraSpacesCollapsed() {
        String h1 = GovSqlHashUtils.hash("CREATE  TABLE  foo  (id  INT)");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo (id INT)");
        assertEquals(h1, h2);
    }

    @Test
    public void whitespaceInvariance_leadingTrailingTrimmed() {
        String h1 = GovSqlHashUtils.hash("  CREATE TABLE foo (id INT)  ");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo (id INT)");
        assertEquals(h1, h2);
    }

    @Test
    public void whitespaceInvariance_emptyLinesFiltered() {
        String h1 = GovSqlHashUtils.hash("\n\nCREATE TABLE foo (id INT)\n\n");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo (id INT)");
        assertEquals(h1, h2);
    }

    @Test
    public void whitespaceInvariance_differentLineBreaks() {
        String h1 = GovSqlHashUtils.hash("CREATE TABLE foo\r\n(id INT)");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo\n(id INT)");
        String h3 = GovSqlHashUtils.hash("CREATE TABLE foo\r(id INT)");
        assertEquals(h1, h2);
        assertEquals(h2, h3);
    }

    @Test
    public void whitespaceInvariance_tabsCollapsed() {
        String h1 = GovSqlHashUtils.hash("CREATE\tTABLE\tfoo");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo");
        assertEquals(h1, h2);
    }

    @Test
    public void multiStatement_differentJoining() {
        String h1 = GovSqlHashUtils.hash("CREATE TABLE foo;\nINSERT INTO bar VALUES (1)");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo;\nINSERT INTO bar VALUES (1)\n");
        assertEquals(h1, h2);
    }

    @Test
    public void differentText_differentHash() {
        String h1 = GovSqlHashUtils.hash("CREATE TABLE foo");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE bar");
        assertNotEquals(h1, h2);
    }

    @Test
    public void hashLength_64HexChars() {
        String hash = GovSqlHashUtils.hash("SELECT 1");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void emptyInput_emptyNormalized() {
        assertEquals("", GovSqlHashUtils.normalize(""));
        assertEquals("", GovSqlHashUtils.normalize(null));
        assertEquals("", GovSqlHashUtils.normalize("   "));
        assertEquals("", GovSqlHashUtils.normalize("\n\n\n"));
    }

    @Test
    public void emptyInput_sameHash() {
        String h1 = GovSqlHashUtils.hash("");
        String h2 = GovSqlHashUtils.hash(null);
        String h3 = GovSqlHashUtils.hash("  \n  \n  ");
        assertEquals(h1, h2);
        assertEquals(h2, h3);
    }

    @Test
    public void commentsPreserved_notFiltered() {
        String h1 = GovSqlHashUtils.hash("-- this is a comment\nCREATE TABLE foo");
        String h2 = GovSqlHashUtils.hash("CREATE TABLE foo");
        assertNotEquals(h1, h2);
    }
}
