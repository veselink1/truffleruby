/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.oracle.truffle.api.frame.FrameUtil;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.format.exceptions.TooFewArgumentsException;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.RopeConstants;
import org.truffleruby.language.RubyBaseNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ConditionProfile;

@ImportStatic(FormatGuards.class)
public abstract class FormatNode extends RubyBaseNode {

    public static final FormatNode[] EMPTY_ARRAY = new FormatNode[0];

    private final ConditionProfile writeMoreThanZeroBytes = ConditionProfile.create();
    private final ConditionProfile tooFewArgumentsProfile = ConditionProfile.create();
    private final ConditionProfile sourceRangeProfile = ConditionProfile.create();
    private final ConditionProfile codeRangeIncreasedProfile = ConditionProfile.create();

    public abstract Object execute(VirtualFrame frame);

    public int getSourceLength(VirtualFrame frame) {
        return FrameUtil.getIntSafe(frame, FormatFrameDescriptor.SOURCE_LENGTH_SLOT);
    }

    protected int getSourcePosition(VirtualFrame frame) {
        return FrameUtil.getIntSafe(frame, FormatFrameDescriptor.SOURCE_POSITION_SLOT);
    }

    protected void setSourcePosition(VirtualFrame frame, int position) {
        frame.setInt(FormatFrameDescriptor.SOURCE_POSITION_SLOT, position);
    }

    protected int advanceSourcePosition(VirtualFrame frame) {
        return advanceSourcePosition(frame, 1);
    }

    protected int advanceSourcePosition(VirtualFrame frame, int count) {
        final int sourcePosition = getSourcePosition(frame);

        if (tooFewArgumentsProfile.profile(sourcePosition + count > getSourceLength(frame))) {
            throw new TooFewArgumentsException();
        }

        setSourcePosition(frame, sourcePosition + count);

        return sourcePosition;
    }

    protected int advanceSourcePositionNoThrow(VirtualFrame frame) {
        return advanceSourcePositionNoThrow(frame, 1, false);
    }

    protected int advanceSourcePositionNoThrow(VirtualFrame frame, int count, boolean consumePartial) {
        final int sourcePosition = getSourcePosition(frame);

        final int sourceLength = getSourceLength(frame);

        if (sourceRangeProfile.profile(sourcePosition + count > sourceLength)) {
            if (consumePartial) {
                setSourcePosition(frame, sourceLength);
            }

            return -1;
        }

        setSourcePosition(frame, sourcePosition + count);

        return sourcePosition;
    }

    protected Object getOutput(VirtualFrame frame) {
        try {
            return frame.getObject(FormatFrameDescriptor.OUTPUT_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void setOutput(VirtualFrame frame, Object output) {
        frame.setObject(FormatFrameDescriptor.OUTPUT_SLOT, output);
    }

    protected int getOutputPosition(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.OUTPUT_POSITION_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void setOutputPosition(VirtualFrame frame, int position) {
        frame.setInt(FormatFrameDescriptor.OUTPUT_POSITION_SLOT, position);
    }

    protected int getStringLength(VirtualFrame frame) {
        try {
            return frame.getInt(FormatFrameDescriptor.STRING_LENGTH_SLOT);
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void setStringLength(VirtualFrame frame, int length) {
        frame.setInt(FormatFrameDescriptor.STRING_LENGTH_SLOT, length);
    }

    protected void increaseStringLength(VirtualFrame frame, int additionalLength) {
        setStringLength(frame, getStringLength(frame) + additionalLength);
    }

    protected void setStringCodeRange(VirtualFrame frame, CodeRange codeRange) {
        try {
            final int existingCodeRange = frame.getInt(FormatFrameDescriptor.STRING_CODE_RANGE_SLOT);

            if (codeRangeIncreasedProfile.profile(codeRange.toInt() > existingCodeRange)) {
                frame.setInt(FormatFrameDescriptor.STRING_CODE_RANGE_SLOT, codeRange.toInt());
            }
        } catch (FrameSlotTypeException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void writeByte(VirtualFrame frame, byte value) {
        final byte[] output = ensureCapacity(frame, 1);
        final int outputPosition = getOutputPosition(frame);
        output[outputPosition] = value;
        setOutputPosition(frame, outputPosition + 1);
        increaseStringLength(frame, 1);
    }

    protected void writeBytes(VirtualFrame frame, byte... values) {
        writeBytes(frame, values, values.length);
    }

    protected void writeBytes(VirtualFrame frame, byte[] values, int valuesLength) {
        byte[] output = ensureCapacity(frame, valuesLength);
        final int outputPosition = getOutputPosition(frame);
        System.arraycopy(values, 0, output, outputPosition, valuesLength);
        setOutputPosition(frame, outputPosition + valuesLength);
        increaseStringLength(frame, valuesLength);
    }

    protected void writeBytes(VirtualFrame frame, Bytes values) {
        byte[] output = ensureCapacity(frame, values.length);
        final int outputPosition = getOutputPosition(frame);
        System.arraycopy(values.array, values.offset, output, outputPosition, values.length);
        setOutputPosition(frame, outputPosition + values.length);
        increaseStringLength(frame, values.length);
    }

    protected void writeNullBytes(VirtualFrame frame, int length) {
        if (writeMoreThanZeroBytes.profile(length > 0)) {
            ensureCapacity(frame, length);
            final int outputPosition = getOutputPosition(frame);
            setOutputPosition(frame, outputPosition + length);
            increaseStringLength(frame, length);
        }
    }

    private byte[] ensureCapacity(VirtualFrame frame, int length) {
        byte[] output = (byte[]) getOutput(frame);
        final int outputPosition = getOutputPosition(frame);

        if (outputPosition + length > output.length) {
            // If we ran out of output byte[], deoptimize and next time we'll allocate more

            CompilerDirectives.transferToInterpreterAndInvalidate();
            output = Arrays.copyOf(output, ArrayUtils.capacity(getLanguage(), output.length, outputPosition + length));
            setOutput(frame, output);
        }

        return output;
    }

    private static final Class<? extends ByteBuffer> HEAP_BYTE_BUFFER_CLASS = ByteBuffer
            .wrap(RopeConstants.EMPTY_BYTES.array, RopeConstants.EMPTY_BYTES.offset, RopeConstants.EMPTY_BYTES.length)
            .getClass();

    public ByteBuffer wrapByteBuffer(VirtualFrame frame, byte[] source) {
        final int position = getSourcePosition(frame);
        final int length = getSourceLength(frame);
        return CompilerDirectives
                .castExact(wrapByteBuffer(source, position, length - position), HEAP_BYTE_BUFFER_CLASS);
    }

    @TruffleBoundary
    private static ByteBuffer wrapByteBuffer(byte[] source, int position, int length) {
        return ByteBuffer.wrap(source, position, length);
    }

    @TruffleBoundary
    public static int safeGet(ByteBuffer encode) {
        while (encode.hasRemaining()) {
            int got = encode.get() & 0xff;

            if (got != 0) {
                return got;
            }
        }

        return 0;
    }

}
