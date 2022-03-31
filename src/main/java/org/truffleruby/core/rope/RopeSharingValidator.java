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

import org.truffleruby.collections.IdentityKey;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class RopeSharingValidator {

    private static final Set<IdentityKey<MutableRope>> KNOWN_MUTABLE_ROPES = Collections
            .newSetFromMap(new WeakHashMap<>(0));

    /** Checks whether the newReference can be safely referenced by an unspecified referrer if that object was
     * previously referencing oldReference.
     *
     * @param oldReference The value being replaced in the referrer object
     * @param newReference The rope that is going to be referenced
     * @return */
    public static boolean checkAttach(Rope newReference, Rope oldReference) {
        if (oldReference == newReference) {
            return true;
        }

        if (oldReference instanceof MutableRope) {
            KNOWN_MUTABLE_ROPES.remove(new IdentityKey<>((MutableRope) oldReference));
        }

        if (!(newReference instanceof MutableRope)) {
            return true;
        }

        MutableRope mutableRope = (MutableRope) newReference;
        if (mutableRope.isReadOnly()) {
            return true;
        }

        synchronized (KNOWN_MUTABLE_ROPES) {
            if (KNOWN_MUTABLE_ROPES.contains(new IdentityKey<>(mutableRope))) {
                return false;
            }
            KNOWN_MUTABLE_ROPES.add(new IdentityKey<>(mutableRope));
        }

        return true;
    }
}
