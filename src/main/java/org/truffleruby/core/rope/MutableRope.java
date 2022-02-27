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

/** A {@link Rope} implements {@link MutableRope} when the internal rope buffer has a mutable state. A mutable rope is
 * in mutable state when {@link #isReadOnly()} is true. A mutable rope can be transitioned to the read-only state using
 * {@link #makeReadOnly()}. Once in the read-only state, a mutable rope cannot be transitioned to the mutable state and
 * implementors should not implement such operation. */
public interface MutableRope extends Cloneable {
    /** Is the rope in the read-only state. */
    boolean isReadOnly();

    /** Transitions the rope to the read-only state. */
    void makeReadOnly();

    /** Provides access to the internal byte buffer of the rope. */
    byte[] getRawBytes();
}
