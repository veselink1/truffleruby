/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.string;

import org.jcodings.specific.ASCIIEncoding;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import org.truffleruby.core.rope.RopeOperations;

public class CoreString {

    private final RubyLanguage language;
    private final String literal;

    @CompilationFinal private volatile Rope rope;

    public CoreString(RubyLanguage language, String literal) {
        assert language != null;
        assert is7Bit(literal);
        this.language = language;
        this.literal = literal;
    }

    public Rope getRope() {
        if (rope == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            rope = language.ropeCache.getRope(
                    new Bytes(RopeOperations.encodeAsciiBytes(literal)),
                    // Binary because error message Strings have a ASCII-8BIT encoding on MRI.
                    // When used for creating a Symbol, the encoding is adapted as needed.
                    ASCIIEncoding.INSTANCE,
                    CodeRange.CR_7BIT);
        }

        return rope;
    }

    public RubyString createInstance(RubyContext context) {
        return StringOperations.createString(context, language, getRope(), Encodings.BINARY);
    }

    private static boolean is7Bit(String literal) {
        for (int n = 0; n < literal.length(); n++) {
            if (literal.charAt(n) > 0b1111111) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return literal;
    }

}
