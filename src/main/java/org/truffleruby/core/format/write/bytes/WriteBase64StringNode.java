/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.write.bytes;

import org.truffleruby.collections.ByteArrayBuilder;
import org.truffleruby.core.format.FormatNode;
import org.truffleruby.core.format.exceptions.NoImplicitConversionException;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

@NodeChild("value")
public abstract class WriteBase64StringNode extends FormatNode {

    private final int length;
    private final boolean ignoreStar;

    public WriteBase64StringNode(int length, boolean ignoreStar) {
        this.length = length;
        this.ignoreStar = ignoreStar;
    }

    @Specialization
    protected Object write(long bytes) {
        throw new NoImplicitConversionException(bytes, "String");
    }

    @Specialization
    protected Object write(VirtualFrame frame, byte[] bytes) {
        writeBytes(frame, encode(bytes));
        return null;
    }

    @Specialization
    protected Object write(VirtualFrame frame, Rope rope,
            @Cached RopeNodes.BytesNode bytesNode) {
        return write(frame, bytesNode.execute(rope).toArray());
    }

    @TruffleBoundary
    private byte[] encode(byte[] bytes) {
        // TODO CS 30-Mar-15 should write our own optimisable version of Base64

        final ByteArrayBuilder output = new ByteArrayBuilder();
        EncodeUM.encodeUM(null, bytes, length, ignoreStar, 'm', output);
        return output.getBytesArray();
    }

}
