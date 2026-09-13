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
package com.clougence.clouddm.sec.rules.execute.sensitive;

import java.util.Arrays;
import java.util.List;

/**
 * @author mode create time is 2023/05/21 13:27
 **/
public class SenAlgorithm {

    public static final String        SEN_ORIGINAL  = "algorithm::ORIGINAL";
    public static final String        SEN_HALF_MASK = "algorithm::HALF_MASK";
    public static final String        SEN_FULL_MASK = "algorithm::FULL_MASK";

    //the bigger the index, the better,
    private static final List<String> algorithmPool = Arrays.asList(SEN_ORIGINAL, SEN_HALF_MASK, SEN_FULL_MASK);

    public static String chooseAlgorithm(String current, String newone) {
        int a = poolIndexOf(current);
        int b = poolIndexOf(newone);
        if (a < 0 || b < 0) {
            throw new IllegalStateException(current + " or " + newone + " not in algorithmPool.");
        }

        if (a > b) {
            return current;
        } else if (a < b) {
            return newone;
        } else {
            return current;
        }
    }

    /**
     * Resolve the strength index of an algorithm command against {@link #algorithmPool}.
     * <p>
     * Algorithms may carry arguments in the command string, e.g.
     * {@code "algorithm::HALF_MASK(4,7)"}; the parenthesised arguments are
     * stripped before lookup so the base name ({@code "algorithm::HALF_MASK"})
     * is matched against the pool. This keeps the "stronger algorithm wins"
     * semantics of {@link #chooseAlgorithm} working for parameterised commands
     * while leaving ORIGINAL / FULL_MASK (which carry no arguments) untouched.
     */
    private static int poolIndexOf(String algorithm) {
        if (algorithm == null) {
            return -1;
        }
        int idx = algorithmPool.indexOf(algorithm);
        if (idx >= 0) {
            return idx;
        }
        int paren = algorithm.indexOf('(');
        if (paren > 0) {
            return algorithmPool.indexOf(algorithm.substring(0, paren));
        }
        return -1;
    }

    // algorithm::original
    public static String doOriginalAlgorithm(String command, String cv) {
        return cv;
    }

    // algorithm::full_mask
    public static String doFullMaskAlgorithm(String command, String cv) {
        return "******";
    }

    // algorithm::half_mask(start,end)  — 1-based closed interval; fail-closed to FULL_MASK
    public static String doHalfMaskAlgorithm(String command, String cv) {
        int lbtIndex = command.indexOf("(");
        int rbtIndex = command.indexOf(")");
        if (lbtIndex < 0 || rbtIndex < 0 || rbtIndex < lbtIndex) {
            return doFullMaskAlgorithm(command, cv);
        }

        String[] splitArg = command.substring(lbtIndex + 1, rbtIndex).split(",");
        if (splitArg.length < 2) {
            return doFullMaskAlgorithm(command, cv);
        }

        int start;
        int end;
        try {
            start = Integer.parseInt(splitArg[0].trim());
            end = Integer.parseInt(splitArg[1].trim());
        } catch (NumberFormatException e) {
            return doFullMaskAlgorithm(command, cv);
        }

        // fail-closed: null value, inverted/invalid range, or value shorter than the end offset
        if (cv == null || start < 1 || start > end || cv.length() < end) {
            return doFullMaskAlgorithm(command, cv);
        }

        int from = start - 1;
        int to = end;
        StringBuilder sb = new StringBuilder(cv.length());
        sb.append(cv, 0, from);
        for (int i = from; i < to; i++) {
            sb.append('*');
        }
        sb.append(cv, to, cv.length());
        return sb.toString();
    }
}
