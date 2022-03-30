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

import com.oracle.truffle.api.profiles.ConditionProfile;
import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;

import com.oracle.truffle.api.CompilerDirectives;

public class ValidLeafRope extends LeafRope {

    public ValidLeafRope(byte[] bytes, Encoding encoding, int characterLength) {
        this(true, bytes, encoding, characterLength);
    }

    ValidLeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding, int characterLength) {
        this(isReadOnly, bytes, encoding, bytes.length, characterLength);
    }

    private ValidLeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding, int byteLength, int characterLength) {
        super(isReadOnly, bytes, encoding, CodeRange.CR_VALID, byteLength, characterLength);

        // It makes sense to use a ValidLeafRope node with ASCII-compatible encoding
        // with code range set to CR_VALID even if the actual code range is CR_7BIT,
        // but only when the node is mutable (because we expect to be replacing code points).
        assert !isReadOnly ||
                !RopeOperations.isAsciiOnly(bytes, encoding) : "ASCII-only string incorrectly marked as CR_VALID";
        assert !RopeOperations.isInvalid(bytes, encoding) : "Broken string incorrectly marked as CR_VALID";
    }

    @Override
    Rope withEncoding7bit(Encoding newEncoding, ConditionProfile bytesNotNull) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnsupportedOperationException("Must only be called for ASCII-only Strings");
    }

    @Override
    Rope withBinaryEncoding(ConditionProfile bytesNotNull) {
        final byte[] rawBytes = bytes;
        final boolean isReadOnly = isReadOnly();
        if (!isReadOnly) {
            // If the rope is mutable, it is only referenced from one string.
            // In that case, we "move" the byte array, by setting the reference in this instance to null.
            // This allows us to detect when this assumption is broken (the only time we see a LeafRope with null bytes).
            bytes = null;
        }
        return new ValidLeafRope(isReadOnly, rawBytes, ASCIIEncoding.INSTANCE, byteLength());
    }
}
