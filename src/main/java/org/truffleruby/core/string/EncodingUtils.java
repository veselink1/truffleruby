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

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import org.jcodings.Encoding;
import org.jcodings.ascii.AsciiTables;
import org.jcodings.specific.ASCIIEncoding;
import org.truffleruby.RubyContext;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.language.control.RaiseException;

public class EncodingUtils {

    // rb_enc_asciicompat
    public static boolean encAsciicompat(Encoding enc) {
        return encMbminlen(enc) == 1 && !encDummy(enc);
    }

    // rb_enc_mbminlen
    public static int encMbminlen(Encoding encoding) {
        return encoding.minLength();
    }

    // rb_enc_dummy_p
    public static boolean encDummy(Encoding enc) {
        return enc.isDummy();
    }

    public static boolean DECORATOR_P(Bytes sname, Bytes dname) {
        return sname == null || sname.length == 0 || sname.get(0) == 0;
    }

    public static List<String> encodingNames(Bytes name, int p, int end) {
        final List<String> names = new ArrayList<>();

        Encoding enc = ASCIIEncoding.INSTANCE;
        int s = p;

        int code = name.get(s) & 0xff;
        if (enc.isDigit(code)) {
            return names;
        }

        boolean hasUpper = false;
        boolean hasLower = false;
        if (enc.isUpper(code)) {
            hasUpper = true;
            while (++s < end && (enc.isAlnum(name.get(s) & 0xff) || name.get(s) == (byte) '_')) {
                if (enc.isLower(name.get(s) & 0xff)) {
                    hasLower = true;
                }
            }
        }

        boolean isValid = false;
        if (s >= end) {
            isValid = true;
            names.add(RopeOperations.decodeAscii(name, p, end));
        }

        if (!isValid || hasLower) {
            if (!hasLower || !hasUpper) {
                do {
                    code = name.get(s) & 0xff;
                    if (enc.isLower(code)) {
                        hasLower = true;
                    }
                    if (enc.isUpper(code)) {
                        hasUpper = true;
                    }
                } while (++s < end && (!hasLower || !hasUpper));
            }

            Bytes constName = new Bytes(end - p);
            Bytes.copy(name, p, constName, 0, end - p);
            s = 0;
            code = constName.get(s) & 0xff;

            if (!isValid) {
                if (enc.isLower(code)) {
                    constName.set(s, AsciiTables.ToUpperCaseTable[code]);
                }
                for (; s < constName.length; ++s) {
                    if (!enc.isAlnum(constName.get(s) & 0xff)) {
                        constName.set(s, (byte) '_');
                    }
                }
                if (hasUpper) {
                    names.add(RopeOperations.decodeAscii(constName));
                }
            }
            if (hasLower) {
                for (s = 0; s < constName.length; ++s) {
                    code = constName.get(s) & 0xff;
                    if (enc.isLower(code)) {
                        constName.set(s, AsciiTables.ToUpperCaseTable[code]);
                    }
                }
                names.add(RopeOperations.decodeAscii(constName));
            }
        }

        return names;
    }


    // rb_enc_ascget
    public static int encAscget(Bytes pBytes, int p, int e, int[] len, Encoding enc, CodeRange codeRange) {
        int c;
        int l;

        if (e <= p) {
            return -1;
        }

        if (encAsciicompat(enc)) {
            c = pBytes.get(p) & 0xFF;
            if (!Encoding.isAscii((byte) c)) {
                return -1;
            }
            if (len != null) {
                len[0] = 1;
            }
            return c;
        }
        l = StringSupport.characterLength(enc, codeRange, pBytes, p, e);
        if (!StringSupport.MBCLEN_CHARFOUND_P(l)) {
            return -1;
        }
        c = enc.mbcToCode(pBytes.array, pBytes.offset + p, pBytes.offset + e);
        if (!Encoding.isAscii(c)) {
            return -1;
        }
        if (len != null) {
            len[0] = l;
        }
        return c;
    }

    // rb_enc_codepoint_len
    @TruffleBoundary
    public static int encCodepointLength(Bytes pBytes, int p, int e, int[] len_p, Encoding enc, CodeRange codeRange,
            Node node) {
        int r;
        if (e <= p) {
            final RubyContext context = RubyContext.get(node);
            throw new RaiseException(context, context.getCoreExceptions().argumentError("empty string", node));
        }
        r = StringSupport.characterLength(enc, codeRange, pBytes, p, e);
        if (!StringSupport.MBCLEN_CHARFOUND_P(r)) {
            final RubyContext context = RubyContext.get(node);
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().argumentError("invalid byte sequence in " + enc, node));
        }
        if (len_p != null) {
            len_p[0] = StringSupport.MBCLEN_CHARFOUND_LEN(r);
        }
        return StringSupport.codePoint(enc, codeRange, pBytes, p, e, node);
    }

    // rb_enc_mbcput
    public static int encMbcput(int c, Bytes buf, int p, Encoding enc) {
        return enc.codeToMbc(c, buf.array, buf.offset + p);
    }

}
