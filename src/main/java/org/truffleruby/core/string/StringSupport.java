/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.core.string;

import static org.truffleruby.core.rope.CodeRange.CR_7BIT;
import static org.truffleruby.core.rope.CodeRange.CR_BROKEN;
import static org.truffleruby.core.rope.CodeRange.CR_UNKNOWN;
import static org.truffleruby.core.rope.CodeRange.CR_VALID;

import com.oracle.truffle.api.nodes.Node;
import org.graalvm.collections.Pair;
import org.jcodings.Config;
import org.jcodings.Encoding;
import org.jcodings.IntHolder;
import org.jcodings.ascii.AsciiTables;
import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jcodings.util.IntHash;
import org.truffleruby.RubyContext;
import org.truffleruby.collections.IntHashMap;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeOperations;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.utils.Utils;

public final class StringSupport {
    public static final int TRANS_SIZE = 256;

    // We don't know how many characters the case map operation will produce and it requires a non-exposed
    // minimum buffer size for its internal operations. We create a buffer larger than expected to avoid
    // exceeding the buffer size.
    private static final int CASE_MAP_BUFFER_SIZE = 32;

    public static int characterLength(Encoding encoding, CodeRange codeRange, Bytes bytes,
            int byteOffset, int byteEnd, boolean recoverIfBroken) {
        assert byteOffset >= 0 && byteOffset < byteEnd && byteEnd <= bytes.length;

        switch (codeRange) {
            case CR_7BIT:
                return 1;
            case CR_VALID:
                return characterLengthValid(encoding, bytes, byteOffset, byteEnd);
            case CR_BROKEN:
            case CR_UNKNOWN:
                if (recoverIfBroken) {
                    return length(encoding, bytes, byteOffset, byteEnd);
                } else {
                    return preciseLength(encoding, bytes, byteOffset, byteEnd);
                }
            default:
                throw Utils.unsupportedOperation("unknown code range value: ", codeRange);
        }
    }

    public static int characterLength(Encoding encoding, CodeRange codeRange, Bytes bytes, int byteOffset,
            int byteEnd) {
        return characterLength(encoding, codeRange, bytes, byteOffset, byteEnd, false);
    }

    private static int characterLengthValid(Encoding encoding, Bytes bytes, int byteOffset, int byteEnd) {
        if (encoding.isUTF8()) {
            return UTF8Operations.charWidth(bytes.get(byteOffset));
        } else if (encoding.isAsciiCompatible()) {
            if (bytes.get(byteOffset) >= 0) {
                return 1;
            } else {
                return encLength(encoding, bytes, byteOffset, byteEnd);
            }
        } else if (encoding.isFixedWidth()) {
            final int width = encoding.minLength();
            assert (byteEnd - byteOffset) >= width;
            return width;
        } else {
            return encLength(encoding, bytes, byteOffset, byteEnd);
        }
    }


    /** This method returns the byte length of the first encountered character in `bytes`, starting at offset `p` and
     * ending at byte position `e`. The `Encoding` implementation will perform character validation and return a
     * negative number if the byte sequence does not correspond to a valid character. Otherwise, the byte length of the
     * character is returned. See the docs for `Encoding#length` for more details.
     *
     * It is up to the caller to check if the return value is negative. In practice, it is expected this method is only
     * called when the caller knows the code range of the byte sequence is either `CR_7BIT` or `CR_VALID`, in which case
     * no check on the return values is necessary -- it will always be positive.
     *
     * Corresponding MRI method: rb_enc_fast_mbclen */
    @TruffleBoundary
    private static int encLength(Encoding enc, Bytes bytes, int p, int e) {
        return enc.length(bytes.array, bytes.offset + p, bytes.offset + e);
    }

    /** This method functions like `StringSupport.encLength`, but differs when an invalid character is encountered
     * (i.e., a negative byte length). In such cases, it attempts to perform a limited form of error recovery. It checks
     * the `Encoding`'s minimum length to see if it's small enough to fit within the range [end - p]. If it is, the
     * method pretends a character with a byte length equal to the `Encoding`'s minimum length was discovered and that
     * byte length is returned. If the minimum character length can't fit in the range, this method pretends a character
     * with a byte length corresponding to the size of the range was encountered and the range size is returned. If a
     * valid character was encountered, its byte length is returned just as would be the case with
     * `StringSupport.encLength`. Consequently, this method never returns a negative value.
     *
     * Ruby allows Strings with a `CR_BROKEN` code range to propagate through to an end user, who may call methods on
     * that String. This variant of getting a character's length is designed is intended to be used in such cases. E.g.,
     * if calling `String#each_char` on a String that is `CR_BROKEN`, returning negative values for the character length
     * would break iteration. In such cases, Ruby just pretends broken byte sequences have some arbitrary, but
     * deterministic, positive byte length.
     *
     * Corresponding MRI method: rb_enc_mbclen */
    public static int length(Encoding enc, Bytes bytes, int p, int end) {
        int n = encLength(enc, bytes, p, end);
        if (MBCLEN_CHARFOUND_P(n) && MBCLEN_CHARFOUND_LEN(n) <= end - p) {
            return MBCLEN_CHARFOUND_LEN(n);
        }
        int min = enc.minLength();
        return min <= end - p ? min : end - p;
    }

    /** This method functions like `StringSupport.encLength`, but differs when a character sequence is too short. In
     * such cases, it examines the return value from `Encoding#length` and if exceeds the length of the byte sequence,
     * it returns the number of bytes required to make the character valid, but negated. Since the value is negated, the
     * caller can distinguish between good character lengths ad bad ones by checking the sign of the value.
     *
     * It is intended to be called when then code range of the byte sequence is unknown. In such cases, it cannot be
     * trusted like `StringSupport.encLength`. Nor is it safe to recover invalid byte sequences as is done with
     * `StringSupport.length`.
     *
     * Corresponding MRI method: rb_enc_precise_mbclen */
    private static int preciseLength(Encoding enc, Bytes bytes, int p, int end) {
        if (p >= end) {
            return MBCLEN_NEEDMORE(1);
        }
        int n = encLength(enc, bytes, p, end);
        if (n > end - p) {
            return MBCLEN_NEEDMORE(n - (end - p));
        }
        return n;
    }

    // MBCLEN_NEEDMORE_P, ONIGENC_MBCLEN_NEEDMORE_P
    public static boolean MBCLEN_NEEDMORE_P(int r) {
        return r < -1;
    }

    // MBCLEN_NEEDMORE_LEN, ONIGENC_MBCLEN_NEEDMORE_LEN
    public static int MBCLEN_NEEDMORE_LEN(int r) {
        return -1 - r;
    }

    // MBCLEN_NEEDMORE, ONIGENC_MBCLEN_NEEDMORE
    public static int MBCLEN_NEEDMORE(int n) {
        return -1 - n;
    }

    // MBCLEN_INVALID_P, ONIGENC_MBCLEN_INVALID_P
    public static boolean MBCLEN_INVALID_P(int r) {
        return r == -1;
    }

    // MBCLEN_CHARFOUND_LEN, ONIGENC_MBCLEN_CHARFOUND_LEN
    public static int MBCLEN_CHARFOUND_LEN(int r) {
        return r;
    }

    // MBCLEN_CHARFOUND_P, ONIGENC_MBCLEN_CHARFOUND_P
    public static boolean MBCLEN_CHARFOUND_P(int r) {
        return 0 < r;
    }

    // MRI: search_nonascii
    public static int searchNonAscii(Bytes bytes, int p, int end) {
        while (p < end) {
            if (!Encoding.isAscii(bytes.get(p))) {
                return p;
            }
            p++;
        }
        return -1;
    }

    // MRI: search_nonascii
    public static int searchNonAscii(Bytes bytes) {
        for (int p = 0; p < bytes.length; ++p) {
            if (!Encoding.isAscii(bytes.get(p))) {
                return p;
            }
        }
        return -1;
    }

    // MRI: rb_enc_strlen
    public static int strLength(Encoding enc, Bytes bytes, int p, int end) {
        return strLength(enc, bytes, p, end, CR_UNKNOWN);
    }

    // MRI: enc_strlen
    @TruffleBoundary
    public static int strLength(Encoding enc, Bytes bytes, int p, int e, CodeRange cr) {
        int c;
        if (enc.isFixedWidth()) {
            return (e - p + enc.minLength() - 1) / enc.minLength();
        } else if (enc.isAsciiCompatible()) {
            c = 0;
            if (cr == CR_7BIT || cr == CR_VALID) {
                while (p < e) {
                    if (Encoding.isAscii(bytes.get(p))) {
                        int q = searchNonAscii(bytes, p, e);
                        if (q == -1) {
                            return c + (e - p);
                        }
                        c += q - p;
                        p = q;
                    }
                    p += characterLength(enc, cr, bytes, p, e);
                    c++;
                }
            } else {
                while (p < e) {
                    if (Encoding.isAscii(bytes.get(p))) {
                        int q = searchNonAscii(bytes, p, e);
                        if (q == -1) {
                            return c + (e - p);
                        }
                        c += q - p;
                        p = q;
                    }
                    p += characterLength(enc, cr, bytes, p, e, true);
                    c++;
                }
            }
            return c;
        }

        for (c = 0; p < e; c++) {
            p += characterLength(enc, cr, bytes, p, e, true);
        }
        return c;
    }

    public static StringAttributes strLengthWithCodeRangeAsciiCompatible(Encoding enc, Bytes bytes, int p, int end) {
        CodeRange cr = CR_UNKNOWN;
        int c = 0;
        while (p < end) {
            if (Encoding.isAscii(bytes.get(p))) {
                int q = searchNonAscii(bytes, p, end);
                if (q == -1) {
                    return new StringAttributes(c + (end - p), cr == CR_UNKNOWN ? CR_7BIT : cr);
                }
                c += q - p;
                p = q;
            }
            int cl = preciseLength(enc, bytes, p, end);
            if (cl > 0) {
                if (cr != CR_BROKEN) {
                    cr = CR_VALID;
                }
                p += cl;
            } else {
                cr = CR_BROKEN;
                p++;
            }
            c++;
        }
        return new StringAttributes(c, cr == CR_UNKNOWN ? CR_7BIT : cr);
    }

    public static StringAttributes strLengthWithCodeRangeNonAsciiCompatible(Encoding enc, Bytes bytes, int p,
            int end) {
        CodeRange cr = CR_UNKNOWN;
        int c;
        for (c = 0; p < end; c++) {
            int cl = preciseLength(enc, bytes, p, end);
            if (cl > 0) {
                if (cr != CR_BROKEN) {
                    cr = CR_VALID;
                }
                p += cl;
            } else {
                cr = CR_BROKEN;
                p++;
            }
        }

        return new StringAttributes(c, cr == CR_UNKNOWN ? CR_7BIT : cr);
    }

    @TruffleBoundary
    public static int codePoint(Encoding enc, CodeRange codeRange, Bytes bytes, int p, int end, Node node) {
        if (p >= end) {
            final RubyContext context = RubyContext.get(node);
            throw new RaiseException(context, context.getCoreExceptions().argumentError("empty string", node));
        }
        int cl = characterLength(enc, codeRange, bytes, p, end);
        if (cl <= 0) {
            final RubyContext context = RubyContext.get(node);
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().argumentError("invalid byte sequence in " + enc, node));
        }
        return enc.mbcToCode(bytes.array, bytes.offset + p, bytes.offset + end);
    }

    /** Returns a negative value for invalid code points, callers should check for that unless they can guarantee the
     * code point is valid. */
    @TruffleBoundary
    public static int codeLength(Encoding enc, int c) {
        return enc.codeToMbcLength(c);
    }

    @TruffleBoundary
    public static int codeToMbc(Encoding encoding, int code, Bytes bytes, int p) {
        return encoding.codeToMbc(code, bytes.array, bytes.offset + p);
    }

    @TruffleBoundary
    public static int preciseCodePoint(Encoding enc, CodeRange codeRange, Bytes bytes, int p, int end) {
        int l = characterLength(enc, codeRange, bytes, p, end);
        if (l > 0) {
            return enc.mbcToCode(bytes.array, bytes.offset + p, bytes.offset + end);
        }
        return -1;
    }

    @TruffleBoundary
    public static int mbcToCode(Encoding encoding, Rope rope, int p, int end) {
        return encoding.mbcToCode(rope.getBytes().array, rope.getBytes().offset + p, rope.getBytes().offset + end);
    }

    public static int offset(int start, int end, int charEnd) {
        return charEnd == -1 ? end - start : Math.min(end, charEnd) - start;
    }

    public static int caseCmp(Bytes bytes1, int p1, Bytes bytes2, int p2, int len) {
        int i = -1;
        for (; ++i < len && bytes1.get(p1 + i) == bytes2.get(p2 + i);) {
        }
        if (i < len) {
            return (bytes1.get(p1 + i) & 0xff) > (bytes2.get(p2 + i) & 0xff) ? 1 : -1;
        }
        return 0;
    }

    public static int scanHex(Bytes bytes, int p, int len) {
        return scanHex(bytes, p, len, ASCIIEncoding.INSTANCE);
    }

    @TruffleBoundary
    public static int scanHex(Bytes bytes, int p, int len, Encoding enc) {
        int v = 0;
        int c;
        while (len-- > 0 && enc.isXDigit(c = bytes.get(p++) & 0xff)) {
            v = (v << 4) + enc.xdigitVal(c);
        }
        return v;
    }

    public static int hexLength(Bytes bytes, int p, int len) {
        return hexLength(bytes, p, len, ASCIIEncoding.INSTANCE);
    }

    @TruffleBoundary
    public static int hexLength(Bytes bytes, int p, int len, Encoding enc) {
        int hlen = 0;
        while (len-- > 0 && enc.isXDigit(bytes.get(p++) & 0xff)) {
            hlen++;
        }
        return hlen;
    }

    public static int scanOct(Bytes bytes, int p, int len) {
        return scanOct(bytes, p, len, ASCIIEncoding.INSTANCE);
    }

    @TruffleBoundary
    public static int scanOct(Bytes bytes, int p, int len, Encoding enc) {
        int v = 0;
        int c;
        while (len-- > 0 && enc.isDigit(c = bytes.get(p++) & 0xff) && c < '8') {
            v = (v << 3) + Encoding.digitVal(c);
        }
        return v;
    }

    public static int octLength(Bytes bytes, int p, int len) {
        return octLength(bytes, p, len, ASCIIEncoding.INSTANCE);
    }

    @TruffleBoundary
    public static int octLength(Bytes bytes, int p, int len, Encoding enc) {
        int olen = 0;
        int c;
        while (len-- > 0 && enc.isDigit(c = bytes.get(p++) & 0xff) && c < '8') {
            olen++;
        }
        return olen;
    }

    public static String escapedCharFormat(int c, boolean isUnicode) {
        String format;
        // c comparisons must be unsigned 32-bit
        if (isUnicode) {

            if ((c & 0xFFFFFFFFL) < 0x7F && Encoding.isAscii(c) && ASCIIEncoding.INSTANCE.isPrint(c)) {
                format = "%c";
            } else if (c < 0x10000) {
                format = "\\u%04X";
            } else {
                format = "\\u{%X}";
            }
        } else {
            if ((c & 0xFFFFFFFFL) < 0x100) {
                format = "\\x%02X";
            } else {
                format = "\\x{%X}";
            }
        }
        return format;
    }

    /** rb_str_count */
    @TruffleBoundary
    public static int strCount(Rope str, boolean[] table, TrTables tables, Encoding enc, Node node) {
        final Bytes bytes = str.getBytes();
        int p = 0;
        final int end = str.byteLength();
        final boolean asciiCompat = enc.isAsciiCompatible();

        int count = 0;
        while (p < end) {
            int c;
            if (asciiCompat && (c = bytes.get(p) & 0xff) < 0x80) {
                if (table[c]) {
                    count++;
                }
                p++;
            } else {
                c = codePoint(enc, str.getCodeRange(), bytes, p, end, node);
                int cl = codeLength(enc, c);
                if (trFind(c, table, tables)) {
                    count++;
                }
                p += cl;
            }
        }

        return count;
    }

    /** rb_str_tr / rb_str_tr_bang */
    public static final class TR {
        public TR(Rope bytes) {
            p = 0;
            pend = bytes.byteLength() + p;
            buf = bytes.getBytes();
            now = max = 0;
            gen = false;
        }

        final Bytes buf;
        int p, pend, now, max;
        boolean gen;
    }

    /** tr_setup_table */
    public static final class TrTables {
        IntHashMap<Object> del, noDel; // used as ~ Set
    }

    private static final Object DUMMY_VALUE = "";

    @TruffleBoundary
    public static TrTables trSetupTable(Rope str, boolean[] stable, TrTables tables, boolean first, Encoding enc,
            Node node) {
        int i, l[] = { 0 };
        final boolean cflag;

        final TR tr = new TR(str);

        if (str.byteLength() > 1 && EncodingUtils.encAscget(tr.buf, tr.p, tr.pend, l, enc, str.getCodeRange()) == '^') {
            cflag = true;
            tr.p += l[0];
        } else {
            cflag = false;
        }

        if (first) {
            for (i = 0; i < TRANS_SIZE; i++) {
                stable[i] = true;
            }
            stable[TRANS_SIZE] = cflag;
        } else if (stable[TRANS_SIZE] && !cflag) {
            stable[TRANS_SIZE] = false;
        }

        if (tables == null) {
            tables = new TrTables();
        }

        Bytes buf = null; // lazy initialized
        IntHashMap<Object> table = null, ptable = null;

        int c;
        while ((c = trNext(tr, enc, str.getCodeRange(), node)) != -1) {
            if (c < TRANS_SIZE) {
                if (buf == null) { // initialize buf
                    buf = new Bytes(TRANS_SIZE);
                    for (i = 0; i < TRANS_SIZE; i++) {
                        buf.set(i, (byte) (cflag ? 1 : 0));
                    }
                }
                // update the buff at [c] :
                buf.set(c & 0xff, (byte) (cflag ? 0 : 1));
            } else {
                if (table == null && (first || tables.del != null || stable[TRANS_SIZE])) {
                    if (cflag) {
                        ptable = tables.noDel;
                        table = ptable != null ? ptable : new IntHashMap<>(8);
                        tables.noDel = table;
                    } else {
                        table = new IntHashMap<>(8);
                        ptable = tables.del;
                        tables.del = table;
                    }
                }

                if (table != null) {
                    final int key = c;
                    if (ptable == null) {
                        table.put(key, DUMMY_VALUE);
                    } else {
                        if (cflag) {
                            table.put(key, DUMMY_VALUE);
                        } else {
                            final boolean val = ptable.get(key) != null;
                            table.put(key, val ? DUMMY_VALUE : null);
                        }
                    }
                }
            }
        }
        if (buf != null) {
            for (i = 0; i < TRANS_SIZE; i++) {
                stable[i] = stable[i] && buf.get(i) != 0;
            }
        } else {
            for (i = 0; i < TRANS_SIZE; i++) {
                stable[i] = stable[i] && cflag;
            }
        }

        if (table == null && !cflag) {
            tables.del = null;
        }

        return tables;
    }

    public static boolean trFind(final int c, final boolean[] table, final TrTables tables) {
        if (c < TRANS_SIZE) {
            return table[c];
        }

        final IntHashMap<Object> del = tables.del, noDel = tables.noDel;

        if (del != null) {
            if (del.get(c) != null &&
                    (noDel == null || noDel.get(c) == null)) {
                return true;
            }
        } else if (noDel != null && noDel.get(c) != null) {
            return false;
        }

        return table[TRANS_SIZE];
    }

    @TruffleBoundary
    public static int trNext(TR tr, Encoding enc, CodeRange codeRange, Node node) {
        for (;;) {
            if (!tr.gen) {
                return trNext_nextpart(tr, enc, codeRange, node);
            }

            while (enc.codeToMbcLength(++tr.now) <= 0) {
                if (tr.now == tr.max) {
                    tr.gen = false;
                    return trNext_nextpart(tr, enc, codeRange, node);
                }
            }
            if (tr.now < tr.max) {
                return tr.now;
            } else {
                tr.gen = false;
                return tr.max;
            }
        }
    }

    private static int trNext_nextpart(TR tr, Encoding enc, CodeRange codeRange, Node node) {
        final int[] n = { 0 };

        if (tr.p == tr.pend) {
            return -1;
        }
        if (EncodingUtils.encAscget(tr.buf, tr.p, tr.pend, n, enc, codeRange) == '\\' && tr.p + n[0] < tr.pend) {
            tr.p += n[0];
        }
        tr.now = EncodingUtils.encCodepointLength(tr.buf, tr.p, tr.pend, n, enc, codeRange, node);
        tr.p += n[0];
        if (EncodingUtils.encAscget(tr.buf, tr.p, tr.pend, n, enc, codeRange) == '-' && tr.p + n[0] < tr.pend) {
            tr.p += n[0];
            if (tr.p < tr.pend) {
                int c = EncodingUtils.encCodepointLength(tr.buf, tr.p, tr.pend, n, enc, codeRange, node);
                tr.p += n[0];
                if (tr.now > c) {
                    final RubyContext context = RubyContext.get(node);
                    if (tr.now < 0x80 && c < 0x80) {
                        final String message = "invalid range \"" + (char) tr.now + '-' + (char) c +
                                "\" in string transliteration";
                        throw new RaiseException(context, context.getCoreExceptions().argumentError(message, node));
                    }

                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().argumentError("invalid range in string transliteration", node));
                }
                tr.gen = true;
                tr.max = c;
            }
        }
        return tr.now;
    }

    public enum NeighborChar {
        NOT_CHAR,
        FOUND,
        WRAPPED
    }

    // MRI: str_succ
    @TruffleBoundary
    public static RopeBuilder succCommon(Rope original, Node node) {
        Bytes carry = new Bytes(org.jcodings.Config.ENC_CODE_TO_MBC_MAXLEN);
        int carryP = 0;
        carry.set(0, (byte) 1);
        int carryLen = 1;

        Encoding enc = original.getEncoding();
        RopeBuilder valueCopy = RopeBuilder.createRopeBuilder(original.getBytes(), enc);
        int p = 0;
        int end = p + valueCopy.getLength();
        int s = end;
        Bytes bytes = valueCopy.getUnsafeBytes();

        NeighborChar neighbor = NeighborChar.FOUND;
        int lastAlnum = -1;
        boolean alnumSeen = false;
        while ((s = enc.prevCharHead(bytes.array, bytes.offset + p, bytes.offset + s, bytes.offset + end)) != -1) {
            if (neighbor == NeighborChar.NOT_CHAR && lastAlnum != -1) {
                ASCIIEncoding ascii = ASCIIEncoding.INSTANCE;
                if (ascii.isAlpha(bytes.get(lastAlnum) & 0xff)
                        ? ascii.isDigit(bytes.get(s) & 0xff)
                        : ascii.isDigit(bytes.get(lastAlnum) & 0xff) ? ascii.isAlpha(bytes.get(s) & 0xff) : false) {
                    s = lastAlnum;
                    break;
                }
            }

            int cl = characterLength(enc, CR_UNKNOWN, bytes, s, end);
            if (cl <= 0) {
                continue;
            }
            switch (neighbor = succAlnumChar(enc, bytes, s, cl, carry, 0, node)) {
                case NOT_CHAR:
                    continue;
                case FOUND:
                    return valueCopy;
                case WRAPPED:
                    lastAlnum = s;
            }
            alnumSeen = true;
            carryP = s - p;
            carryLen = cl;
        }

        if (!alnumSeen) {
            s = end;
            while ((s = enc.prevCharHead(bytes.array, bytes.offset + p, bytes.offset + s, bytes.offset + end)) != -1) {
                int cl = characterLength(enc, CR_UNKNOWN, bytes, s, end);
                if (cl <= 0) {
                    continue;
                }
                neighbor = succChar(enc, bytes, s, cl, node);
                if (neighbor == NeighborChar.FOUND) {
                    return valueCopy;
                }
                if (characterLength(enc, CR_UNKNOWN, bytes, s, s + 1) != cl) {
                    succChar(enc, bytes, s, cl, node); /* wrapped to \0...\0. search next valid char. */
                }
                if (!enc.isAsciiCompatible()) {
                    Bytes.copy(bytes, s, carry, 0, cl);
                    carryLen = cl;
                }
                carryP = s - p;
            }
        }
        valueCopy.unsafeEnsureSpace(valueCopy.getLength() + carryLen);
        s = carryP;
        Bytes.copy(
                valueCopy.getUnsafeBytes(),
                s,
                valueCopy.getUnsafeBytes(),
                s + carryLen,
                valueCopy.getLength() - carryP);
        Bytes.copy(carry, 0, valueCopy.getUnsafeBytes(), s, carryLen);
        valueCopy.setLength(valueCopy.getLength() + carryLen);
        return valueCopy;
    }

    // MRI: enc_succ_char
    public static NeighborChar succChar(Encoding enc, Bytes bytes, int p, int len, Node node) {
        int l;
        if (enc.minLength() > 1) {
            /* wchar, trivial case */
            int r = characterLength(enc, CR_UNKNOWN, bytes, p, p + len), c;
            if (!MBCLEN_CHARFOUND_P(r)) {
                return NeighborChar.NOT_CHAR;
            }
            c = codePoint(enc, CR_UNKNOWN, bytes, p, p + len, node) + 1;
            l = codeLength(enc, c);
            if (l == 0) {
                return NeighborChar.NOT_CHAR;
            }
            if (l != len) {
                return NeighborChar.WRAPPED;
            }
            EncodingUtils.encMbcput(c, bytes, p, enc);
            r = characterLength(enc, CR_UNKNOWN, bytes, p, p + len);
            if (!MBCLEN_CHARFOUND_P(r)) {
                return NeighborChar.NOT_CHAR;
            }
            return NeighborChar.FOUND;
        }

        while (true) {
            int i = len - 1;
            for (; i >= 0 && bytes.get(p + i) == (byte) 0xff; i--) {
                bytes.set(p + i, (byte) 0);
            }
            if (i < 0) {
                return NeighborChar.WRAPPED;
            }
            bytes.set(p + i, (byte) ((bytes.get(p + i) & 0xff) + 1));
            l = characterLength(enc, CR_UNKNOWN, bytes, p, p + len);
            if (MBCLEN_CHARFOUND_P(l)) {
                l = MBCLEN_CHARFOUND_LEN(l);
                if (l == len) {
                    return NeighborChar.FOUND;
                } else {
                    int start = p + l;
                    int end = start + (len - l);
                    Bytes.fill(bytes, start, end, (byte) 0xff);
                }
            }
            if (MBCLEN_INVALID_P(l) && i < len - 1) {
                int len2;
                int l2;
                for (len2 = len - 1; 0 < len2; len2--) {
                    l2 = characterLength(enc, CR_UNKNOWN, bytes, p, p + len2);
                    if (!MBCLEN_INVALID_P(l2)) {
                        break;
                    }
                }
                int start = p + len2 + 1;
                int end = start + len - (len2 + 1);
                Bytes.fill(bytes, start, end, (byte) 0xff);
            }
        }
    }

    // MRI: enc_succ_alnum_char
    private static NeighborChar succAlnumChar(Encoding enc, Bytes bytes, int p, int len, Bytes carry, int carryP,
            Node node) {
        Bytes save = new Bytes(org.jcodings.Config.ENC_CODE_TO_MBC_MAXLEN);
        int c = enc.mbcToCode(bytes.array, bytes.offset + p, bytes.offset + p + len);

        final int cType;
        if (enc.isDigit(c)) {
            cType = CharacterType.DIGIT;
        } else if (enc.isAlpha(c)) {
            cType = CharacterType.ALPHA;
        } else {
            return NeighborChar.NOT_CHAR;
        }

        Bytes.copy(bytes, p, save, 0, len);
        NeighborChar ret = succChar(enc, bytes, p, len, node);
        if (ret == NeighborChar.FOUND) {
            c = enc.mbcToCode(bytes.array, bytes.offset + p, bytes.offset + p + len);
            if (enc.isCodeCType(c, cType)) {
                return NeighborChar.FOUND;
            }
        }

        Bytes.copy(save, 0, bytes, p, len);
        int range = 1;

        while (true) {
            Bytes.copy(bytes, p, save, 0, len);
            ret = predChar(enc, bytes, p, len, node);
            if (ret == NeighborChar.FOUND) {
                c = enc.mbcToCode(bytes.array, bytes.offset + p, bytes.offset + p + len);
                if (!enc.isCodeCType(c, cType)) {
                    Bytes.copy(save, 0, bytes, p, len);
                    break;
                }
            } else {
                Bytes.copy(save, 0, bytes, p, len);
                break;
            }
            range++;
        }

        if (range == 1) {
            return NeighborChar.NOT_CHAR;
        }

        if (cType != CharacterType.DIGIT) {
            Bytes.copy(bytes, p, carry, carryP, len);
            return NeighborChar.WRAPPED;
        }

        Bytes.copy(bytes, p, carry, carryP, len);
        succChar(enc, carry, carryP, len, node);
        return NeighborChar.WRAPPED;
    }

    private static NeighborChar predChar(Encoding enc, Bytes bytes, int p, int len, Node node) {
        int l;
        if (enc.minLength() > 1) {
            /* wchar, trivial case */
            int r = characterLength(enc, CR_UNKNOWN, bytes, p, p + len), c;
            if (!MBCLEN_CHARFOUND_P(r)) {
                return NeighborChar.NOT_CHAR;
            }
            c = codePoint(enc, CR_UNKNOWN, bytes, p, p + len, node);
            if (c == 0) {
                return NeighborChar.NOT_CHAR;
            }
            --c;
            l = codeLength(enc, c);
            if (l == 0) {
                return NeighborChar.NOT_CHAR;
            }
            if (l != len) {
                return NeighborChar.WRAPPED;
            }
            EncodingUtils.encMbcput(c, bytes, p, enc);
            r = characterLength(enc, CR_UNKNOWN, bytes, p, p + len);
            if (!MBCLEN_CHARFOUND_P(r)) {
                return NeighborChar.NOT_CHAR;
            }
            return NeighborChar.FOUND;
        }
        while (true) {
            int i = len - 1;
            for (; i >= 0 && bytes.get(p + i) == 0; i--) {
                bytes.set(p + i, (byte) 0xff);
            }
            if (i < 0) {
                return NeighborChar.WRAPPED;
            }
            bytes.set(p + i, (byte) ((bytes.get(p + i) & 0xff) - 1));
            l = characterLength(enc, CR_UNKNOWN, bytes, p, p + len);
            if (MBCLEN_CHARFOUND_P(l)) {
                l = MBCLEN_CHARFOUND_LEN(l);
                if (l == len) {
                    return NeighborChar.FOUND;
                } else {
                    int start = p + l;
                    int end = start + (len - l);
                    Bytes.fill(bytes, start, end, (byte) 0x0);
                }
            }
            if (!MBCLEN_CHARFOUND_P(l) && i < len - 1) {
                int len2;
                int l2;
                for (len2 = len - 1; 0 < len2; len2--) {
                    l2 = characterLength(enc, CR_UNKNOWN, bytes, p, p + len2);
                    if (!MBCLEN_INVALID_P(l2)) {
                        break;
                    }
                }
                int start = p + len2 + 1;
                int end = start + (len - (len2 + 1));
                Bytes.fill(bytes, start, end, (byte) 0);
            }
        }
    }

    /** rb_str_delete_bang */
    @TruffleBoundary
    public static Rope delete_bangCommon19(Rope rubyString, boolean[] squeeze, TrTables tables, Encoding enc,
            Node node) {
        int s = 0;
        int t = s;
        int send = s + rubyString.byteLength();
        Bytes bytes = rubyString.getBytesCopy();
        boolean modified = false;
        boolean asciiCompatible = enc.isAsciiCompatible();
        CodeRange cr = asciiCompatible ? CR_7BIT : CR_VALID;
        while (s < send) {
            int c;
            if (asciiCompatible && Encoding.isAscii(c = bytes.get(s) & 0xff)) {
                if (squeeze[c]) {
                    modified = true;
                } else {
                    if (t != s) {
                        bytes.set(t, (byte) c);
                    }
                    t++;
                }
                s++;
            } else {
                c = codePoint(enc, rubyString.getCodeRange(), bytes, s, send, node);
                int cl = codeLength(enc, c);
                if (trFind(c, squeeze, tables)) {
                    modified = true;
                } else {
                    if (t != s) {
                        enc.codeToMbc(c, bytes.array, bytes.offset + t);
                    }
                    t += cl;
                    if (cr == CR_7BIT) {
                        cr = CR_VALID;
                    }
                }
                s += cl;
            }
        }

        return modified ? RopeOperations.create(Bytes.extractRange(bytes, 0, t), enc, cr) : null;
    }

    /** rb_str_tr / rb_str_tr_bang */

    private static CodeRange CHECK_IF_ASCII(int c, CodeRange currentCodeRange) {
        if (currentCodeRange == CR_7BIT && !Encoding.isAscii(c)) {
            return CR_VALID;
        }

        return currentCodeRange;
    }

    @TruffleBoundary
    public static Rope trTransHelper(Rope self, Rope srcStr, Rope replStr, Encoding e1, Encoding enc, boolean sflag,
            Node node) {
        // This method does not handle the cases where either srcStr or replStr are empty.  It is the responsibility
        // of the caller to take the appropriate action in those cases.

        CodeRange cr = self.getCodeRange();

        final StringSupport.TR trSrc = new StringSupport.TR(srcStr);
        boolean cflag = false;
        int[] l = { 0 };

        if (srcStr.byteLength() > 1 &&
                EncodingUtils.encAscget(trSrc.buf, trSrc.p, trSrc.pend, l, enc, srcStr.getCodeRange()) == '^' &&
                trSrc.p + 1 < trSrc.pend) {
            cflag = true;
            trSrc.p++;
        }

        int c, c0, last = 0;
        final int[] trans = new int[StringSupport.TRANS_SIZE];
        final StringSupport.TR trRepl = new StringSupport.TR(replStr);
        boolean modified = false;
        IntHash<Integer> hash = null;
        boolean singlebyte = self.isSingleByteOptimizable();

        if (cflag) {
            for (int i = 0; i < StringSupport.TRANS_SIZE; i++) {
                trans[i] = 1;
            }

            while ((c = StringSupport.trNext(trSrc, enc, srcStr.getCodeRange(), node)) != -1) {
                if (c < StringSupport.TRANS_SIZE) {
                    trans[c] = -1;
                } else {
                    if (hash == null) {
                        hash = new IntHash<>();
                    }
                    hash.put(c, 1); // QTRUE
                }
            }
            while ((c = StringSupport.trNext(trRepl, enc, replStr.getCodeRange(), node)) != -1) {
                /* retrieve last replacer */
            }
            last = trRepl.now;
            for (int i = 0; i < StringSupport.TRANS_SIZE; i++) {
                if (trans[i] != -1) {
                    trans[i] = last;
                }
            }
        } else {
            for (int i = 0; i < StringSupport.TRANS_SIZE; i++) {
                trans[i] = -1;
            }

            while ((c = StringSupport.trNext(trSrc, enc, srcStr.getCodeRange(), node)) != -1) {
                int r = StringSupport.trNext(trRepl, enc, replStr.getCodeRange(), node);
                if (r == -1) {
                    r = trRepl.now;
                }
                if (c < StringSupport.TRANS_SIZE) {
                    trans[c] = r;
                    if (codeLength(enc, r) != 1) {
                        singlebyte = false;
                    }
                } else {
                    if (hash == null) {
                        hash = new IntHash<>();
                    }
                    hash.put(c, r);
                }
            }
        }

        if (cr == CR_VALID && enc.isAsciiCompatible()) {
            cr = CR_7BIT;
        }

        int s = 0;
        int send = self.byteLength();

        final Rope ret;
        if (sflag) {
            Bytes sbytes = self.getBytes();
            int clen, tlen;
            int max = self.byteLength();
            int save = -1;
            Bytes buf = new Bytes(max);
            int t = 0;
            while (s < send) {
                boolean mayModify = false;
                c0 = c = codePoint(e1, CR_UNKNOWN, sbytes, s, send, node);
                clen = codeLength(e1, c);
                tlen = enc == e1 ? clen : codeLength(enc, c);
                s += clen;

                if (c < TRANS_SIZE) {
                    c = trCode(c, trans, hash, cflag, last, false);
                } else if (hash != null) {
                    Integer tmp = hash.get(c);
                    if (tmp == null) {
                        if (cflag) {
                            c = last;
                        } else {
                            c = -1;
                        }
                    } else if (cflag) {
                        c = -1;
                    } else {
                        c = tmp;
                    }
                } else {
                    c = -1;
                }

                if (c != -1) {
                    if (save == c) {
                        cr = CHECK_IF_ASCII(c, cr);
                        continue;
                    }
                    save = c;
                    tlen = codeLength(enc, c);
                    modified = true;
                } else {
                    save = -1;
                    c = c0;
                    if (enc != e1) {
                        mayModify = true;
                    }
                }

                while (t + tlen >= max) {
                    max *= 2;
                    buf = Bytes.copyOf(buf, max);
                }
                enc.codeToMbc(c, buf.array, buf.offset + t);
                // MRI does not check s < send again because their null terminator can still be compared
                if (mayModify && (s >= send || !ArrayUtils.regionEquals(sbytes, s, buf, t, tlen))) {
                    modified = true;
                }
                cr = CHECK_IF_ASCII(c, cr);
                t += tlen;
            }

            ret = RopeOperations.create(Bytes.extractRange(buf, 0, t), enc, cr);
        } else if (enc.isSingleByte() || (singlebyte && hash == null)) {
            Bytes sbytes = self.getBytesCopy();
            while (s < send) {
                c = sbytes.get(s) & 0xff;
                if (trans[c] != -1) {
                    if (!cflag) {
                        c = trans[c];
                        sbytes.set(s, (byte) c);
                    } else {
                        sbytes.set(s, (byte) last);
                    }
                    modified = true;
                }
                cr = CHECK_IF_ASCII(c, cr);
                s++;
            }

            ret = RopeOperations.create(sbytes, enc, cr);
        } else {
            Bytes sbytes = self.getBytes();
            int clen, tlen, max = (int) (self.byteLength() * 1.2);
            Bytes buf = new Bytes(max);
            int t = 0;

            while (s < send) {
                boolean mayModify = false;
                c0 = c = codePoint(e1, CR_UNKNOWN, sbytes, s, send, node);
                clen = codeLength(e1, c);
                tlen = enc == e1 ? clen : codeLength(enc, c);

                if (c < TRANS_SIZE) {
                    c = trans[c];
                } else if (hash != null) {
                    Integer tmp = hash.get(c);
                    if (tmp == null) {
                        if (cflag) {
                            c = last;
                        } else {
                            c = -1;
                        }
                    } else if (cflag) {
                        c = -1;
                    } else {
                        c = tmp;
                    }
                } else {
                    c = cflag ? last : -1;
                }
                if (c != -1) {
                    tlen = codeLength(enc, c);
                    modified = true;
                } else {
                    c = c0;
                    if (enc != e1) {
                        mayModify = true;
                    }
                }
                while (t + tlen >= max) {
                    max <<= 1;
                    buf = Bytes.copyOf(buf, max);
                }
                // headius: I don't see how s and t could ever be the same, since they refer to different buffers
                //                if (s != t) {
                enc.codeToMbc(c, buf.array, buf.offset + t);
                if (mayModify && !ArrayUtils.regionEquals(sbytes, s, buf, t, tlen)) {
                    modified = true;
                }
                //                }

                cr = CHECK_IF_ASCII(c, cr);
                s += clen;
                t += tlen;
            }

            ret = RopeOperations.create(Bytes.extractRange(buf, 0, t), enc, cr);
        }

        if (modified) {
            return ret;
        }

        return null;
    }

    private static int trCode(int c, int[] trans, IntHash<Integer> hash, boolean cflag, int last, boolean set) {
        if (c < StringSupport.TRANS_SIZE) {
            return trans[c];
        } else if (hash != null) {
            Integer tmp = hash.get(c);
            if (tmp == null) {
                return cflag ? last : -1;
            } else {
                return cflag ? -1 : tmp;
            }
        } else {
            return cflag && set ? last : -1;
        }
    }

    @TruffleBoundary
    public static int multiByteCasecmp(Encoding enc, Rope value, Rope otherValue) {
        Bytes bytes = value.getBytes();
        int p = 0;
        int end = value.byteLength();

        Bytes obytes = otherValue.getBytes();
        int op = 0;
        int oend = otherValue.byteLength();

        while (p < end && op < oend) {
            final int c, oc;
            if (enc.isAsciiCompatible()) {
                c = bytes.get(p) & 0xff;
                oc = obytes.get(op) & 0xff;
            } else {
                c = preciseCodePoint(enc, value.getCodeRange(), bytes, p, end);
                oc = preciseCodePoint(enc, otherValue.getCodeRange(), obytes, op, oend);
            }

            int cl, ocl;
            if (enc.isAsciiCompatible() && Encoding.isAscii(c) && Encoding.isAscii(oc)) {
                byte uc = AsciiTables.ToUpperCaseTable[c];
                byte uoc = AsciiTables.ToUpperCaseTable[oc];
                if (uc != uoc) {
                    return uc < uoc ? -1 : 1;
                }
                cl = ocl = 1;
            } else {
                cl = characterLength(
                        enc,
                        enc == value.getEncoding() ? value.getCodeRange() : CR_UNKNOWN,
                        bytes,
                        p,
                        end,
                        true);
                ocl = characterLength(
                        enc,
                        enc == otherValue.getEncoding() ? otherValue.getCodeRange() : CR_UNKNOWN,
                        obytes,
                        op,
                        oend,
                        true);
                // TODO: opt for 2 and 3 ?
                int ret = caseCmp(bytes, p, obytes, op, cl < ocl ? cl : ocl);
                if (ret != 0) {
                    return ret < 0 ? -1 : 1;
                }
                if (cl != ocl) {
                    return cl < ocl ? -1 : 1;
                }
            }

            p += cl;
            op += ocl;
        }
        if (end - p == oend - op) {
            return 0;
        }
        return end - p > oend - op ? 1 : -1;
    }

    public static boolean singleByteSqueeze(RopeBuilder value, boolean squeeze[]) {
        int s = 0;
        int t = s;
        int send = s + value.getLength();
        final Bytes bytes = value.getUnsafeBytes();
        int save = -1;

        while (s < send) {
            int c = bytes.get(s++) & 0xff;
            if (c != save || !squeeze[c]) {
                bytes.set(t++, (byte) (save = c));
            }
        }

        if (t != value.getLength()) { // modified
            value.setLength(t);
            return true;
        }

        return false;
    }

    @TruffleBoundary
    public static boolean multiByteSqueeze(RopeBuilder value, CodeRange originalCodeRange, boolean[] squeeze,
            TrTables tables, Encoding enc, boolean isArg, Node node) {
        int s = 0;
        int t = s;
        int send = s + value.getLength();
        Bytes bytes = value.getUnsafeBytes();
        int save = -1;
        int c;

        while (s < send) {
            if (enc.isAsciiCompatible() && (c = bytes.get(s) & 0xff) < 0x80) {
                if (c != save || (isArg && !squeeze[c])) {
                    bytes.set(t++, (byte) (save = c));
                }
                s++;
            } else {
                c = codePoint(enc, originalCodeRange, bytes, s, send, node);
                int cl = codeLength(enc, c);
                if (c != save || (isArg && !trFind(c, squeeze, tables))) {
                    if (t != s) {
                        enc.codeToMbc(c, bytes.array, bytes.offset + t);
                    }
                    save = c;
                    t += cl;
                }
                s += cl;
            }
        }

        if (t != value.getLength()) { // modified
            value.setLength(t);
            return true;
        }

        return false;
    }

    // region Case Mapping Methods

    @TruffleBoundary
    private static int caseMapChar(int codePoint, Encoding enc, Bytes stringBytes, int stringByteOffset,
            RopeBuilder builder, IntHolder flags, Bytes workBuffer) {
        final IntHolder fromP = new IntHolder();
        fromP.value = stringBytes.offset + stringByteOffset;

        final int clen = enc.codeToMbcLength(codePoint);

        final int newByteLength = enc
                .caseMap(
                        flags,
                        stringBytes.array,
                        fromP,
                        fromP.value + clen,
                        workBuffer.array,
                        workBuffer.offset,
                        workBuffer.end());

        if (clen == newByteLength) {
            Bytes.copy(workBuffer, 0, stringBytes, stringByteOffset, newByteLength);
        } else {
            final int tailLength = stringBytes.length - (stringByteOffset + clen);
            final int newBufferLength = stringByteOffset + newByteLength + tailLength;
            final Bytes newBuffer = Bytes.copyOf(stringBytes, newBufferLength);

            Bytes.copy(workBuffer, 0, newBuffer, stringByteOffset, newByteLength);
            Bytes.copy(
                    stringBytes,
                    stringByteOffset + clen,
                    newBuffer,
                    stringByteOffset + newByteLength,
                    tailLength);

            builder.unsafeReplace(newBuffer, newBufferLength);
        }

        return newByteLength;
    }

    /** Returns a copy of {@code bytes} but with ASCII characters' case swapped, or {@code bytes} itself if the string
     * doesn't require changes. The encoding must be ASCII-compatible (i.e. represent each ASCII character as a single
     * byte ({@link Encoding#isAsciiCompatible()}). */
    @TruffleBoundary
    public static Bytes swapcaseMultiByteAsciiSimple(Encoding enc, CodeRange codeRange, Bytes bytes) {
        assert enc.isAsciiCompatible();
        boolean modified = false;
        int s = 0;
        final int end = bytes.length;

        while (s < end) {
            if (isAsciiAlpha(bytes.get(s))) {
                if (!modified) {
                    bytes = bytes.clone();
                    modified = true;
                }
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                s++;
            } else {
                s += characterLength(enc, codeRange, bytes, s, end);
            }
        }

        return bytes;
    }

    @TruffleBoundary
    public static boolean swapCaseMultiByteComplex(Encoding enc, CodeRange originalCodeRange, RopeBuilder builder,
            int caseMappingOptions, Node node) {
        Bytes buf = new Bytes(CASE_MAP_BUFFER_SIZE);

        final IntHolder flagP = new IntHolder();
        flagP.value = caseMappingOptions | Config.CASE_UPCASE | Config.CASE_DOWNCASE;

        boolean modified = false;
        int s = 0;
        Bytes bytes = builder.getUnsafeBytes();

        while (s < bytes.length) {
            int c = codePoint(enc, originalCodeRange, bytes, s, bytes.length, node);
            if (enc.isUpper(c) || enc.isLower(c)) {
                s += caseMapChar(c, enc, bytes, s, builder, flagP, buf);
                modified = true;

                if (bytes != builder.getUnsafeBytes()) {
                    bytes = builder.getUnsafeBytes();
                }
            } else {
                s += codeLength(enc, c);
            }
        }

        return modified;
    }

    /** Returns a copy of {@code bytes} but with ASCII characters downcased, or {@code bytes} itself if no ASCII
     * characters need upcasing. The encoding must be ASCII-compatible (i.e. represent each ASCII character as a single
     * byte ({@link Encoding#isAsciiCompatible()}). */
    @TruffleBoundary
    public static Bytes downcaseMultiByteAsciiSimple(Encoding enc, CodeRange codeRange, Bytes bytes) {
        assert enc.isAsciiCompatible();
        boolean modified = false;
        int s = 0;
        final int end = bytes.length;

        while (s < end) {
            if (isAsciiUppercase(bytes.get(s))) {
                if (!modified) {
                    bytes = bytes.clone();
                    modified = true;
                }
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                s++;
            } else {
                s += characterLength(enc, codeRange, bytes, s, end);
            }
        }

        return bytes;
    }

    @TruffleBoundary
    public static boolean downcaseMultiByteComplex(Encoding enc, CodeRange originalCodeRange, RopeBuilder builder,
            int caseMappingOptions, Node node) {
        Bytes buf = new Bytes(CASE_MAP_BUFFER_SIZE);

        final IntHolder flagP = new IntHolder();
        flagP.value = caseMappingOptions | Config.CASE_DOWNCASE;

        final boolean isFold = (caseMappingOptions & Config.CASE_FOLD) != 0;
        final boolean isTurkic = (caseMappingOptions & Config.CASE_FOLD_TURKISH_AZERI) != 0;

        boolean modified = false;
        int s = 0;
        Bytes bytes = builder.getUnsafeBytes();

        while (s < bytes.length) {
            if (!isTurkic && enc.isAsciiCompatible() && isAsciiUppercase(bytes.get(s))) {
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                modified = true;
                s++;
            } else {
                final int c = codePoint(enc, originalCodeRange, bytes, s, bytes.length, node);

                if (isFold || enc.isUpper(c)) {
                    s += caseMapChar(c, enc, bytes, s, builder, flagP, buf);
                    modified = true;

                    if (bytes != builder.getUnsafeBytes()) {
                        bytes = builder.getUnsafeBytes();
                    }
                } else {
                    s += codeLength(enc, c);
                }
            }
        }

        return modified;
    }

    /** Returns a copy of {@code bytes} but with ASCII characters upcased, or {@code bytes} itself if no ASCII
     * characters need upcasing. The encoding must be ASCII-compatible (i.e. represent each ASCII character as a single
     * byte ( {@link Encoding#isAsciiCompatible()}). */
    @TruffleBoundary
    public static Bytes upcaseMultiByteAsciiSimple(Encoding enc, CodeRange codeRange, Bytes bytes) {
        assert enc.isAsciiCompatible();
        boolean modified = false;
        int s = 0;
        final int end = bytes.length;

        while (s < end) {
            if (isAsciiLowercase(bytes.get(s))) {
                if (!modified) {
                    bytes = bytes.clone();
                    modified = true;
                }
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                s++;
            } else {
                s += characterLength(enc, codeRange, bytes, s, end);
            }
        }

        return bytes;
    }

    @TruffleBoundary
    public static boolean upcaseMultiByteComplex(Encoding enc, CodeRange originalCodeRange, RopeBuilder builder,
            int caseMappingOptions, Node node) {
        Bytes buf = new Bytes(CASE_MAP_BUFFER_SIZE);

        final IntHolder flagP = new IntHolder();
        flagP.value = caseMappingOptions | Config.CASE_UPCASE;

        final boolean isTurkic = (caseMappingOptions & Config.CASE_FOLD_TURKISH_AZERI) != 0;
        boolean modified = false;
        int s = 0;
        Bytes bytes = builder.getUnsafeBytes();

        while (s < bytes.length) {
            if (!isTurkic && enc.isAsciiCompatible() && isAsciiLowercase(bytes.get(s))) {
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                modified = true;
                s++;
            } else {
                final int c = codePoint(enc, originalCodeRange, bytes, s, bytes.length, node);

                if (enc.isLower(c)) {
                    s += caseMapChar(c, enc, bytes, s, builder, flagP, buf);
                    modified = true;

                    if (bytes != builder.getUnsafeBytes()) {
                        bytes = builder.getUnsafeBytes();
                    }
                } else {
                    s += codeLength(enc, c);
                }
            }
        }

        return modified;
    }

    /** Returns a copy of {@code bytes} but capitalized (affecting only ASCII characters), or {@code bytes} itself if
     * the string doesn't require changes. The encoding must be ASCII-compatible (i.e. represent each ASCII character as
     * a single byte ({@link Encoding#isAsciiCompatible()}). */
    @TruffleBoundary
    public static Bytes capitalizeMultiByteAsciiSimple(Encoding enc, CodeRange codeRange, Bytes bytes) {
        assert enc.isAsciiCompatible();
        boolean modified = false;
        final int end = bytes.length;

        if (end == 0) {
            return bytes;
        }

        if (StringSupport.isAsciiLowercase(bytes.get(0))) {
            bytes = bytes.clone();
            bytes.set(0, (byte) (bytes.get(0) ^ 0x20));
            modified = true;
        }

        int s = 1;
        while (s < end) {
            if (StringSupport.isAsciiUppercase(bytes.get(s))) {
                if (!modified) {
                    bytes = bytes.clone();
                    modified = true;
                }
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                s++;
            } else {
                s += StringSupport.characterLength(enc, codeRange, bytes, s, end);
            }
        }

        return bytes;
    }

    @TruffleBoundary
    public static boolean capitalizeMultiByteComplex(Encoding enc, CodeRange originalCodeRange, RopeBuilder builder,
            int caseMappingOptions, Node node) {
        Bytes buf = new Bytes(CASE_MAP_BUFFER_SIZE);

        final IntHolder flagP = new IntHolder();
        flagP.value = caseMappingOptions | Config.CASE_UPCASE | Config.CASE_TITLECASE;

        final boolean isTurkic = (caseMappingOptions & Config.CASE_FOLD_TURKISH_AZERI) != 0;
        boolean modified = false;
        int s = 0;
        Bytes bytes = builder.getUnsafeBytes();
        boolean upcasing = true;

        while (s < bytes.length) {
            if (!isTurkic && enc.isAsciiCompatible() &&
                    ((upcasing && isAsciiLowercase(bytes.get(s))) || (!upcasing && isAsciiUppercase(bytes.get(s))))) {
                bytes.set(s, (byte) (bytes.get(s) ^ 0x20));
                modified = true;
                s++;
            } else {
                final int c = codePoint(enc, originalCodeRange, bytes, s, bytes.length, node);

                if ((upcasing && enc.isLower(c)) || (!upcasing && enc.isUpper(c))) {
                    s += caseMapChar(c, enc, bytes, s, builder, flagP, buf);
                    modified = true;

                    if (bytes != builder.getUnsafeBytes()) {
                        bytes = builder.getUnsafeBytes();
                    }
                } else {
                    s += codeLength(enc, c);
                }
            }

            if (upcasing) {
                upcasing = false;
                flagP.value = caseMappingOptions | Config.CASE_DOWNCASE;
            }
        }

        return modified;
    }

    //endregion
    //region Predicates

    public static boolean isAsciiLowercase(byte c) {
        return c >= 'a' && c <= 'z';
    }

    public static boolean isAsciiUppercase(byte c) {
        return c >= 'A' && c <= 'Z';
    }

    /** MRI: ISSPACE() and rb_isspace() True for ' ', \t, \n, \v, \f, and \r */
    public static boolean isAsciiSpace(int c) {
        return c == ' ' || ('\t' <= c && c <= '\r');
    }

    public static boolean isAsciiPrintable(int c) {
        return c == ' ' || (c >= '!' && c <= '~');
    }

    public static boolean isAsciiAlpha(byte c) {
        return isAsciiUppercase(c) || isAsciiLowercase(c);
    }

    @TruffleBoundary
    public static boolean isSpace(Encoding encoding, int c) {
        return encoding.isSpace(c);
    }

    public static boolean isAsciiCodepoint(int value) {
        return value >= 0 && value < 128;
    }

    //endregion
    //region undump helpers

    private static final Bytes FORCE_ENCODING_BYTES = new Bytes(RopeOperations.encodeAsciiBytes(".force_encoding(\""));
    private static final Bytes HEXDIGIT = new Bytes(
            RopeOperations.encodeAsciiBytes("0123456789abcdef0123456789ABCDEF"));
    private static final String INVALID_FORMAT_MESSAGE = "invalid dumped string; not wrapped with '\"' nor '\"...\".force_encoding(\"...\")' form";

    @TruffleBoundary
    public static Pair<RopeBuilder, RubyEncoding> undump(Rope rope, RubyEncoding encoding, RubyContext context,
            Node currentNode) {
        Bytes bytes = rope.getBytes();
        int start = 0;
        int length = bytes.length;
        RubyEncoding resultEncoding = encoding;
        Encoding[] enc = { encoding.jcoding };
        boolean[] utf8 = { false };
        boolean[] binary = { false };
        RopeBuilder undumped = new RopeBuilder();
        undumped.setEncoding(enc[0]);

        CodeRange cr = rope.getCodeRange();
        if (cr != CR_7BIT) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().runtimeError("non-ASCII character detected", currentNode));
        }

        if (Bytes.memchr(bytes, start, bytes.length, (byte) '\0') != -1) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().runtimeError("string contains null byte", currentNode));
        }
        if (length < 2) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
        }
        if (bytes.get(start) != '"') {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
        }
        /* strip '"' at the start */
        start++;

        for (;;) {
            if (start >= length) {
                throw new RaiseException(
                        context,
                        context.getCoreExceptions().runtimeError("unterminated dumped string", currentNode));
            }

            if (bytes.get(start) == '"') {
                /* epilogue */
                start++;
                if (start == length) {
                    /* ascii compatible dumped string */
                    break;
                } else {
                    int size;

                    if (utf8[0]) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(
                                        "dumped string contained Unicode escape but used force_encoding",
                                        currentNode));
                    }

                    size = FORCE_ENCODING_BYTES.length;
                    if (length - start <= size) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
                    }
                    if (!ArrayUtils.regionEquals(bytes, start, FORCE_ENCODING_BYTES, 0, size)) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
                    }
                    start += size;

                    int encname = start;
                    start = Bytes.memchr(bytes, start, length - start, (byte) '"');
                    size = start - encname;
                    if (start == -1) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
                    }
                    if (length - start != 2) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
                    }
                    if (bytes.get(start) != '"' || bytes.get(start + 1) != ')') {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(INVALID_FORMAT_MESSAGE, currentNode));
                    }
                    String encnameString = new String(
                            bytes.array,
                            bytes.offset + encname,
                            size,
                            rope.encoding.getCharset());
                    RubyEncoding enc2 = context.getEncodingManager().getRubyEncoding(encnameString);
                    if (enc2 == null) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError(
                                        "dumped string has unknown encoding name",
                                        currentNode));
                    }
                    undumped.setEncoding(enc2.jcoding);
                    resultEncoding = enc2;
                }
                break;
            }

            if (bytes.get(start) == '\\') {
                start++;
                if (start >= length) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError("invalid escape", currentNode));
                }
                final Pair<Integer, RubyEncoding> undumpAfterBackslashResult = undumpAfterBackslash(
                        undumped,
                        resultEncoding,
                        bytes,
                        start,
                        length,
                        enc,
                        utf8,
                        binary,
                        context,
                        currentNode);
                start = undumpAfterBackslashResult.getLeft();
                resultEncoding = undumpAfterBackslashResult.getRight();
            } else {
                undumped.append(bytes, start++, 1);
            }
        }

        return Pair.create(undumped, resultEncoding);
    }

    private static Pair<Integer, RubyEncoding> undumpAfterBackslash(RopeBuilder out, RubyEncoding encoding,
            Bytes bytes, int start, int length, Encoding[] enc,
            boolean[] utf8, boolean[] binary, RubyContext context, Node currentNode) {
        long c;
        int codelen;
        int[] hexlen = { 0 };
        Bytes buf = new Bytes(6);
        RubyEncoding resultEncoding = encoding;

        switch (bytes.get(start)) {
            case '\\':
            case '"':
            case '#':
                out.append(bytes, start, 1); /* cat itself */
                start++;
                break;
            case 'n':
            case 'r':
            case 't':
            case 'f':
            case 'v':
            case 'b':
            case 'a':
            case 'e':
                buf.set(0, unescapeAscii(bytes.get(start)));
                out.append(buf, 0, 1);
                start++;
                break;
            case 'u':
                if (binary[0]) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError(
                                    "hex escape and Unicode escape are mixed",
                                    currentNode));
                }
                utf8[0] = true;
                if (++start >= length) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError("invalid Unicode escape", currentNode));
                }
                if (enc[0] != UTF8Encoding.INSTANCE) {
                    enc[0] = UTF8Encoding.INSTANCE;
                    out.setEncoding(UTF8Encoding.INSTANCE);
                    resultEncoding = Encodings.UTF_8;
                }
                if (bytes.get(start) == '{') { /* handle u{...} form */
                    start++;
                    for (;;) {
                        if (start >= length) {
                            throw new RaiseException(
                                    context,
                                    context.getCoreExceptions().runtimeError(
                                            "unterminated Unicode escape",
                                            currentNode));
                        }
                        if (bytes.get(start) == '}') {
                            start++;
                            break;
                        }
                        if (Character.isSpaceChar(bytes.get(start))) {
                            start++;
                            continue;
                        }
                        c = scanHex(bytes, start, length - start, hexlen);
                        if (hexlen[0] == 0 || hexlen[0] > 6) {
                            throw new RaiseException(
                                    context,
                                    context.getCoreExceptions().runtimeError("invalid Unicode escape", currentNode));
                        }
                        if (c > 0x10ffff) {
                            throw new RaiseException(
                                    context,
                                    context.getCoreExceptions().runtimeError(
                                            "invalid Unicode codepoint (too large)",
                                            currentNode));
                        }
                        if (0xd800 <= c && c <= 0xdfff) {
                            throw new RaiseException(
                                    context,
                                    context.getCoreExceptions().runtimeError("invalid Unicode codepoint", currentNode));
                        }
                        codelen = EncodingUtils.encMbcput((int) c, buf, 0, enc[0]);
                        out.append(buf, 0, codelen);
                        start += hexlen[0];
                    }
                } else { /* handle uXXXX form */
                    c = scanHex(bytes, start, 4, hexlen);
                    if (hexlen[0] != 4) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError("invalid Unicode escape", currentNode));
                    }
                    if (0xd800 <= c && c <= 0xdfff) {
                        throw new RaiseException(
                                context,
                                context.getCoreExceptions().runtimeError("invalid Unicode codepoint", currentNode));
                    }
                    codelen = EncodingUtils.encMbcput((int) c, buf, 0, enc[0]);
                    out.append(buf, 0, codelen);
                    start += hexlen[0];
                }
                break;
            case 'x':
                if (utf8[0]) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError(
                                    "hex escape and Unicode escape are mixed",
                                    currentNode));
                }
                binary[0] = true;
                if (++start >= length) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError("invalid hex escape", currentNode));
                }
                buf.set(0, (byte) scanHex(bytes, start, 2, hexlen));
                if (hexlen[0] != 2) {
                    throw new RaiseException(
                            context,
                            context.getCoreExceptions().runtimeError("invalid hex escape", currentNode));
                }
                out.append(buf, 0, 1);
                start += hexlen[0];
                break;
            default:
                out.append(bytes, start - 1, 2);
                start++;
        }

        return Pair.create(start, resultEncoding);
    }

    private static long scanHex(Bytes bytes, int start, int len, int[] retlen) {
        int s = start;
        long retval = 0;
        int tmp;

        while ((len--) > 0 && s < bytes.length &&
                (tmp = Bytes.memchr(HEXDIGIT, 0, HEXDIGIT.length, bytes.get(s))) != -1) {
            retval <<= 4;
            retval |= tmp & 15;
            s++;
        }
        retlen[0] = (s - start); /* less than len */
        return retval;
    }

    private static byte unescapeAscii(byte c) {
        switch (c) {
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'f':
                return '\f';
            case 'v':
                return '\13';
            case 'b':
                return '\010';
            case 'a':
                return '\007';
            case 'e':
                return 033;
            default:
                // not reached
                return -1;
        }
    }
}
