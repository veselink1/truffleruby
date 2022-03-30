/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.string;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import org.jcodings.Encoding;
import org.truffleruby.collections.IdentityKey;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.rope.MutableRope;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.interop.ToJavaStringNode;
import org.truffleruby.language.RubyDynamicObject;
import org.truffleruby.language.library.RubyLibrary;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.Shape;
import org.truffleruby.language.library.RubyStringLibrary;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

@ExportLibrary(RubyLibrary.class)
@ExportLibrary(InteropLibrary.class)
@ExportLibrary(RubyStringLibrary.class)
public class RubyString extends RubyDynamicObject {

    private static final Set<IdentityKey<MutableRope>> KNOWN_MUTABLE_ROPES = Collections
            .newSetFromMap(new WeakHashMap<>(0));

    public boolean frozen;
    public Rope rope;
    public RubyEncoding encoding;

    public RubyString(RubyClass rubyClass, Shape shape, boolean frozen, Rope rope, RubyEncoding rubyEncoding) {
        super(rubyClass, shape);
        assert rope.encoding == rubyEncoding.jcoding;
        this.frozen = frozen;
        this.rope = rope;
        this.encoding = rubyEncoding;
    }

    public void setRope(Rope rope) {
        assert rope.encoding == encoding.jcoding : rope.encoding.toString() + " does not equal " +
                encoding.jcoding.toString();
        assert prepareToSetAndCheckRope(rope) : "Unsafe sharing of mutable rope detected!";

        this.rope = rope;
    }

    public void setRope(Rope rope, RubyEncoding encoding) {
        assert rope.encoding == encoding.jcoding : String
                .format("rope: %s != string: %s", rope.encoding.toString(), encoding.jcoding.toString());
        assert prepareToSetAndCheckRope(rope) : "Unsafe sharing of mutable rope detected!";

        this.rope = rope;
        this.encoding = encoding;
    }

    /** should only be used for debugging */
    @Override
    public String toString() {
        return rope.toString();
    }

    public Encoding getJCoding() {
        assert encoding.jcoding == rope.encoding;
        return encoding.jcoding;
    }

    // region RubyStringLibrary messages
    @ExportMessage
    public RubyEncoding getEncoding() {
        return encoding;
    }

    @ExportMessage
    protected boolean isRubyString() {
        return true;
    }

    @ExportMessage
    protected Rope getRope() {
        return rope;
    }

    @ExportMessage
    protected String getJavaString() {
        return RopeOperations.decodeRope(rope);
    }
    // endregion

    // region RubyLibrary messages
    @ExportMessage
    protected void freeze() {
        frozen = true;
    }

    @ExportMessage
    protected boolean isFrozen() {
        return frozen;
    }
    // endregion

    // region String messages
    @ExportMessage
    protected boolean isString() {
        return true;
    }

    @ExportMessage
    protected String asString(
            @Cached ToJavaStringNode toJavaStringNode) {
        return toJavaStringNode.executeToJavaString(this);
    }
    // endregion

    // This is slow and is meant to be used when assertions are enabled.
    private boolean prepareToSetAndCheckRope(Rope rope) {
        if (this.rope == rope) {
            return true;
        }

        if (this.rope instanceof MutableRope) {
            KNOWN_MUTABLE_ROPES.remove(new IdentityKey<>((MutableRope) this.rope));
        }

        if (!(rope instanceof MutableRope)) {
            return true;
        }

        MutableRope mutableRope = (MutableRope) rope;
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
