/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.collections;

public final class IdentityKey<T> {
    public final T key;

    public IdentityKey(T key) {
        this.key = key;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(key);
    }

    @Override
    public boolean equals(Object obj) {
        return this.key == obj;
    }
}
