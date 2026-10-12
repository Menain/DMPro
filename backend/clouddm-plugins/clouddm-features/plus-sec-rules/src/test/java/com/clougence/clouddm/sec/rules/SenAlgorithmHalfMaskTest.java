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
package com.clougence.clouddm.sec.rules;

import org.junit.Assert;
import org.junit.Test;

import com.clougence.clouddm.sec.rules.execute.sensitive.SecValueProcessServiceProvider;
import com.clougence.clouddm.sec.rules.execute.sensitive.SenAlgorithm;

/**
 * Unit tests for the HALF_MASK desensitise algorithm (task 09-13 R6).
 * <p>
 * The algorithm command is parameterised as {@code HALF_MASK(start,end)} with a
 * 1-based closed interval; any invalid parameter or too-short value must
 * fail-closed to FULL_MASK ({@code "******"}). These tests invoke the static
 * {@link SenAlgorithm} methods directly, no Spring context required.
 */
public class SenAlgorithmHalfMaskTest {

    // Normal masking: 11-digit phone, mask 4th-7th -> 138****5678
    @Test
    public void halfMask_normal_fullCommand() {
        Assert.assertEquals("138****5678",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4,7)", "13812345678"));
    }

    // Runtime (SecValueProcessServiceProvider.doAlgorithm) passes the command
    // with the "algorithm::" prefix already stripped; verify it still works.
    @Test
    public void halfMask_normal_strippedPrefix() {
        Assert.assertEquals("138****5678",
            SenAlgorithm.doHalfMaskAlgorithm("HALF_MASK(4,7)", "13812345678"));
    }

    // Custom range (2,5): mask positions 2-5 -> 1****345678
    @Test
    public void halfMask_customRange() {
        Assert.assertEquals("1****345678",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(2,5)", "13812345678"));
    }

    // Value too short for the end offset -> fail-closed full mask
    @Test
    public void halfMask_valueTooShort() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4,7)", "138"));
    }

    // Inverted range (start>end) -> fail-closed full mask
    @Test
    public void halfMask_invertedRange() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(7,4)", "13812345678"));
    }

    // start < 1 (zero-based) -> fail-closed full mask
    @Test
    public void halfMask_startBelowOne() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(0,3)", "13812345678"));
    }

    // null value -> fail-closed full mask
    @Test
    public void halfMask_nullValue() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4,7)", null));
    }

    // HALF_MASK is weaker than FULL_MASK: chooseAlgorithm must let FULL_MASK win
    @Test
    public void chooseAlgorithm_fullMaskOverridesHalf() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm("algorithm::HALF_MASK(4,7)", SenAlgorithm.SEN_FULL_MASK));
    }

    // HALF_MASK is stronger than ORIGINAL: chooseAlgorithm must let HALF_MASK win
    // (args preserved so doAlgorithm can apply them)
    @Test
    public void chooseAlgorithm_halfOverridesOriginal() {
        Assert.assertEquals("algorithm::HALF_MASK(4,7)",
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_ORIGINAL, "algorithm::HALF_MASK(4,7)"));
    }

    // ORIGINAL vs FULL_MASK comparison unchanged (regression guard for existing behaviour)
    @Test
    public void chooseAlgorithm_originalVsFullMask_unchanged() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_ORIGINAL, SenAlgorithm.SEN_FULL_MASK));
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_FULL_MASK, SenAlgorithm.SEN_ORIGINAL));
    }

    // Non-numeric arguments (NumberFormatException) -> fail-closed full mask
    @Test
    public void halfMask_nonNumericArgs() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(a,b)", "13812345678"));
    }

    // Missing parentheses (malformed command) -> fail-closed full mask
    @Test
    public void halfMask_missingParens() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK", "13812345678"));
    }

    // Single argument (fewer than 2) -> fail-closed full mask
    @Test
    public void halfMask_singleArg() {
        Assert.assertEquals("******",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4)", "13812345678"));
    }

    // Boundary: start==end masks exactly one character (position 4)
    @Test
    public void halfMask_startEqualsEnd() {
        Assert.assertEquals("138*2345678",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4,4)", "13812345678"));
    }

    // Boundary: end==length masks from start to the tail of the string
    @Test
    public void halfMask_endAtLength() {
        Assert.assertEquals("138********",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(4,11)", "13812345678"));
    }

    // Boundary: full-string mask (1,11) -> all asterisks
    @Test
    public void halfMask_fullStringMask() {
        Assert.assertEquals("***********",
            SenAlgorithm.doHalfMaskAlgorithm("algorithm::HALF_MASK(1,11)", "13812345678"));
    }

    // null algorithm must throw IllegalStateException (not NPE) — preserves old indexOf behaviour
    @Test(expected = IllegalStateException.class)
    public void chooseAlgorithm_nullInput_throwsIllegalState() {
        SenAlgorithm.chooseAlgorithm(null, "algorithm::HALF_MASK(4,7)");
    }

    // ============================================================================
    // C6/M2 fail-closed (task 10-11-masking-fail-closed, design §2 / survey §8 A2):
    // an UNKNOWN command string degrades to FULL_MASK strength and is NORMALIZED to
    // SEN_FULL_MASK (a typo over-masks the column instead of failing the whole query
    // with IllegalStateException); null input keeps throwing (the pin above).
    // ============================================================================

    // unknown newone → normalized FULL_MASK wins over ORIGINAL
    @Test
    public void chooseAlgorithm_unknownNewone_normalizesToFullMask() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_ORIGINAL, "algorithm::TYPO_MASK"));
    }

    // unknown current → normalized to FULL_MASK and returned (never the raw typo string)
    @Test
    public void chooseAlgorithm_unknownCurrent_normalizesToFullMask() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm("algorithm::TYPO_MASK", SenAlgorithm.SEN_ORIGINAL));
    }

    // unknown command competes at FULL_MASK strength: beats HALF_MASK, result normalized
    @Test
    public void chooseAlgorithm_unknownBeatsHalfMask_normalized() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm("algorithm::HALF_MASK(4,7)", "algorithm::FULL_MASKK"));
    }

    // unknown vs unknown → both normalized, FULL_MASK returned (no throw)
    @Test
    public void chooseAlgorithm_bothUnknown_normalizesToFullMask() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm("algorithm::FOO", "algorithm::BAR(1,2)"));
    }

    // unknown vs known FULL_MASK → equal strength, the normalized command returned
    @Test
    public void chooseAlgorithm_unknownVsFullMask_returnsFullMask() {
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm("algorithm::TYPO_MASK", SenAlgorithm.SEN_FULL_MASK));
        Assert.assertEquals(SenAlgorithm.SEN_FULL_MASK,
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_FULL_MASK, "algorithm::TYPO_MASK"));
    }

    // known-command regression guard: the normalization change must not touch pool hits
    @Test
    public void chooseAlgorithm_knownCommands_unchanged() {
        Assert.assertEquals("algorithm::HALF_MASK(4,7)",
            SenAlgorithm.chooseAlgorithm("algorithm::HALF_MASK(4,7)", SenAlgorithm.SEN_ORIGINAL));
        Assert.assertEquals(SenAlgorithm.SEN_ORIGINAL,
            SenAlgorithm.chooseAlgorithm(SenAlgorithm.SEN_ORIGINAL, SenAlgorithm.SEN_ORIGINAL));
    }

    // ============================================================================
    // dispatch side (spec §6 "new command: dispatch test + chooseAlgorithm
    // compatibility test"): doAlgorithm's unknown-command else branch is sealed —
    // FULL_MASK + warn, never the original value. Private method → plain reflection
    // (the plugin module has no spring-test/mockito on its test classpath).
    // ============================================================================

    private static String invokeDoAlgorithm(String command, String value) throws Exception {
        SecValueProcessServiceProvider provider = new SecValueProcessServiceProvider(null, null);
        java.lang.reflect.Method m = SecValueProcessServiceProvider.class
            .getDeclaredMethod("doAlgorithm", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, command, value);
    }

    // unknown command dispatch → "******" (fail-closed; the old behaviour returned cv unchanged)
    @Test
    public void doAlgorithm_unknownCommand_fullMaskNeverOriginal() throws Exception {
        Assert.assertEquals("******", invokeDoAlgorithm("algorithm::TYPO_MASK", "13812345678"));
    }

    // unknown command with args → same fail-closed dispatch
    @Test
    public void doAlgorithm_unknownCommandWithArgs_fullMask() throws Exception {
        Assert.assertEquals("******", invokeDoAlgorithm("algorithm::WEIRD_MASK(1,3)", "13812345678"));
    }

    // known dispatch branches regression guard (ORIGINAL / HALF_MASK / FULL_MASK unchanged)
    @Test
    public void doAlgorithm_knownCommands_unchanged() throws Exception {
        Assert.assertEquals("13812345678", invokeDoAlgorithm(SenAlgorithm.SEN_ORIGINAL, "13812345678"));
        Assert.assertEquals("138****5678", invokeDoAlgorithm("algorithm::HALF_MASK(4,7)", "13812345678"));
        Assert.assertEquals("******", invokeDoAlgorithm(SenAlgorithm.SEN_FULL_MASK, "13812345678"));
    }
}
