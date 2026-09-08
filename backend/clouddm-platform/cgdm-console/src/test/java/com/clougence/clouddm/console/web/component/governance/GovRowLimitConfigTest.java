/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.*;

import org.junit.Test;

public class GovRowLimitConfigTest {

    @Test
    public void parse_fullConfig() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertEquals(Long.valueOf(1000), config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
        assertTrue(config.isConfigured());
    }

    @Test
    public void parse_fullConfig_swappedOrder() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("block:100000,warn:1000");
        assertEquals(Long.valueOf(1000), config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
    }

    @Test
    public void parse_onlyWarn() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000");
        assertEquals(Long.valueOf(1000), config.getWarn());
        assertNull(config.getBlock());
        assertTrue(config.isConfigured());
    }

    @Test
    public void parse_onlyBlock() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("block:100000");
        assertNull(config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
        assertTrue(config.isConfigured());
    }

    @Test
    public void parse_null() {
        GovRowLimitConfig config = GovRowLimitConfig.parse(null);
        assertNull(config.getWarn());
        assertNull(config.getBlock());
        assertFalse(config.isConfigured());
    }

    @Test
    public void parse_empty() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("");
        assertNull(config.getWarn());
        assertNull(config.getBlock());
        assertFalse(config.isConfigured());
    }

    @Test
    public void parse_blank() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("   ");
        assertFalse(config.isConfigured());
    }

    @Test
    public void parse_invalidValue_ignored() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:abc,block:100000");
        assertNull(config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
    }

    @Test
    public void parse_unknownKey_ignored() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("foo:1000,block:100000");
        assertNull(config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
    }

    @Test
    public void parse_malformedSegment_ignored() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,no_colon,block:100000");
        assertEquals(Long.valueOf(1000), config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
    }

    @Test
    public void parse_negativeValue_ignored() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:-1,block:100000");
        assertNull(config.getWarn());
        assertEquals(Long.valueOf(100000), config.getBlock());
    }

    // ---- threshold semantics ----

    @Test
    public void shouldBlock_aboveBlock() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertTrue(config.shouldBlock(100001));
    }

    @Test
    public void shouldBlock_atBlock_notBlocked() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertFalse(config.shouldBlock(100000));
    }

    @Test
    public void shouldWarn_betweenWarnAndBlock() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertTrue(config.shouldWarn(50000));
    }

    @Test
    public void shouldWarn_atWarn_notWarned() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertFalse(config.shouldWarn(1000));
    }

    @Test
    public void shouldWarn_atBlock_isWarned() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertTrue(config.shouldWarn(100000));
    }

    @Test
    public void shouldWarn_belowWarn_notWarned() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertFalse(config.shouldWarn(500));
    }

    @Test
    public void shouldWarn_missingBlock_neverWarns() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000");
        assertFalse(config.shouldWarn(50000));
    }

    @Test
    public void shouldBlock_missingBlock_neverBlocks() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000");
        assertFalse(config.shouldBlock(999999999L));
    }

    @Test
    public void pgDegradation_rowZero_notBlocked() {
        GovRowLimitConfig config = GovRowLimitConfig.parse("warn:1000,block:100000");
        assertFalse(config.shouldBlock(0));
        assertFalse(config.shouldWarn(0));
    }
}
