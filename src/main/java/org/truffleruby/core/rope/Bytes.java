/*
 * Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.rope;

import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.LoopConditionProfile;
import org.truffleruby.core.array.ArrayUtils;

import java.util.Arrays;

@ValueType
@ExportLibrary(InteropLibrary.class)
public final class Bytes implements TruffleObject, Cloneable {

    public static final Bytes EMPTY = new Bytes(0);

    public final byte[] array;
    public final int offset;
    public final int length;

    public static Bytes of(byte value) {
        return new Bytes(new byte[]{ value }, 0, 1);
    }

    public static Bytes of(byte... array) {
        return new Bytes(array, 0, array.length);
    }

    public Bytes(byte[] array, int offset, int length) {
        assert offset >= 0 && length >= 0 && offset + length <= array.length;
        this.array = array;
        this.offset = offset;
        this.length = length;
    }

    public Bytes(byte[] array) {
        this(array, 0, array.length);
    }

    public Bytes(int length) {
        this(new byte[length], 0, length);
    }

    public static void fill(Bytes bytes, int begin, int end, byte b) {
        Arrays.fill(bytes.array, bytes.offset + begin, bytes.offset + end, b);
    }

    public static int memchr(Bytes bytes, int start, int length, byte b) {
        return ArrayUtils.memchr(bytes.array, bytes.offset + start, length, b);
    }

    public static void copy(Bytes src, int srcPos, Bytes dest, int destPos, int length) {
        System.arraycopy(src.array, src.offset + srcPos, dest.array, dest.offset + destPos, length);
    }

    public static void fill(Bytes bytes, byte b) {
        Arrays.fill(bytes.array, bytes.offset, bytes.end(), b);
    }

    public static int memcmp(Bytes bytes, int i, Bytes otherBytes, int i1, int memcmpLength,
            RopeNodes.CompareRopesNode compareRopesNode, LoopConditionProfile loopProfile) {
        return ArrayUtils.memcmp(
                bytes.array,
                bytes.offset + i,
                otherBytes.array,
                otherBytes.offset + i1,
                memcmpLength,
                compareRopesNode,
                loopProfile);
    }

    @Override
    public Bytes clone() {
        return new Bytes(copyToArray());
    }

    @Override
    public int hashCode() {
        if (offset == 0 && length == array.length) {
            // Prefer to use the intrinsic.
            return Arrays.hashCode(array);
        } else {
            return hashCode(array, offset, length);
        }
    }

    // Conforming hashCode implementation
    private static int hashCode(byte[] array, int offset, int length) {
        if (array == null) {
            return 0;
        }

        int result = 1;
        for (int i = offset; i < offset + length; i++) {
            result = 31 * result + array[i];
        }

        return result;
    }

    public static Bytes fromRange(byte[] array, int start, int end) {
        assert 0 <= start && start <= end && end <= array.length;
        return new Bytes(array, start, end - start);
    }

    /** Just like {@link #fromRange(byte[], int, int)}, but will clamp the length to stay within the bounds. */
    public static Bytes fromRangeClamped(byte[] array, int start, int end) {
        return fromRange(array, start, Math.min(array.length, end));
    }

    public static Bytes extractRange(Bytes source, int start, int end) {
        int length = end - start;
        assert length < source.length;

        byte[] result = new byte[length];
        System.arraycopy(source.array, source.offset + start, result, 0, length);
        return new Bytes(result);
    }

    public static Bytes copyOf(Bytes bytes, int newLength) {
        final byte[] newBytes = new byte[newLength];
        System.arraycopy(
                bytes.array,
                bytes.offset,
                newBytes,
                0,
                Math.min(bytes.length, newLength));
        return new Bytes(newBytes);
    }

    public static boolean equals(Bytes bytes, Bytes bytes1) {
        return Arrays.equals(bytes.array, bytes.offset, bytes.end(), bytes1.array, bytes1.offset, bytes1.end());
    }

    public boolean referenceAndRangeEquals(Bytes bytes) {
        return array == bytes.array && offset == bytes.offset && length == bytes.length;
    }

    /** Returns the end offset, equal to {@link #offset} + {@link #length}. */
    public int end() {
        return offset + length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public Bytes slice(int offset, int length) {
        assert offset >= 0 && length >= 0 && offset + length <= this.length;
        return new Bytes(this.array, this.offset + offset, length);
    }

    public Bytes sliceRange(int start, int end) {
        assert start >= 0 && end >= 0 && start <= end && end <= this.length;
        return new Bytes(this.array, this.offset + start, end - start);
    }

    /** Just like {@link #slice(int, int)}}, but will clamp the length to stay within the bounds. */
    public Bytes clampedSlice(int offset, int length) {
        return slice(offset, Math.min(length, this.length - offset));
    }

    /** Just like {@link #sliceRange(int, int)}}, but will clamp the end offset to stay within the bounds. */
    public Bytes clampedRange(int start, int end) {
        return sliceRange(start, Math.min(end, this.length));
    }

    public byte get(int i) {
        return array[offset + i];
    }

    public void set(int i, byte c) {
        array[offset + i] = c;
    }

    // region Array messages for TRegex
    @ExportMessage
    public boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    public long getArraySize() {
        return length;
    }

    @ExportMessage
    public Object readArrayElement(long index,
            @Cached BranchProfile errorProfile) throws InvalidArrayIndexException {
        if (isArrayElementReadable(index)) {
            return get((int) index);
        } else {
            errorProfile.enter();
            throw InvalidArrayIndexException.create(index);
        }
    }

    @ExportMessage
    public boolean isArrayElementReadable(long index) {
        return index >= 0 && index < length;
    }

    public byte[] toArray() {
        if (offset == 0 && length == array.length) {
            return array;
        } else {
            byte[] result = new byte[length];
            System.arraycopy(array, offset, result, 0, length);
            return result;
        }
    }

    public byte[] copyToArray() {
        byte[] result = new byte[length];
        System.arraycopy(array, offset, result, 0, length);
        return result;
    }
    // endregion
}
