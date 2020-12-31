/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.convert;

import org.truffleruby.core.format.FormatNode;
import org.truffleruby.core.format.MissingValue;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.language.Nil;

@NodeChild("value")
public abstract class ReinterpretAsUnsignedNode extends FormatNode {

    @Specialization
    protected MissingValue asUnsigned(MissingValue missingValue) {
        return missingValue;
    }

    @Specialization
    protected Object asUnsigned(Nil nil) {
        return nil;
    }

    @Specialization
    protected int asUnsigned(short value) {
        return value & 0xffff;
    }

    @Specialization
    protected long asUnsigned(int value) {
        return value & 0xffffffffL;
    }

    @Specialization
    protected Object asUnsigned(long value) {
        return BigIntegerOps.asUnsignedFixnumOrBignum(value);
    }
}
