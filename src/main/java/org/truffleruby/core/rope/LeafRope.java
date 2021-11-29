/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.rope;

import com.oracle.truffle.api.CompilerDirectives;
import org.jcodings.Encoding;

import static org.truffleruby.core.rope.CodeRange.CR_BROKEN;
import static org.truffleruby.core.rope.CodeRange.CR_VALID;

public abstract class LeafRope extends ManagedRope {

    private final boolean isReadOnly;

    protected LeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding, CodeRange codeRange, int characterLength) {
        super(encoding, codeRange, bytes.length, characterLength, bytes);
        this.isReadOnly = isReadOnly;
    }

    @Override
    public byte getByteSlow(int index) {
        return getRawBytes()[index];
    }

    @Override
    public LeafRope getMutable() {
        if (!isReadOnly) {
            return this;
        } else {
            return clone(false);
        }
    }

    protected final boolean isReadOnly() {
        return isReadOnly;
    }

    protected final LeafRope clone(boolean isReadOnly) {
        if (this instanceof AsciiOnlyLeafRope) {
            return new AsciiOnlyLeafRope(isReadOnly, bytes, encoding);
        } else if (this instanceof ValidLeafRope) {
            return new ValidLeafRope(isReadOnly, bytes.clone(), encoding, characterLength());
        } else if (this instanceof InvalidLeafRope) {
            return new InvalidLeafRope(isReadOnly, bytes, encoding, characterLength());
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnsupportedOperationException("clone() for " + this.getClass());
        }
    }

    public void replaceRange(int spliceByteIndex, byte[] srcBytes, CodeRange srcCodeRange) {
        assert !isReadOnly : this.getClass() + " not mutable!";
        codeRange = commonCodeRange(getCodeRange(), srcCodeRange);
        System.arraycopy(srcBytes, 0, this.bytes, spliceByteIndex, srcBytes.length);
    }

    private static CodeRange commonCodeRange(CodeRange first, CodeRange second) {
        if (first == second) {
            return first;
        }

        if ((first == CR_BROKEN) || (second == CR_BROKEN)) {
            return CR_BROKEN;
        }

        // If we get this far, one must be CR_7BIT and the other must be CR_VALID, so promote to the more general code range.
        return CR_VALID;
    }
}
