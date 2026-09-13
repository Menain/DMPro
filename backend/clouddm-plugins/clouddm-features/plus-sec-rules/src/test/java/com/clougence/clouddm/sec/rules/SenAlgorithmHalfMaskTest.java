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
}
