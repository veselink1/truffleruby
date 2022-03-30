/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */

package org.truffleruby.core.rope;

import com.oracle.truffle.api.profiles.ConditionProfile;
import org.jcodings.Encoding;

import com.oracle.truffle.api.CompilerDirectives;

public class AsciiOnlyLeafRope extends LeafRope {

    public AsciiOnlyLeafRope(byte[] bytes, Encoding encoding) {
        this(true, bytes, encoding, bytes.length);
    }

    AsciiOnlyLeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding) {
        this(isReadOnly, bytes, encoding, bytes.length);
    }

    private AsciiOnlyLeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding, int byteLength) {
        super(isReadOnly, bytes, encoding, CodeRange.CR_7BIT, byteLength, byteLength);

        assert RopeOperations.isAsciiOnly(bytes, encoding) : "MBC string incorrectly marked as CR_7BIT";
    }

    @Override
    Rope withEncoding7bit(Encoding newEncoding, ConditionProfile bytesNotNull) {
        final byte[] rawBytes = bytes;
        final boolean isReadOnly = isReadOnly();
        if (!isReadOnly) {
            // If the rope is mutable, it is only referenced from one string.
            // In that case, we "move" the byte array, by setting the reference in this instance to null.
            // This allows us to detect when this assumption is broken (the only time we see a LeafRope with null bytes).
            bytes = null;
        }
        return new AsciiOnlyLeafRope(isReadOnly, rawBytes, newEncoding, byteLength);
    }

    @Override
    Rope withBinaryEncoding(ConditionProfile bytesNotNull) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnsupportedOperationException("Must only be called for CR_VALID Strings");
    }
}
