/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 *
 * Some of the code in this class is modified from org.jruby.util.StringSupport,
 * licensed under the same EPL 2.0/GPL 2.0/LGPL 2.1 used throughout.
 */

package org.truffleruby.core.support;

/** Hypothesise that a condition is true in the majority of cases. Must be initialized with a binomial distribution with
 * numSuccess > numTrials. Once the hypothesis is falsified, a Hypothesis.Rejected exception is thrown. */
public final class Hypothesis {

    public static class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private int numSuccesses = 0;
    private int numTrials = 0;

    public Hypothesis(int numSuccesses, int numTrials) {
        assert numSuccesses <= numTrials;
        assert numTrials >= 0;
        this.numSuccesses = numSuccesses;
        this.numTrials = numTrials;
    }

    /** Records the outcome and ensures that the value being true is still more likely than it being false.
     * 
     * @param value
     * @return */
    public boolean test(boolean value) {
        if (value) {
            numSuccesses++;
        }
        numTrials++;
        if (numTrials - numSuccesses > numSuccesses) {
            throw new Rejected();
        }
        return value;
    }
}
