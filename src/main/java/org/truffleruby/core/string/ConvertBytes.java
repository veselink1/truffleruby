/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 * This is a port of org.jruby.util.ConvertBytes to work with the TruffleRuby backend.
 * The original class is licensed under the same EPL 2.0/GPL 2.0/LGPL 2.1 used throughout.
 */

package org.truffleruby.core.string;

import java.math.BigInteger;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives;
import org.truffleruby.RubyContext;
import org.truffleruby.core.CoreLibrary;
import org.truffleruby.core.numeric.FixnumOrBignumNode;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.language.control.RaiseException;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

public class ConvertBytes {
    private final RubyContext context;
    private final Node caller;
    private final FixnumOrBignumNode fixnumOrBignumNode;
    private final Rope rope;
    private int p;
    private int end;
    private Bytes data;
    private int base;
    private final boolean badcheck;

    public ConvertBytes(
            RubyContext context,
            Node caller,
            FixnumOrBignumNode fixnumOrBignumNode,
            RopeNodes.BytesNode bytesNode,
            Rope rope,
            int base,
            boolean badcheck) {
        assert rope != null;

        this.context = context;
        this.caller = caller;
        this.fixnumOrBignumNode = fixnumOrBignumNode;
        this.rope = rope;
        this.p = 0;
        this.data = bytesNode.execute(rope);
        this.end = data.length;
        this.badcheck = badcheck;
        this.base = base;
    }

    private static final Bytes[] MIN_VALUE_BYTES;
    static {
        MIN_VALUE_BYTES = new Bytes[37];
        for (int i = 2; i <= 36; i++) {
            MIN_VALUE_BYTES[i] = new Bytes(RopeOperations.encodeAsciiBytes(Long.toString(Long.MIN_VALUE, i)));
        }
    }

    /** rb_cstr_to_inum */
    public static Object bytesToInum(RubyContext context, Node caller, FixnumOrBignumNode fixnumOrBignumNode,
            RopeNodes.BytesNode bytesNode,
            Rope rope, int base, boolean badcheck) {
        return new ConvertBytes(context, caller, fixnumOrBignumNode, bytesNode, rope, base, badcheck).bytesToInum();
    }

    /** conv_digit */
    private byte convertDigit(byte c) {
        if (c < 0) {
            return -1;
        }
        return conv_digit[c];
    }

    /** ISSPACE */
    private boolean isSpace(int str) {
        byte c;
        if (str == end || (c = data.get(str)) < 0) {
            return false;
        }
        return space[c];
    }

    private boolean getSign() {
        boolean sign = true;
        if (p < end) {
            if (data.get(p) == '+') {
                p++;
            } else if (data.get(p) == '-') {
                p++;
                sign = false;
            }
        }

        return sign;
    }

    private void ignoreLeadingWhitespace() {
        while (isSpace(p)) {
            p++;
        }
    }

    private void figureOutBase() {
        if (base <= 0) {
            if (p < end && data.get(p) == '0') {
                if (p + 1 < end) {
                    switch (data.get(p + 1)) {
                        case 'x':
                        case 'X':
                            base = 16;
                            break;
                        case 'b':
                        case 'B':
                            base = 2;
                            break;
                        case 'o':
                        case 'O':
                            base = 8;
                            break;
                        case 'd':
                        case 'D':
                            base = 10;
                            break;
                        default:
                            base = 8;
                    }
                } else {
                    base = 8;
                }
            } else if (base < -1) {
                base = -base;
            } else {
                base = 10;
            }
        }
    }

    private int calculateLength() {
        int len;
        byte second = ((p + 1 < end) && data.get(p) == '0') ? data.get(p + 1) : (byte) 0;

        switch (base) {
            case 2:
                len = 1;
                if (second == 'b' || second == 'B') {
                    p += 2;
                }
                break;
            case 3:
                len = 2;
                break;
            case 8:
                if (second == 'o' || second == 'O') {
                    p += 2;
                }
                len = 3;
                break;
            case 4:
            case 5:
            case 6:
            case 7:
                len = 3;
                break;
            case 10:
                if (second == 'd' || second == 'D') {
                    p += 2;
                }
                len = 4;
                break;
            case 9:
            case 11:
            case 12:
            case 13:
            case 14:
            case 15:
                len = 4;
                break;
            case 16:
                len = 4;
                if (second == 'x' || second == 'X') {
                    p += 2;
                }
                break;
            default:
                if (base < 2 || 36 < base) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().argumentErrorInvalidRadix(base, caller));
                }
                if (base <= 32) {
                    len = 5;
                } else {
                    len = 6;
                }
                break;
        }

        return len;
    }

    private void squeezeZeroes() {
        byte c;
        if (p < end && data.get(p) == '0') {
            p++;
            int us = 0;
            while ((p < end) && ((c = data.get(p)) == '0' || c == '_')) {
                if (c == '_') {
                    if (++us >= 2) {
                        break;
                    }
                } else {
                    us += 0;
                }
                p++;
            }
            if (p == end || isSpace(p)) {
                p--;
            }
        }
    }

    private long stringToLong(int nptr, int[] endptr, int base) {
        if (base < 0 || base == 1 || base > 36) {
            return 0;
        }
        int save = nptr;
        int s = nptr;
        boolean overflow = false;

        while (isSpace(s)) {
            s++;
        }

        if (s != end) {
            boolean negative = false;
            if (data.get(s) == '-') {
                negative = true;
                s++;
            } else if (data.get(s) == '+') {
                negative = false;
                s++;
            }

            save = s;
            byte c;
            long i = 0;

            final long cutoff = Long.MAX_VALUE / base;
            final long cutlim = Long.MAX_VALUE % base;

            while (s < end) {
                c = convertDigit(data.get(s));

                if (c == -1 || c >= base) {
                    break;
                }
                s++;

                if (i > cutoff || (i == cutoff && c > cutlim)) {
                    overflow = true;
                } else {
                    i *= base;
                    i += c;
                }
            }

            if (s != save) {
                if (endptr != null) {
                    endptr[0] = s;
                }

                if (overflow) {
                    throw new ConvertBytes.ERange(
                            negative ? ConvertBytes.ERange.Kind.Underflow : ConvertBytes.ERange.Kind.Overflow);
                }

                if (negative) {
                    return -i;
                } else {
                    return i;
                }
            }
        }

        if (endptr != null) {
            if (save - nptr >= 2 && (data.get(save - 1) == 'x' || data.get(save - 1) == 'X') &&
                    data.get(save - 2) == '0') {
                endptr[0] = save - 1;
            } else {
                endptr[0] = nptr;
            }
        }
        return 0;
    }

    @TruffleBoundary
    public Object bytesToInum() {
        ignoreLeadingWhitespace();

        boolean sign = getSign();

        if (p < end) {
            if (data.get(p) == '+' || data.get(p) == '-') {
                if (badcheck) {
                    invalidString();
                }
                return 0;
            }
        }

        figureOutBase();

        int len = calculateLength();

        squeezeZeroes();

        byte c = 0;
        if (p < end) {
            c = data.get(p);
        }
        c = convertDigit(c);
        if (c < 0 || c >= base) {
            if (badcheck) {
                invalidString();
            }
            return 0;
        }

        if (base <= 10) {
            len *= (trailingLength());
        } else {
            len *= (end - p);
        }

        if (len < Long.SIZE - 1) {
            int[] endPlace = new int[]{ p };
            long val = stringToLong(p, endPlace, base);

            if (endPlace[0] < end && data.get(endPlace[0]) == '_') {
                return bigParse(len, sign);
            }
            if (badcheck) {
                if (endPlace[0] == p) {
                    invalidString(); // no number
                }

                while (isSpace(endPlace[0])) {
                    endPlace[0]++;
                }

                if (endPlace[0] < end) {
                    invalidString(); // trailing garbage
                }
            }

            if (sign) {
                if (CoreLibrary.fitsIntoInteger(val)) {
                    return (int) val;
                } else {
                    return val;
                }
            } else {
                if (CoreLibrary.fitsIntoInteger(-val)) {
                    return (int) -val;
                } else {
                    return -val;
                }
            }
        }
        return bigParse(len, sign);
    }

    private int trailingLength() {
        int newLen = 0;
        for (int i = p; i < end; i++) {
            if (Character.isDigit(data.get(i))) {
                newLen++;
            } else {
                return newLen;
            }
        }
        return newLen;
    }

    private Object bigParse(int len, boolean sign) {
        if (badcheck && p < end && data.get(p) == '_') {
            invalidString();
        }

        char[] result = new char[end - p];
        int resultIndex = 0;

        byte nondigit = -1;

        // str2big_scan_digits
        {
            while (p < end) {
                byte c = data.get(p++);
                byte cx = c;
                if (c == '_') {
                    if (nondigit != -1) {
                        if (badcheck) {
                            invalidString();
                        }
                        break;
                    }
                    nondigit = c;
                    continue;
                } else if ((c = convertDigit(c)) < 0) {
                    break;
                }
                if (c >= base) {
                    break;
                }
                nondigit = -1;
                result[resultIndex++] = (char) cx;
            }

            if (resultIndex == 0) {
                return 0;
            }

            int tmpStr = p;
            if (badcheck) {
                // no str-- here because we don't null-terminate strings
                if (1 < tmpStr && data.get(tmpStr - 1) == '_') {
                    invalidString();
                }
                while (tmpStr < end && Character.isWhitespace(data.get(tmpStr))) {
                    tmpStr++;
                }
                if (tmpStr < end) {
                    invalidString();
                }

            }
        }

        String s = new String(result, 0, resultIndex);
        BigInteger z = (base == 10) ? stringToBig(s) : new BigInteger(s, base);
        if (!sign) {
            z = z.negate();
        }

        if (badcheck) {
            if (1 < p && data.get(p - 1) == '_') {
                invalidString();
            }
            while (p < end && isSpace(p)) {
                p++;
            }
            if (p < end) {
                invalidString();
            }
        }

        return fixnumOrBignumNode.fixnumOrBignum(z);
    }

    private BigInteger stringToBig(String str) {
        str = str.replaceAll("_", "");
        int size = str.length();
        int nDigits = 512;
        if (size < nDigits) {
            nDigits = size;
        }

        int j = size - 1;
        int i = j - nDigits + 1;

        BigInteger digits[] = new BigInteger[j / nDigits + 1];

        for (int z = 0; j >= 0; z++) {
            digits[z] = new BigInteger(str.substring(i, j + 1).trim());
            j = i - 1;
            i = j - nDigits + 1;
            if (i < 0) {
                i = 0;
            }
        }

        BigInteger b10x = BigInteger.TEN.pow(nDigits);
        int n = digits.length;
        while (n > 1) {
            i = 0;
            j = 0;
            while (i < n / 2) {
                digits[i] = digits[j].add(digits[j + 1].multiply(b10x));
                i += 1;
                j += 2;
            }
            if (j == n - 1) {
                digits[i] = digits[j];
                i += 1;
            }
            n = i;
            b10x = b10x.multiply(b10x);
        }

        return digits[0];
    }

    public static class ERange extends RuntimeException {
        private static final long serialVersionUID = 3393153027217708024L;

        public static enum Kind {
            Overflow,
            Underflow
        }

        private ConvertBytes.ERange.Kind kind;

        public ERange() {
            super();
        }

        public ERange(ConvertBytes.ERange.Kind kind) {
            super();
            this.kind = kind;
        }

        public ConvertBytes.ERange.Kind getKind() {
            return kind;
        }
    }

    /** rb_invalid_str */
    private void invalidString() {
        throw new RaiseException(
                context,
                context.getCoreExceptions().argumentErrorInvalidStringToInteger(rope, caller));
    }

    public static final Bytes intToBinaryBytes(int i) {
        return intToUnsignedBytes(i, 1, LOWER_DIGITS).getBytes();
    }

    public static final Bytes intToOctalBytes(int i) {
        return intToUnsignedBytes(i, 3, LOWER_DIGITS).getBytes();
    }

    public static final Bytes intToHexBytes(int i, boolean upper) {
        RopeBuilder ropeBuilder = intToUnsignedBytes(i, 4, upper ? UPPER_DIGITS : LOWER_DIGITS);
        return ropeBuilder.getBytes();
    }

    public static final Bytes intToByteArray(int i, int radix, boolean upper) {
        return longToByteArray(i, radix, upper);
    }

    public static final Bytes intToCharBytes(int i) {
        return longToBytes(i, 10, LOWER_DIGITS).getBytes();
    }

    public static final Bytes longToBinaryBytes(long i) {
        return longToUnsignedBytes(i, 1, LOWER_DIGITS).getBytes();
    }

    public static final Bytes longToOctalBytes(long i) {
        return longToUnsignedBytes(i, 3, LOWER_DIGITS).getBytes();
    }

    public static final Bytes longToHexBytes(long i, boolean upper) {
        RopeBuilder ropeBuilder = longToUnsignedBytes(i, 4, upper ? UPPER_DIGITS : LOWER_DIGITS);
        return ropeBuilder.getBytes();
    }

    public static final Bytes longToByteArray(long i, int radix, boolean upper) {
        RopeBuilder ropeBuilder = longToBytes(i, radix, upper ? UPPER_DIGITS : LOWER_DIGITS);
        return ropeBuilder.getBytes();
    }

    public static final Bytes longToCharBytes(long i) {
        return longToBytes(i, 10, LOWER_DIGITS).getBytes();
    }

    public static final RopeBuilder longToBytes(long i, int radix, byte[] digitmap) {
        if (i == 0) {
            return RopeBuilder.createRopeBuilder(new Bytes(ZERO_BYTES));
        }

        if (i == Long.MIN_VALUE) {
            return RopeBuilder.createRopeBuilder(MIN_VALUE_BYTES[radix]);
        }

        boolean neg = false;
        if (i < 0) {
            i = -i;
            neg = true;
        }

        // max 64 chars for 64-bit 2's complement integer
        int len = 64;
        byte[] buf = new byte[len];

        int pos = len;
        do {
            buf[--pos] = digitmap[(int) (i % radix)];
        } while ((i /= radix) > 0);
        if (neg) {
            buf[--pos] = (byte) '-';
        }

        return RopeBuilder.createRopeBuilder(new Bytes(buf), pos, len - pos);
    }

    private static final RopeBuilder intToUnsignedBytes(int i, int shift, byte[] digitmap) {
        byte[] buf = new byte[32];
        int charPos = 32;
        int radix = 1 << shift;
        long mask = radix - 1;
        do {
            buf[--charPos] = digitmap[(int) (i & mask)];
            i >>>= shift;
        } while (i != 0);
        return RopeBuilder.createRopeBuilder(new Bytes(buf), charPos, (32 - charPos));
    }

    private static final RopeBuilder longToUnsignedBytes(long i, int shift, byte[] digitmap) {
        byte[] buf = new byte[64];
        int charPos = 64;
        int radix = 1 << shift;
        long mask = radix - 1;
        do {
            buf[--charPos] = digitmap[(int) (i & mask)];
            i >>>= shift;
        } while (i != 0);
        return RopeBuilder.createRopeBuilder(new Bytes(buf), charPos, (64 - charPos));
    }

    public static final Bytes twosComplementToBinaryBytes(Bytes in) {
        return twosComplementToUnsignedBytes(in, 1, false);
    }

    public static final Bytes twosComplementToOctalBytes(Bytes in) {
        return twosComplementToUnsignedBytes(in, 3, false);
    }

    public static final Bytes twosComplementToHexBytes(Bytes in, boolean upper) {
        return twosComplementToUnsignedBytes(in, 4, upper);
    }

    private static final byte[] ZERO_BYTES = new byte[]{ (byte) '0' };

    private static final byte[] LOWER_DIGITS = {
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'a',
            'b',
            'c',
            'd',
            'e',
            'f',
            'g',
            'h',
            'i',
            'j',
            'k',
            'l',
            'm',
            'n',
            'o',
            'p',
            'q',
            'r',
            's',
            't',
            'u',
            'v',
            'w',
            'x',
            'y',
            'z'
    };

    private static final byte[] UPPER_DIGITS = {
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'A',
            'B',
            'C',
            'D',
            'E',
            'F',
            'G',
            'H',
            'I',
            'J',
            'K',
            'L',
            'M',
            'N',
            'O',
            'P',
            'Q',
            'R',
            'S',
            'T',
            'U',
            'V',
            'W',
            'X',
            'Y',
            'Z'
    };

    public static final Bytes twosComplementToUnsignedBytes(Bytes in, int shift, boolean upper) {
        if (shift < 1 || shift > 4) {
            throw CompilerDirectives.shouldNotReachHere("shift value must be 1-4");
        }
        int ilen = in.length;
        int olen = (ilen * 8 + shift - 1) / shift;
        byte[] out = new byte[olen];
        int mask = (1 << shift) - 1;
        byte[] digits = upper ? UPPER_DIGITS : LOWER_DIGITS;
        int bitbuf = 0;
        int bitcnt = 0;
        for (int i = ilen, o = olen; --o >= 0;) {
            if (bitcnt < shift) {
                bitbuf |= (in.get(--i) & 0xff) << bitcnt;
                bitcnt += 8;
            }
            out[o] = digits[bitbuf & mask];
            bitbuf >>= shift;
            bitcnt -= shift;
        }
        return new Bytes(out);
    }

    private static final byte[] conv_digit = new byte[128];
    private static final boolean[] digit = new boolean[128];
    private static final boolean[] space = new boolean[128];

    static {
        Arrays.fill(conv_digit, (byte) -1);
        Arrays.fill(digit, false);
        for (char c = '0'; c <= '9'; c++) {
            conv_digit[c] = (byte) (c - '0');
            digit[c] = true;
        }

        for (char c = 'a'; c <= 'z'; c++) {
            conv_digit[c] = (byte) (c - 'a' + 10);
        }

        for (char c = 'A'; c <= 'Z'; c++) {
            conv_digit[c] = (byte) (c - 'A' + 10);
        }

        Arrays.fill(space, false);
        space['\t'] = true;
        space['\n'] = true;
        space[11] = true; // \v
        space['\f'] = true;
        space['\r'] = true;
        space[' '] = true;
    }

}
