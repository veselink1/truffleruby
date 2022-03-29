/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.collections;

import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.RopeConstants;

import java.nio.charset.StandardCharsets;

public class ByteArrayBuilder {

    private static final Bytes EMPTY_BYTES = RopeConstants.EMPTY_BYTES;

    private Bytes bytes = EMPTY_BYTES;
    private int length;

    public ByteArrayBuilder() {
    }

    public ByteArrayBuilder(int size) {
        bytes = new Bytes(size);
    }

    public static ByteArrayBuilder createUnsafeBuilder(Bytes wrap) {
        final ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.unsafeReplace(wrap, wrap.length);
        return builder;
    }

    public int getLength() {
        return length;
    }

    public void append(int b) {
        append((byte) b);
    }

    public void append(byte b) {
        ensureSpace(1);
        bytes.set(length, b);
        length++;
    }

    public void append(byte b, int count) {
        if (count > 0) {
            ensureSpace(count);
            Bytes.fill(bytes, length, length + count, b);
            length += count;
        }
    }

    public void append(int b, int count) {
        append((byte) b, count);
    }

    public void append(Bytes appendBytes) {
        append(appendBytes, 0, appendBytes.length);
    }

    public void append(byte[] appendBytes) {
        append(appendBytes, 0, appendBytes.length);
    }

    public void append(Bytes appendBytes, int appendStart, int appendLength) {
        append(appendBytes.array, appendBytes.offset + appendStart, appendLength);
    }

    public void append(byte[] appendBytes, int appendStart, int appendLength) {
        ensureSpace(appendLength);
        System.arraycopy(appendBytes, appendStart, bytes.array, bytes.offset + length, appendLength);
        length += appendLength;
    }

    public void unsafeReplace(Bytes bytes, int size) {
        this.bytes = bytes;
        this.length = size;
    }

    private void ensureSpace(int space) {
        if (length + space > bytes.length) {
            bytes = Bytes.copyOf(bytes, (bytes.length + space) * 2);
        }
    }

    public byte get(int n) {
        return bytes.get(n);
    }

    public void set(int n, int b) {
        bytes.set(n, (byte) b);
    }

    public Bytes getBytes() {
        return Bytes.copyOf(bytes, length);
    }

    public byte[] getBytesArray() {
        return getBytes().array;
    }

    public void clear() {
        bytes = EMPTY_BYTES;
        length = 0;
    }

    @Override
    public String toString() {
        return new String(bytes.array, bytes.offset, length, StandardCharsets.ISO_8859_1);
    }

    // TODO CS 14-Feb-17 review all uses of this method
    public Bytes getUnsafeBytes() {
        return bytes;
    }

    // TODO CS 14-Feb-17 review all uses of this method
    public void setLength(int length) {
        this.length = length;
    }

    // TODO CS 14-Feb-17 review all uses of this method
    public void unsafeEnsureSpace(int space) {
        ensureSpace(space);
    }

}
