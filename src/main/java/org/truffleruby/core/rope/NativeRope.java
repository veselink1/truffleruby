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
import org.jcodings.specific.ASCIIEncoding;
import org.truffleruby.RubyContext;
import org.truffleruby.core.string.StringAttributes;
import org.truffleruby.core.string.StringSupport;
import org.truffleruby.extra.ffi.Pointer;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

public class NativeRope extends Rope {

    public static final int UNKNOWN_CHARACTER_LENGTH = -1;

    private CodeRange codeRange;
    private int characterLength;
    private final Pointer pointer;

    public NativeRope(
            RubyContext context,
            Bytes bytes,
            Encoding encoding,
            int characterLength,
            CodeRange codeRange) {
        this(allocateNativePointer(context, bytes), bytes.length, encoding, characterLength, codeRange);
    }

    private NativeRope(Pointer pointer, int byteLength, Encoding encoding, int characterLength, CodeRange codeRange) {
        super(encoding, byteLength, null);

        assert (codeRange == CodeRange.CR_UNKNOWN) == (characterLength == UNKNOWN_CHARACTER_LENGTH);
        this.codeRange = codeRange;
        this.characterLength = characterLength;
        this.pointer = pointer;
    }

    private static Pointer allocateNativePointer(RubyContext context, Bytes bytes) {
        final Pointer pointer = Pointer.malloc(bytes.length + 1);
        pointer.enableAutorelease(context);
        pointer.writeBytes(0, bytes.array, bytes.offset, bytes.length);
        pointer.writeByte(bytes.length, (byte) 0);
        return pointer;
    }

    private static Pointer copyNativePointer(RubyContext context, Pointer existing) {
        final Pointer pointer = Pointer.malloc(existing.getSize());
        pointer.enableAutorelease(context);
        pointer.writeBytes(0, existing, 0, existing.getSize());
        return pointer;
    }

    public static NativeRope newBuffer(RubyContext context, int byteCapacity, int byteLength) {
        assert byteCapacity >= byteLength;

        final Pointer pointer = Pointer.calloc(byteCapacity + 1);
        pointer.enableAutorelease(context);

        return new NativeRope(
                pointer,
                byteLength,
                ASCIIEncoding.INSTANCE,
                UNKNOWN_CHARACTER_LENGTH,
                CodeRange.CR_UNKNOWN);
    }

    public NativeRope withByteLength(int newByteLength, int characterLength, CodeRange codeRange) {
        pointer.writeByte(newByteLength, (byte) 0); // Like MRI
        return new NativeRope(pointer, newByteLength, getEncoding(), characterLength, codeRange);
    }

    public NativeRope makeCopy(RubyContext context) {
        final Pointer newPointer = copyNativePointer(context, pointer);
        return new NativeRope(newPointer, byteLength(), getEncoding(), characterLength(), getCodeRange());
    }

    public NativeRope resize(RubyContext context, int newByteLength) {
        assert byteLength() != newByteLength;

        final Pointer pointer = Pointer.malloc(newByteLength + 1);
        pointer.writeBytes(0, this.pointer, 0, Math.min(getNativePointer().getSize(), newByteLength));
        pointer.writeByte(newByteLength, (byte) 0); // Like MRI
        pointer.enableAutorelease(context);
        return new NativeRope(pointer, newByteLength, getEncoding(), UNKNOWN_CHARACTER_LENGTH, CodeRange.CR_UNKNOWN);
    }

    /** Creates a new native rope which preserves existing bytes and byte length up to newCapacity
     *
     * @param context the Ruby context
     * @param newCapacity the size in bytes minus one of the new pointer length
     * @return the new NativeRope */
    public NativeRope expandCapacity(RubyContext context, int newCapacity) {
        assert getCapacity() != newCapacity;
        final Pointer pointer = Pointer.malloc(newCapacity + 1);
        pointer.writeBytes(0, this.pointer, 0, Math.min(getNativePointer().getSize(), newCapacity));
        pointer.writeByte(newCapacity, (byte) 0); // Like MRI
        pointer.enableAutorelease(context);
        return new NativeRope(
                pointer,
                byteLength(),
                getEncoding(),
                UNKNOWN_CHARACTER_LENGTH,
                CodeRange.CR_UNKNOWN);
    }

    @Override
    public Bytes getBytes() {
        // Always re-read bytes from the native pointer as they might have changed.
        final Bytes bytes = new Bytes(byteLength());
        copyTo(0, bytes, 0, byteLength());
        return bytes;
    }

    public CodeRange getRawCodeRange() {
        return codeRange;
    }

    @Override
    public CodeRange getCodeRange() {
        if (codeRange == CodeRange.CR_UNKNOWN) {
            final StringAttributes attributes = RopeOperations
                    .calculateCodeRangeAndLength(getEncoding(), getBytes(), 0, byteLength());
            updateAttributes(attributes);
            return attributes.getCodeRange();
        } else {
            return codeRange;
        }
    }

    public int rawCharacterLength() {
        return characterLength;
    }

    @Override
    public int characterLength() {
        if (characterLength == UNKNOWN_CHARACTER_LENGTH) {
            final StringAttributes attributes = RopeOperations
                    .calculateCodeRangeAndLength(getEncoding(), getBytes(), 0, byteLength());
            updateAttributes(attributes);
            return attributes.getCharacterLength();
        } else {
            return characterLength;
        }
    }

    public void clearCodeRange() {
        this.characterLength = UNKNOWN_CHARACTER_LENGTH;
        this.codeRange = CodeRange.CR_UNKNOWN;
    }

    public void updateAttributes(StringAttributes attributes) {
        this.characterLength = attributes.getCharacterLength();
        this.codeRange = attributes.getCodeRange();
    }

    public Bytes getBytes(int byteOffset, int byteLength) {
        final Bytes bytes = new Bytes(byteLength);
        copyTo(byteOffset, bytes, 0, byteLength);
        return bytes;
    }

    @TruffleBoundary
    public void copyTo(int byteOffset, Bytes dest, int bufferPos, int byteLength) {
        pointer.readBytes(byteOffset, dest.array, dest.offset + bufferPos, byteLength);
    }

    @Override
    public byte getByteSlow(int index) {
        return get(index);
    }

    @Override
    public byte get(int index) {
        assert 0 <= index && index < pointer.getSize();
        return pointer.readByte(index);
    }

    public void set(int index, int value) {
        assert 0 <= index && index < pointer.getSize();
        assert value >= -128 && value < 256;

        if (!(codeRange == CodeRange.CR_7BIT && StringSupport.isAsciiCodepoint(value))) {
            clearCodeRange();
        }

        pointer.writeByte(index, (byte) value);
    }

    @Override
    public int hashCode() {
        // TODO (pitr-ch 16-May-2017): this forces Rope#hashCode to be non-final, which is bad for performance
        return RopeOperations.hashForRange(this, 1, 0, byteLength());
    }

    @Override
    Rope withEncoding7bit(Encoding newEncoding, ConditionProfile bytesNotNull) {
        return withEncoding(newEncoding);
    }

    @Override
    Rope withBinaryEncoding(ConditionProfile bytesNotNull) {
        return withEncoding(ASCIIEncoding.INSTANCE);
    }

    NativeRope withEncoding(Encoding newEncoding) {
        return new NativeRope(pointer, byteLength(), newEncoding, UNKNOWN_CHARACTER_LENGTH, CodeRange.CR_UNKNOWN);
    }

    public Pointer getNativePointer() {
        return pointer;
    }

    public long getCapacity() {
        final long nativeBufferSize = pointer.getSize();
        assert nativeBufferSize > 0;
        // Do not count the extra byte for \0, like MRI.
        return nativeBufferSize - 1;
    }

    @TruffleBoundary
    public LeafRope toLeafRope() {
        return RopeOperations.create(getBytes(), getEncoding(), CodeRange.CR_UNKNOWN);
    }

}
