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

import org.jcodings.Encoding;

public abstract class LeafRope extends ManagedRope implements MutableRope {

    private volatile boolean isReadOnly;

    protected LeafRope(
            boolean isReadOnly,
            byte[] bytes,
            Encoding encoding,
            CodeRange codeRange,
            int byteLength,
            int characterLength) {
        super(encoding, codeRange, byteLength, characterLength, bytes);
        this.isReadOnly = isReadOnly;

        assert !isReadOnly ||
                bytes.length == byteLength : "Read-only ropes cannot have an over-allocated internal buffer.";
    }

    @Override
    public byte getByteSlow(int index) {
        return getRawBytesUnsafe()[index];
    }

    public final boolean isReadOnly() {
        return isReadOnly;
    }

    public final void makeReadOnly() {
        isReadOnly = true;
    }

    public void setByte(int index, byte b) {
        assert !isReadOnly() : this.getClass() + " not mutable!";
        bytes[index] = b;
    }

    public void replaceRange(int spliceByteIndex, byte[] srcBytes, CodeRange srcCodeRange) {
        assert getCodeRange() == CodeRange.commonCodeRange(getCodeRange(), srcCodeRange) : "Cannot replace " +
                getCodeRange() + " bytes with " + srcCodeRange + "!";
        assert !isReadOnly() : this.getClass() + " not mutable!";

        if (srcBytes.length == 1) {
            bytes[spliceByteIndex] = srcBytes[0];
        } else {
            System.arraycopy(srcBytes, 0, this.bytes, spliceByteIndex, srcBytes.length);
        }
    }
}
