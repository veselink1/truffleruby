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

public abstract class LeafRope extends ManagedRope {

    // isReadOnly is set at construction time.
    private final boolean isReadOnly;
    // isReadOnlyOverride is set at runtime to convert mutable strings to immutable without defensively copying.
    // Having a separate "override" flag tells the compiler that isReadOnly=true is always a constant and that
    // strings can only become isReadOnly=true, but never the other way round.
    private volatile boolean isReadOnlyOverride;

    protected LeafRope(boolean isReadOnly, byte[] bytes, Encoding encoding, CodeRange codeRange, int characterLength) {
        super(encoding, codeRange, bytes.length, characterLength, bytes);
        this.isReadOnly = isReadOnly;
        this.isReadOnlyOverride = false;
    }

    @Override
    public byte getByteSlow(int index) {
        return getRawBytes()[index];
    }

    public final boolean isReadOnly() {
        // We do not need to synchronize() here when reading
        // isReadOnlyOverride, because:
        // 1. if isReadOnlyOverride = false
        //    it could be set to true only from makeReadOnly, which is thread-safe.
        //    and all mutating operations go through getMutableRope();
        return isReadOnly || isReadOnlyOverride;
    }

    public final void makeReadOnly() {
        isReadOnlyOverride = true;
    }

    public void replaceRange(int spliceByteIndex, byte[] srcBytes, CodeRange srcCodeRange) {
        assert getCodeRange() == CodeRange.commonCodeRange(getCodeRange(), srcCodeRange) : "Cannot replace " +
                getCodeRange() + " bytes with " + srcCodeRange + "!";
        assert !isReadOnly : this.getClass() + " not mutable!";
        if (isReadOnlyOverride) {
            // This indicates that the user has been changing the string buffer from one thread
            // and using the string in other ropes from another. Since that is a clear race condition, there is
            // nothing we can really do, except maybe abort.
            // TODO(veselink1): Think about warning the user that they might want to consider adding synchronization.
        }
        System.arraycopy(srcBytes, 0, this.bytes, spliceByteIndex, srcBytes.length);
    }
}
