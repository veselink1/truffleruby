/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of Rubinius nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.truffleruby.core.string;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeCost;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.language.RubyBaseNode;

public class StringProfilingNodes {

    /** Indicates a preference for the underlying rope implementation. */
    public enum RopeOptimizationHint {
        /// More profiling is needed.
        UNDEFINED,
        /// Profiling indicates a mutable implementation might be best.
        MUTABLE,
        /// Profiling indicates an immutable implementation might be best.
        IMMUTABLE,
    }

    /** <p>
     * This node injects runtime profiling into string operations. The profiling is based on a sliding window approach,
     * whereby two values - the old value and the new value are passed in, and the node returns one of three values,
     * indicating whether there is a lot of reuse happening, minimal reuse or indeterminate (which should be ignored).
     * </p>
     * <p>
     * The initial Reuse and NoReuse counts are initialized to the same unspecified positive value. The returned value
     * is the fraction {@code Reuse / (Reuse + NoReuse)}.
     * </p>
    */
    @ImportStatic(StringGuards.class)
    @ReportPolymorphism
    protected abstract static class RopeReuseMonitorNode extends RubyBaseNode {

        // Some large limit that we could never overflow due to race conditions or by our arithmetic.
        private static final int MAX_VALUE = 0x0fffffff;

        protected int ropeReuseCount = 1000;
        protected int ropeNoReuseCount = 1000;
        protected int prevOutputRopeIdentity = 0;

        @Override
        public NodeCost getCost() {
            // We return a cost of 0, since we don't want our profiling to influence any decisions of the
            // underlying implementation in regard to optimization.
            return NodeCost.NONE;
        }

        public abstract double execute(Rope inputRope, Rope outputRope);

        @Specialization
        protected double profile(Rope inputRope, Rope outputRope) {

            boolean isReused = prevOutputRopeIdentity == System.identityHashCode(inputRope);
            prevOutputRopeIdentity = System.identityHashCode(outputRope);
            if (isReused) {
                if (ropeReuseCount < MAX_VALUE) {
                    ++ropeReuseCount;
                }
            } else if (ropeNoReuseCount < MAX_VALUE) {
                ++ropeNoReuseCount;
            }

            return (double) ropeReuseCount / (ropeReuseCount + ropeNoReuseCount);
        }

    }

    /** Profiles rope mutation operations of the form {@code f(inputRope) -> outputRope} and returns an
     * "optimization hint", indicating whether these operations might benefit from a mutable rope implementation. */
    @ReportPolymorphism
    public abstract static class ProfileRopeMutationNode extends RubyBaseNode {

        /** A profiling node which collapses into a constant expression when a hint other than
         * {@code RopeOptimizationHint.UNDEFINED} is returned. */
        public static ProfileRopeMutationNode createEphemeral() {
            return EphemeralProfileRopeMutationNode.create();
        }

        @Override
        public NodeCost getCost() {
            // We return a cost of 0, since we don't want our profiling to influence any decisions of the
            // underlying implementation in regard to optimization.
            return NodeCost.NONE;
        }

        public abstract RopeOptimizationHint execute(Rope inputRope, Rope outputRope);
    }

    @ImportStatic(StringGuards.class)
    @NodeField(name = "constantValue", type = RopeOptimizationHint.class)
    protected abstract static class ConstantProfileRopeMutationNode extends ProfileRopeMutationNode {

        public static final ConstantProfileRopeMutationNode MUTABLE = StringProfilingNodesFactory.ConstantProfileRopeMutationNodeGen
                .create(RopeOptimizationHint.MUTABLE);
        public static final ConstantProfileRopeMutationNode IMMUTABLE = StringProfilingNodesFactory.ConstantProfileRopeMutationNodeGen
                .create(RopeOptimizationHint.IMMUTABLE);
        public static final ConstantProfileRopeMutationNode UNDEFINED = StringProfilingNodesFactory.ConstantProfileRopeMutationNodeGen
                .create(RopeOptimizationHint.UNDEFINED);

        public static ProfileRopeMutationNode create(RopeOptimizationHint constantValue) {
            switch (constantValue) {
                case UNDEFINED:
                    return UNDEFINED;
                case MUTABLE:
                    return MUTABLE;
                case IMMUTABLE:
                    return IMMUTABLE;
                default:
                    throw new IllegalStateException(constantValue.toString());
            }
        }

        public abstract RopeOptimizationHint getConstantValue();

        @Specialization
        protected RopeOptimizationHint profile(Rope inputRope, Rope outputRope) {
            return getConstantValue();
        }
    }

    @ImportStatic(StringGuards.class)
    @ReportPolymorphism
    protected abstract static class EphemeralProfileRopeMutationNode extends ProfileRopeMutationNode {

        public static final double HIGH_REUSE_THRESHOLD = 0.75;
        public static final double LOW_REUSE_THRESHOLD = 0.1;

        public static ProfileRopeMutationNode create() {
            return StringProfilingNodesFactory.EphemeralProfileRopeMutationNodeGen.create();
        }

        @Specialization
        protected RopeOptimizationHint profile(Rope inputRope, Rope outputRope,
                @Cached RopeReuseMonitorNode reuseMonitorNode) {

            double reuse = reuseMonitorNode.execute(inputRope, outputRope);

            if (reuse >= HIGH_REUSE_THRESHOLD) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                System.err.println("Collapsing EphemeralProfileRopeMutationNode -> MUTABLE");
                replace(ConstantProfileRopeMutationNode.create(RopeOptimizationHint.MUTABLE));
            } else if (reuse <= LOW_REUSE_THRESHOLD) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                System.err.println("Collapsing EphemeralProfileRopeMutationNode -> IMMUTABLE");
                replace(ConstantProfileRopeMutationNode.create(RopeOptimizationHint.IMMUTABLE));
            }

            return RopeOptimizationHint.UNDEFINED;
        }

    }

}
