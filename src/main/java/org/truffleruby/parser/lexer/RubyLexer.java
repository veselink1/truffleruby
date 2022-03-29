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
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2004-2005 David Corbin <dcorbin@users.sourceforge.net>
 * Copyright (C) 2005 Zach Dennis <zdennis@mktec.com>
 * Copyright (C) 2006 Thomas Corbat <tcorbat@hsr.ch>
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
 *
 * Copyright (c) 2016, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser.lexer;

import static org.truffleruby.core.rope.CodeRange.CR_7BIT;
import static org.truffleruby.core.rope.CodeRange.CR_BROKEN;
import static org.truffleruby.core.rope.CodeRange.CR_UNKNOWN;
import static org.truffleruby.core.string.StringSupport.isAsciiSpace;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.RubyContext;
import org.truffleruby.SuppressFBWarnings;
import org.truffleruby.collections.ByteArrayBuilder;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.rope.Bytes;
import org.truffleruby.core.rope.BytesKey;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeConstants;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringSupport;
import org.truffleruby.language.SourceIndexLength;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.parser.RubyDeferredWarnings;
import org.truffleruby.parser.SafeDoubleParser;
import org.truffleruby.parser.ast.BackRefParseNode;
import org.truffleruby.parser.ast.BigRationalParseNode;
import org.truffleruby.parser.ast.BignumParseNode;
import org.truffleruby.parser.ast.ComplexParseNode;
import org.truffleruby.parser.ast.FixnumParseNode;
import org.truffleruby.parser.ast.FloatParseNode;
import org.truffleruby.parser.ast.ListParseNode;
import org.truffleruby.parser.ast.NthRefParseNode;
import org.truffleruby.parser.ast.NumericParseNode;
import org.truffleruby.parser.ast.ParseNode;
import org.truffleruby.parser.ast.RationalParseNode;
import org.truffleruby.parser.ast.StrParseNode;
import org.truffleruby.parser.parser.ParserRopeOperations;
import org.truffleruby.parser.parser.ParserSupport;
import org.truffleruby.parser.parser.RubyParser;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/*
 * This is a port of the MRI lexer to Java.
 */
public class RubyLexer implements MagicCommentHandler {

    private final ParserRopeOperations parserRopeOperations = new ParserRopeOperations();

    private BignumParseNode newBignumNode(String value, int radix) {
        return new BignumParseNode(getPosition(), new BigInteger(value, radix));
    }

    private FixnumParseNode newFixnumNode(String value, int radix) throws NumberFormatException {
        return new FixnumParseNode(getPosition(), Long.parseLong(value, radix));
    }

    private ParseNode newRationalNode(String value, int radix) throws NumberFormatException {
        try {
            return new RationalParseNode(getPosition(), Long.parseLong(value, radix), 1);
        } catch (NumberFormatException e) {
            return new BigRationalParseNode(getPosition(), new BigInteger(value, radix), BigInteger.ONE);
        }
    }

    private ComplexParseNode newComplexNode(NumericParseNode number) {
        return new ComplexParseNode(getPosition(), number);
    }

    protected void ambiguousOperator(String op, String syn) {
        warnings.warning(
                getFile(),
                getPosition().toSourceSection(src.getSource()).getStartLine(),
                "`" + op + "' after local variable or literal is interpreted as binary operator");
        warnings.warning(
                getFile(),
                getPosition().toSourceSection(src.getSource()).getStartLine(),
                "even though it seems like " + syn);
    }

    public Source getSource() {
        return src.getSource();
    }

    public enum Keyword {
        END("end", RubyParser.keyword_end, EXPR_END),
        ELSE("else", RubyParser.keyword_else, EXPR_BEG),
        CASE("case", RubyParser.keyword_case, EXPR_BEG),
        ENSURE("ensure", RubyParser.keyword_ensure, EXPR_BEG),
        MODULE("module", RubyParser.keyword_module, EXPR_BEG),
        ELSIF("elsif", RubyParser.keyword_elsif, EXPR_BEG),
        DEF("def", RubyParser.keyword_def, EXPR_FNAME),
        RESCUE("rescue", RubyParser.keyword_rescue, RubyParser.modifier_rescue, EXPR_MID),
        NOT("not", RubyParser.keyword_not, EXPR_ARG),
        THEN("then", RubyParser.keyword_then, EXPR_BEG),
        YIELD("yield", RubyParser.keyword_yield, EXPR_ARG),
        FOR("for", RubyParser.keyword_for, EXPR_BEG),
        SELF("self", RubyParser.keyword_self, EXPR_END),
        FALSE("false", RubyParser.keyword_false, EXPR_END),
        RETRY("retry", RubyParser.keyword_retry, EXPR_END),
        RETURN("return", RubyParser.keyword_return, EXPR_MID),
        TRUE("true", RubyParser.keyword_true, EXPR_END),
        IF("if", RubyParser.keyword_if, RubyParser.modifier_if, EXPR_BEG),
        DEFINED_P("defined?", RubyParser.keyword_defined, EXPR_ARG),
        SUPER("super", RubyParser.keyword_super, EXPR_ARG),
        UNDEF("undef", RubyParser.keyword_undef, EXPR_FNAME),
        BREAK("break", RubyParser.keyword_break, EXPR_MID),
        IN("in", RubyParser.keyword_in, EXPR_BEG),
        DO("do", RubyParser.keyword_do, EXPR_BEG),
        NIL("nil", RubyParser.keyword_nil, EXPR_END),
        UNTIL("until", RubyParser.keyword_until, RubyParser.modifier_until, EXPR_BEG),
        UNLESS("unless", RubyParser.keyword_unless, RubyParser.modifier_unless, EXPR_BEG),
        OR("or", RubyParser.keyword_or, EXPR_BEG),
        NEXT("next", RubyParser.keyword_next, EXPR_MID),
        WHEN("when", RubyParser.keyword_when, EXPR_BEG),
        REDO("redo", RubyParser.keyword_redo, EXPR_END),
        AND("and", RubyParser.keyword_and, EXPR_BEG),
        BEGIN("begin", RubyParser.keyword_begin, EXPR_BEG),
        __LINE__("__LINE__", RubyParser.keyword__LINE__, EXPR_END),
        CLASS("class", RubyParser.keyword_class, EXPR_CLASS),
        __FILE__("__FILE__", RubyParser.keyword__FILE__, EXPR_END),
        LEND("END", RubyParser.keyword_END, EXPR_END),
        LBEGIN("BEGIN", RubyParser.keyword_BEGIN, EXPR_END),
        WHILE("while", RubyParser.keyword_while, RubyParser.modifier_while, EXPR_BEG),
        ALIAS("alias", RubyParser.keyword_alias, EXPR_FNAME),
        __ENCODING__("__ENCODING__", RubyParser.keyword__ENCODING__, EXPR_END);

        public final String name;
        public final Rope bytes;
        public final int id0;
        public final int id1;
        public final int state;

        private abstract static class Maps {
            private static final Map<String, Keyword> FROM_STRING;
            private static final Map<BytesKey, Keyword> FROM_BYTES;

            static {
                final Map<String, Keyword> fromString = new HashMap<>();
                final Map<BytesKey, Keyword> fromBytes = new HashMap<>();
                for (Keyword keyword : Keyword.values()) {
                    fromString.put(keyword.name, keyword);
                    fromBytes.put(new BytesKey(keyword.bytes.getBytes(), null), keyword);
                }
                FROM_STRING = Collections.unmodifiableMap(fromString);
                FROM_BYTES = Collections.unmodifiableMap(fromBytes);
            }
        }

        Keyword(String name, int id, int state) {
            this(name, id, id, state);
        }

        Keyword(String name, int id, int modifier, int state) {
            this.name = name;
            this.bytes = RopeOperations.encodeAscii(name, USASCIIEncoding.INSTANCE);
            this.id0 = id;
            this.id1 = modifier;
            this.state = state;
        }
    }

    public static Keyword getKeyword(String str) {
        return Keyword.Maps.FROM_STRING.get(str);
    }

    public static Keyword getKeyword(Rope rope) {
        return Keyword.Maps.FROM_BYTES.get(new BytesKey(rope.getBytes(), null));
    }

    // Used for tiny smidgen of grammar in lexer (see setParserSupport())
    private ParserSupport parserSupport = null;

    // What handles warnings
    private RubyDeferredWarnings warnings;

    public int tokenize_ident(int result) {
        Rope value = createTokenRope();

        if (isLexState(last_state, EXPR_DOT | EXPR_FNAME) &&
                parserSupport.getCurrentScope().isDefined(value.getJavaString().intern()) >= 0) {
            setState(EXPR_END);
        }

        yaccValue = value;
        return result;
    }

    private StrTerm lex_strterm;

    public RubyLexer(ParserSupport support, LexerSource source, RubyDeferredWarnings warnings) {
        this.src = source;
        this.parserSupport = support;
        this.warnings = warnings;
        reset();
    }

    public void reset() {
        superReset();
        lex_strterm = null;
        ruby_sourceline = 1;
        updateLineOffset();

        // nextc will increment for the first character on the first line
        ruby_sourceline--;

        parser_prepare();
    }

    public int nextc() {
        if (lex_p == lex_pend) {
            if (eofp) {
                return EOF;
            }

            final Rope line = src.gets();
            if (line == null) {
                eofp = true;
                lex_goto_eol();
                return EOF;
            }

            if (heredoc_end > 0) {
                ruby_sourceline = heredoc_end;
                updateLineOffset();
                heredoc_end = 0;
            }
            ruby_sourceline++;
            updateLineOffset();
            line_count++;
            lex_pbeg = lex_p = 0;
            lex_pend = lex_p + line.byteLength();
            lexb = line;
            flush();
        }

        int c = p(lex_p);
        lex_p++;
        if (c == '\r') {
            if (peek('\n')) {
                lex_p++;
                c = '\n';
            } else if (ruby_sourceline > last_cr_line) {
                last_cr_line = ruby_sourceline;
                warnings.warn(getFile(), ruby_sourceline, "encountered \\r in middle of line, treated as a mere space");
                c = ' ';
            }
        }

        return c;
    }

    /** Dedent the given node (a {@code (XXX)StrParseNode} or a {@link ListParseNode} of {@code (XXX)StrParseNode})
     * according to {@link #heredoc_indent}. */
    public void heredoc_dedent(ParseNode root) {
        final int indent = heredoc_indent;

        if (indent <= 0 || root == null) {
            return;
        }

        // Other types of string parse nodes do not need dedentation (e.g. EvStrParseNode)
        if (root instanceof StrParseNode) {
            StrParseNode str = (StrParseNode) root;
            str.setValue(dedent_string(str.getValue(), indent));
        } else if (root instanceof ListParseNode) {
            ListParseNode list = (ListParseNode) root;
            int length = list.size();
            int currentLine = 0;
            for (int i = 0; i < length; i++) {
                ParseNode child = list.get(i);
                final int line = child.getPosition().toSourceSection(src.getSource()).getStartLine();
                if (currentLine == line) {
                    continue;  // only process the first element on each line
                }
                currentLine = line;
                if (child instanceof StrParseNode) {
                    final StrParseNode childStrNode = (StrParseNode) child;
                    childStrNode.setValue(dedent_string(childStrNode.getValue(), indent));
                }
            }
        }
    }

    public void compile_error(String message) {
        throw new SyntaxException(SyntaxException.PID.BAD_HEX_NUMBER, getFile(), ruby_sourceline, message);
    }

    public void compile_error(SyntaxException.PID pid, String message) {
        throw new SyntaxException(pid, getFile(), ruby_sourceline, message);
    }

    /** Continue parsing after parsing a heredoc: restore the rest of line after the heredoc start marker, also sets
     * {@link #heredoc_end} to the line where the heredoc ends, so that we can skip the already parsed heredoc. */
    void heredoc_restore(HeredocTerm here) {
        Rope line = here.lastLine;
        lexb = line;
        lex_pbeg = 0;
        lex_pend = lex_pbeg + line.byteLength();
        lex_p = lex_pbeg + here.nth;
        heredoc_end = ruby_sourceline;
        ruby_sourceline = here.line;
        updateLineOffset();
        flush();
    }

    public int nextToken() {
        token = yylex();
        return token == EOF ? 0 : token;
    }

    /** Return a {@link SourceIndexLength} for the current line, to be used as positional information for a token. */
    public SourceIndexLength getPosition() {
        if (tokline != null && ruby_sourceline == ruby_sourceline_when_tokline_created) {
            return tokline;
        }
        assert sourceSectionsMatch();
        return new SourceIndexLength(ruby_sourceline_char_offset, ruby_sourceline_char_length);
    }

    private boolean sourceSectionsMatch() {
        int line = ruby_sourceline;

        if (line == 0) {
            // Reading the position before nextc has run for the first time
            line = 1;
        }

        final SourceSection sectionFromOffsets = src
                .getSource()
                .createSection(ruby_sourceline_char_offset, ruby_sourceline_char_length);

        final SourceSection sectionFromLine = src.getSource().createSection(line);
        assert sectionFromLine.getStartLine() == line;

        assert sectionFromOffsets.getStartLine() == line;
        assert sectionFromLine.getCharIndex() == sectionFromOffsets.getCharIndex();
        assert sectionFromLine.getCharLength() == sectionFromOffsets.getCharLength();

        return true;
    }

    public void updateLineOffset() {
        if (ruby_sourceline != 0) {
            ruby_sourceline_char_offset = src.getSource().getLineStartOffset(ruby_sourceline);
            ruby_sourceline_char_length = src.getSource().getLineLength(ruby_sourceline);
        }
    }

    protected void setCompileOptionFlag(String name, Rope value) {
        if (tokenSeen) {
            warnings.warning(
                    getFile(),
                    getPosition().toSourceSection(src.getSource()).getStartLine(),
                    "`" + name + "' is ignored after any tokens");
            return;
        }

        int b = asTruth(name, value);
        if (b < 0) {
            return;
        }

        if (name.equals("frozen_string_literal")) {
            parserSupport.getConfiguration().setFrozenStringLiteral(b == 1);
        } else if (name.equals("truffleruby_primitives")) {
            parserSupport.getConfiguration().allowTruffleRubyPrimitives = (b == 1);
        } else {
            compile_error("Unknown compile option flag: " + name);
        }
    }

    private static final Rope TRUE = RopeOperations
            .create(new Bytes(new byte[]{ 't', 'r', 'u', 'e' }), ASCIIEncoding.INSTANCE, CR_7BIT);
    private static final Rope FALSE = RopeOperations
            .create(new Bytes(new byte[]{ 'f', 'a', 'l', 's', 'e' }), ASCIIEncoding.INSTANCE, CR_7BIT);

    protected int asTruth(String name, Rope value) {
        int result = RopeOperations.caseInsensitiveCmp(value, TRUE);
        if (result == 0) {
            return 1;
        }

        result = RopeOperations.caseInsensitiveCmp(value, FALSE);
        if (result == 0) {
            return 0;
        }

        warnings.warn("invalid value for " + name + ": " + value);
        return -1;
    }

    protected void setTokenInfo(String name, Rope value) {

    }

    protected void setEncoding(Rope name) {
        final RubyContext context = parserSupport.getConfiguration().getContext();
        final Encoding newEncoding = parserSupport.getEncoding(name);

        if (newEncoding == null) {
            throw argumentError(context, "unknown encoding name: " + RopeOperations.decodeRope(name));
        }

        if (!newEncoding.isAsciiCompatible()) {
            throw argumentError(context, RopeOperations.decodeRope(name) + " is not ASCII compatible");
        }

        if (!src.isFromRope() && !isUTF8Subset(newEncoding)) {
            /* The source we are lexing came in via a String (or Reader, or File) from the Polyglot API, so we only have
             * the String - we don't have any access to the original bytes, so we cannot re-interpret them in another
             * encoding without risking errors. */

            final String description;

            if (src.getSourcePath().equals("-e")) {
                description = "program from an -e argument";
            } else {
                description = "Polyglot API Source";
            }

            throw argumentError(
                    context,
                    String.format(
                            "%s cannot be used as an encoding for a %s as it is not UTF-8 or a subset of UTF-8",
                            RopeOperations.decodeRope(name),
                            description));
        }

        setEncoding(newEncoding);
    }

    private boolean isUTF8Subset(Encoding encoding) {
        return encoding == UTF8Encoding.INSTANCE || encoding == USASCIIEncoding.INSTANCE;
    }

    private RuntimeException argumentError(RubyContext context, String message) {
        if (context != null) {
            return new RaiseException(context, context.getCoreExceptions().argumentError(message, null));
        } else {
            return new UnsupportedOperationException(message);
        }
    }

    public StrTerm getStrTerm() {
        return lex_strterm;
    }

    public void setStrTerm(StrTerm strterm) {
        this.lex_strterm = strterm;
    }

    public void setWarnings(RubyDeferredWarnings warnings) {
        this.warnings = warnings;
    }

    private int considerComplex(int token, int suffix) {
        if ((suffix & SUFFIX_I) == 0) {
            return token;
        } else {
            yaccValue = newComplexNode((NumericParseNode) yaccValue);
            return RubyParser.tIMAGINARY;
        }
    }

    private int getFloatToken(String number, int suffix) {
        if ((suffix & SUFFIX_R) != 0) {
            BigDecimal bd = new BigDecimal(number);
            BigDecimal denominator = BigDecimal.ONE.scaleByPowerOfTen(bd.scale());
            BigDecimal numerator = bd.multiply(denominator);

            try {
                yaccValue = new RationalParseNode(
                        getPosition(),
                        numerator.longValueExact(),
                        denominator.longValueExact());
            } catch (ArithmeticException ae) {
                // FIXME: Rational supports Bignum numerator and denominator
                compile_error(
                        SyntaxException.PID.RATIONAL_OUT_OF_RANGE,
                        "Rational (" + numerator + "/" + denominator + ") out of range.");
            }
            return considerComplex(RubyParser.tRATIONAL, suffix);
        }

        double d;
        try {
            d = SafeDoubleParser.parseDouble(number);
        } catch (NumberFormatException e) {
            warnings.warn(
                    getFile(),
                    getPosition().toSourceSection(src.getSource()).getStartLine(),
                    "Float " + number + " out of range.");

            d = number.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        yaccValue = new FloatParseNode(getPosition(), d);
        return considerComplex(RubyParser.tFLOAT, suffix);
    }

    private int getIntegerToken(String value, int radix, int suffix) {
        ParseNode literalValue;

        if ((suffix & SUFFIX_R) != 0) {
            literalValue = newRationalNode(value, radix);
        } else {
            try {
                literalValue = newFixnumNode(value, radix);
            } catch (NumberFormatException e) {
                literalValue = newBignumNode(value, radix);
            }
        }

        yaccValue = literalValue;
        return considerComplex(RubyParser.tINTEGER, suffix);
    }

    public StrParseNode createStr(RopeBuilder buffer, int flags) {
        return createStr(buffer.toRope(), flags);
    }

    // STR_NEW3/parser_str_new
    public StrParseNode createStr(Rope buffer, int flags) {
        Encoding bufferEncoding = buffer.getEncoding();
        CodeRange codeRange = buffer.getCodeRange();

        if ((flags & STR_FUNC_REGEXP) == 0 && bufferEncoding.isAsciiCompatible()) {
            // If we have characters outside 7-bit range and we are still ascii then change to ascii-8bit
            if (codeRange == CodeRange.CR_7BIT) {
                // Do nothing like MRI
            } else if (getEncoding() == USASCIIEncoding.INSTANCE &&
                    bufferEncoding != UTF8Encoding.INSTANCE) {
                codeRange = associateEncoding(buffer, ASCIIEncoding.INSTANCE, codeRange);
                buffer = parserRopeOperations.withEncoding(buffer, ASCIIEncoding.INSTANCE);
            }
        }

        StrParseNode newStr = new StrParseNode(getPosition(), buffer, codeRange);

        if (parserSupport.getConfiguration().isFrozenStringLiteral()) {
            newStr.setFrozen(true);
        }

        return newStr;
    }

    public static CodeRange associateEncoding(Rope buffer, Encoding newEncoding, CodeRange codeRange) {
        Encoding bufferEncoding = buffer.getEncoding();

        if (newEncoding == bufferEncoding) {
            return codeRange;
        }

        if (codeRange != CodeRange.CR_7BIT || !newEncoding.isAsciiCompatible()) {
            return CodeRange.CR_UNKNOWN;
        }

        return codeRange;
    }

    /** What type/kind of quote are we dealing with?
     *
     * @param c first character the the quote construct
     * @return a token that specifies the quote type */
    private int parseQuote(int c) {
        int begin, end;
        boolean shortHand;

        if (!Character.isLetterOrDigit(c)) {
            // Short-hand (e.g. %{,%.,%!,... versus %Q{).
            begin = c;
            c = 'Q';
            shortHand = true;
        } else {
            // Long-hand (e.g. %Q{}).
            shortHand = false;
            begin = nextc();
            if (Character.isLetterOrDigit(begin) /* no mb || ismbchar(term) */) {
                compile_error(SyntaxException.PID.STRING_UNKNOWN_TYPE, "unknown type of %string");
            }
        }
        if (c == EOF || begin == EOF) {
            compile_error(SyntaxException.PID.STRING_HITS_EOF, "unterminated quoted string meets end of file");
        }

        // Figure end-char.  '\0' is special to indicate begin=end and that no nesting?
        switch (begin) {
            case '(':
                end = ')';
                break;
            case '[':
                end = ']';
                break;
            case '{':
                end = '}';
                break;
            case '<':
                end = '>';
                break;
            default:
                end = begin;
                begin = '\0';
        }

        switch (c) {
            case 'Q':
                lex_strterm = new StringTerm(str_dquote, begin, end, ruby_sourceline);
                yaccValue = "%" + (shortHand ? ("" + end) : ("" + c + begin));
                return RubyParser.tSTRING_BEG;

            case 'q':
                lex_strterm = new StringTerm(str_squote, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tSTRING_BEG;

            case 'W':
                lex_strterm = new StringTerm(str_dword, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tWORDS_BEG;

            case 'w':
                lex_strterm = new StringTerm(str_sword, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tQWORDS_BEG;

            case 'x':
                lex_strterm = new StringTerm(str_xquote, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tXSTRING_BEG;

            case 'r':
                lex_strterm = new StringTerm(str_regexp, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tREGEXP_BEG;

            case 's':
                lex_strterm = new StringTerm(str_ssym, begin, end, ruby_sourceline);
                setState(EXPR_FNAME | EXPR_FITEM);
                yaccValue = "%" + c + begin;
                return RubyParser.tSYMBEG;

            case 'I':
                lex_strterm = new StringTerm(str_dword, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tSYMBOLS_BEG;
            case 'i':
                lex_strterm = new StringTerm(str_sword, begin, end, ruby_sourceline);
                yaccValue = "%" + c + begin;
                return RubyParser.tQSYMBOLS_BEG;
            default:
                compile_error(SyntaxException.PID.STRING_UNKNOWN_TYPE, "unknown type of %string");
        }
        return -1; // not-reached
    }

    @SuppressFBWarnings({ "INT", "DB" })
    private int hereDocumentIdentifier() {
        int c = nextc();
        int term;
        int indent = 0;

        int func = 0;
        if (c == '-') {
            c = nextc();
            func = STR_FUNC_INDENT;
        } else if (c == '~') {
            c = nextc();
            func = STR_FUNC_INDENT;
            indent = Integer.MAX_VALUE;
        }

        Rope markerValue; // the value that marks the end of the heredoc

        if (c == '\'' || c == '"' || c == '`') {
            // the marker is quoted
            if (c == '\'') {
                func |= str_squote; // don't expand interpolation
            } else if (c == '"') {
                func |= str_dquote; // expand interpolation, same as default
            } else {
                func |= str_xquote; // run as process and return value
            }

            newtok(false); // reset token position past the opening quote

            // read marker value (until closing quote)
            term = c;
            while ((c = nextc()) != EOF && c != term) {
                if (!tokadd_mbchar(c)) {
                    return EOF;
                }
            }

            if (c == EOF) {
                compile_error("unterminated here document identifier");
            }

            assert c == term;

            // We pushback the term symbol so that we can build the rope using lex_p.
            pushback(term);
            markerValue = createTokenByteArrayView();
            nextc();
        } else {
            if (!isIdentifierChar(c)) {
                // no identifier following <<- or <<~, not a heredoc & pushback - or ~
                pushback(c);
                if ((func & STR_FUNC_INDENT) != 0) {
                    pushback(heredoc_indent > 0 ? '~' : '-');
                }
                return 0;
            }
            newtok(true);

            // when there is no quote around the end marker, behave as thought it was double quote
            term = '"';
            func |= str_dquote;

            // read marker value (until non-identifier char)
            do {
                if (!tokadd_mbchar(c)) {
                    return EOF;
                }
            } while ((c = nextc()) != EOF && isIdentifierChar(c));
            pushback(c);
            markerValue = createTokenByteArrayView();
        }

        int len = lex_p - lex_pbeg; // marker length

        // skip to end of line - we will resume parsing the line after the heredoc is processed!
        lex_goto_eol();

        // next yylex() invocation(s) will parse the heredoc content (including the terminator)
        lex_strterm = new HeredocTerm(markerValue, func, len, ruby_sourceline, lexb);

        if (term == '`') {
            yaccValue = RopeConstants.BACKTICK;
            flush();
            return RubyParser.tXSTRING_BEG; // marks the beggining of a backtick string in the parser
        }

        yaccValue = RopeConstants.QQ; // double quote
        heredoc_indent = indent; // 0 if [<<-], MAX_VALUE if [<<~]
        heredoc_line_indent = 0;
        flush();
        return RubyParser.tSTRING_BEG; // marks the beginning of a string in the parser
    }

    private boolean arg_ambiguous() {
        warnings.warning(
                getFile(),
                getPosition().toSourceSection(src.getSource()).getStartLine(),
                "Ambiguous first argument; make sure.");
        return true;
    }

    /** Returns the next token. Also sets yyVal is needed.
     *
     * @return Description of the Returned Value */
    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings("SF")
    private int yylex() {
        int c;
        boolean spaceSeen = false;
        boolean commandState;
        boolean tokenSeen = this.tokenSeen;

        if (lex_strterm != null) {
            // parse string or heredoc content
            return lex_strterm.parseString(this);
        }

        commandState = commandStart;
        commandStart = false;
        this.tokenSeen = true;

        loop: for (;;) {
            last_state = lex_state;
            c = nextc();
            switch (c) {
                case '\000': /* NUL */
                case '\004': /* ^D */
                case '\032': /* ^Z */
                case EOF: /* end of script. */
                    return EOF;

                /* white spaces */
                case ' ':
                case '\t':
                case '\f':
                case '\r':
                case '\13': /* '\v' */
                    getPosition();
                    spaceSeen = true;
                    continue;
                case '#': { /* it's a comment */
                    this.tokenSeen = tokenSeen;

                    // There are no magic comments that can affect any runtime options after a token has been seen, so there's
                    // no point in looking for them. However, if warnings are enabled, this should, but does not, scan for
                    // the magic comment so we can report that it will be ignored. It does not warn for verbose because
                    // verbose is not known at this point and we don't want to remove the tokenSeen check because it would
                    // affect lexer performance.
                    if (!tokenSeen) {
                        if (!parser_magic_comment(lexb, lex_p, lex_pend - lex_p, parserRopeOperations, this)) {
                            if (comment_at_top()) {
                                set_file_encoding(lex_p, lex_pend);
                            }
                        }
                    }

                    lex_p = lex_pend;
                }
                /* fall through */
                case '\n': {
                    this.tokenSeen = tokenSeen;
                    boolean normalArg = isLexState(lex_state, EXPR_BEG | EXPR_CLASS | EXPR_FNAME | EXPR_DOT) &&
                            !isLexState(lex_state, EXPR_LABELED);
                    if (normalArg || isLexStateAll(lex_state, EXPR_ARG | EXPR_LABELED)) {
                        if (!normalArg && inKwarg) {
                            commandStart = true;
                            setState(EXPR_BEG);
                            return '\n';
                        }
                        continue loop;
                    }

                    boolean done = false;
                    while (!done) {
                        c = nextc();

                        switch (c) {
                            case ' ':
                            case '\t':
                            case '\f':
                            case '\r':
                            case '\13': /* '\v' */
                                spaceSeen = true;
                                continue;
                            case '#':
                                pushback(c);
                                continue loop;
                            case '&':
                            case '.': {
                                if (peek('.') == (c == '&')) {
                                    pushback(c);

                                    continue loop;
                                }
                            }
                            default:
                            case -1:    // EOF (ENEBO: After default?
                                done = true;
                        }
                    }

                    if (c == -1) {
                        return EOF;
                    }

                    pushback(c);
                    getPosition();

                    commandStart = true;
                    setState(EXPR_BEG);
                    return '\n';
                }
                case '*':
                    return star(spaceSeen);
                case '!':
                    return bang();
                case '=':
                    // documentation nodes
                    if (was_bol()) {
                        if (strncmp(
                                parserRopeOperations.makeShared(lexb, lex_p, lex_pend - lex_p),
                                BEGIN_DOC_MARKER,
                                BEGIN_DOC_MARKER.byteLength()) &&
                                Character.isWhitespace(p(lex_p + 5))) {
                            for (;;) {
                                lex_goto_eol();

                                c = nextc();

                                if (c == EOF) {
                                    compile_error("embedded document meets end of file");
                                    return EOF;
                                }

                                if (c != '=') {
                                    continue;
                                }

                                if (strncmp(
                                        parserRopeOperations.makeShared(lexb, lex_p, lex_pend - lex_p),
                                        END_DOC_MARKER,
                                        END_DOC_MARKER.byteLength()) &&
                                        (lex_p + 3 == lex_pend || Character.isWhitespace(p(lex_p + 3)))) {
                                    break;
                                }
                            }
                            lex_goto_eol();

                            continue loop;
                        }
                    }

                    setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

                    c = nextc();
                    if (c == '=') {
                        c = nextc();
                        if (c == '=') {
                            yaccValue = RopeConstants.EQ_EQ_EQ;
                            return RubyParser.tEQQ;
                        }
                        pushback(c);
                        yaccValue = RopeConstants.EQ_EQ;
                        return RubyParser.tEQ;
                    }
                    if (c == '~') {
                        yaccValue = RopeConstants.EQ_TILDE;
                        return RubyParser.tMATCH;
                    } else if (c == '>') {
                        yaccValue = RopeConstants.EQ_GT;
                        return RubyParser.tASSOC;
                    }
                    pushback(c);
                    yaccValue = RopeConstants.EQ;
                    return '=';

                case '<':
                    return lessThan(spaceSeen);
                case '>':
                    return greaterThan();
                case '"':
                    return doubleQuote(commandState);
                case '`':
                    return backtick(commandState);
                case '\'':
                    return singleQuote(commandState);
                case '?':
                    return questionMark();
                case '&':
                    return ampersand(spaceSeen);
                case '|':
                    return pipe();
                case '+':
                    return plus(spaceSeen);
                case '-':
                    return minus(spaceSeen);
                case '.':
                    return dot();
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    return parseNumber(c);
                case ')':
                    return rightParen();
                case ']':
                    return rightBracket();
                case '}':
                    return rightCurly();
                case ':':
                    return colon(spaceSeen);
                case '/':
                    return slash(spaceSeen);
                case '^':
                    return caret();
                case ';':
                    commandStart = true;
                    setState(EXPR_BEG);
                    yaccValue = RopeConstants.SEMICOLON;
                    return ';';
                case ',':
                    return comma(c);
                case '~':
                    return tilde();
                case '(':
                    return leftParen(spaceSeen);
                case '[':
                    return leftBracket(spaceSeen);
                case '{':
                    return leftCurly();
                case '\\':
                    c = nextc();
                    if (c == '\n') {
                        spaceSeen = true;
                        continue;
                    }
                    pushback(c);
                    yaccValue = RopeConstants.BACKSLASH;
                    return '\\';
                case '%':
                    return percent(spaceSeen);
                case '$':
                    return dollar();
                case '@':
                    return at();
                case '_':
                    if (was_bol() && whole_match_p(END_MARKER, false)) {
                        endPosition = src.getOffset();
                        eofp = true;

                        lex_goto_eol();
                        return EOF;
                    }
                    return identifier(c, commandState);
                default:
                    return identifier(c, commandState);
            }
        }
    }

    private int identifierToken(int result, Rope value) {
        if (result == RubyParser.tIDENTIFIER && !isLexState(last_state, EXPR_DOT | EXPR_FNAME) &&
                parserSupport.getCurrentScope().isDefined(value.getJavaString().intern()) >= 0) {
            setState(EXPR_END | EXPR_LABEL);
        }

        yaccValue = value;
        return result;
    }

    private int ampersand(boolean spaceSeen) {
        int c = nextc();

        switch (c) {
            case '&':
                setState(EXPR_BEG);
                if ((c = nextc()) == '=') {
                    yaccValue = RopeConstants.AMPERSAND_AMPERSAND;
                    setState(EXPR_BEG);
                    return RubyParser.tOP_ASGN;
                }
                pushback(c);
                yaccValue = RopeConstants.AMPERSAND_AMPERSAND;
                return RubyParser.tANDOP;
            case '=':
                yaccValue = RopeConstants.AMPERSAND;
                setState(EXPR_BEG);
                return RubyParser.tOP_ASGN;
            case '.':
                setState(EXPR_DOT);
                yaccValue = RopeConstants.AMPERSAND_DOT;
                return RubyParser.tANDDOT;
        }
        pushback(c);

        //tmpPosition is required because getPosition()'s side effects.
        //if the warning is generated, the getPosition() on line 954 (this line + 18) will create
        //a wrong position if the "inclusive" flag is not set.
        SourceIndexLength tmpPosition = getPosition();
        if (isSpaceArg(c, spaceSeen)) {
            warnings.warning(
                    getFile(),
                    tmpPosition.toSourceSection(src.getSource()).getStartLine(),
                    "`&' interpreted as argument prefix");
            c = RubyParser.tAMPER;
        } else if (isBEG()) {
            c = RubyParser.tAMPER;
        } else {
            warn_balanced(c, spaceSeen, "&", "argument prefix");
            c = RubyParser.tAMPER2;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        yaccValue = RopeConstants.AMPERSAND;
        return c;
    }

    private static boolean hasShebangLine(Bytes bytes) {
        return bytes.length > 2 && bytes.get(0) == '#' && bytes.get(1) == '!';
    }

    private static int newLineIndex(Bytes bytes, int start) {
        for (int i = start; i < bytes.length; i++) {
            if (bytes.get(i) == '\n') {
                return i;
            }
        }

        return bytes.length;
    }

    /** Peak in source to see if there is a magic comment. This is used by eval() & friends to know the actual encoding
     * of the source code, and be able to convert to a Java String faithfully. */
    public static void parseMagicComment(Rope source, BiConsumer<String, Rope> magicCommentHandler) {
        final Bytes bytes = source.getBytes();
        final int length = source.byteLength();
        int start = 0;

        if (hasShebangLine(bytes)) {
            start = newLineIndex(bytes, 2) + 1;
        }

        // Skip leading spaces but don't jump to another line
        while (start < length && isAsciiSpace(bytes.get(start)) && bytes.get(start) != '\n') {
            start++;
        }

        if (start < length && bytes.get(start) == '#') {
            start++;

            final int magicLineStart = start;
            int endOfMagicLine = newLineIndex(bytes, magicLineStart);
            if (endOfMagicLine < length) {
                endOfMagicLine++;
            }
            int magicLineLength = endOfMagicLine - magicLineStart;

            parser_magic_comment(source, magicLineStart, magicLineLength, new ParserRopeOperations(), (name, value) -> {
                magicCommentHandler.accept(name, value);
                return isKnownMagicComment(name);
            });
        }
    }

    // MRI: parser_magic_comment
    private static boolean parser_magic_comment(Rope magicLine, int magicLineOffset, int magicLineLength,
            ParserRopeOperations parserRopeOperations, MagicCommentHandler magicCommentHandler) {

        boolean emacsStyle = false;
        int i = magicLineOffset;
        int end = magicLineOffset + magicLineLength;

        if (magicLineLength <= 7) {
            return false;
        }

        final int emacsBegin = findEmacsStyleMarker(magicLine, 0, end);
        if (emacsBegin >= 0) {
            final int emacsEnd = findEmacsStyleMarker(magicLine, emacsBegin, end);
            if (emacsEnd < 0) {
                return false;
            }
            emacsStyle = true;
            i = emacsBegin;
            end = emacsEnd - 3; // -3 is to backup over the final -*- we just found
        }

        while (i < end) { // in Emacs mode, there can be multiple name/value pairs on the same line

            // Manual parsing corresponding to this Regexp.
            // Done manually as we want to parse bytes and don't know the encoding yet, and to optimize speed.

            // / [\s'":;]* (?<name> [^\s'":;]+ ) \s* : \s* (?<value> "(?:\\.|[^"])*" | [^";\s]+ ) [\s;]* /x

            // Ignore leading whitespace or '":;
            while (i < end) {
                byte c = magicLine.get(i);

                if (isIgnoredMagicLineCharacter(c) || isAsciiSpace(c)) {
                    i++;
                } else {
                    break;
                }
            }

            final int nameBegin = i;

            // Consume anything except [\s'":;]
            while (i < end) {
                byte c = magicLine.get(i);

                if (isIgnoredMagicLineCharacter(c) || isAsciiSpace(c)) {
                    break;
                } else {
                    i++;
                }
            }

            final int nameEnd = i;

            // Ignore whitespace
            while (i < end && isAsciiSpace(magicLine.get(i))) {
                i++;
            }

            if (i == end) {
                break;
            }

            // Expect ':' between name and value
            final byte sep = magicLine.get(i);
            if (sep == ':') {
                i++;
            } else {
                if (!emacsStyle) {
                    return false;
                }
                continue;
            }

            // Ignore whitespace
            while (i < end && isAsciiSpace(magicLine.get(i))) {
                i++;
            }

            if (i == end) {
                break;
            }

            final int valueBegin, valueEnd;

            if (magicLine.get(i) == '"') { // quoted value
                valueBegin = ++i;
                while (i < end && magicLine.get(i) != '"') {
                    if (magicLine.get(i) == '\\') {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                valueEnd = i;

                // Skip the final "
                if (i < end) {
                    i++;
                }
            } else {
                valueBegin = i;
                while (i < end) {
                    byte c = magicLine.get(i);
                    if (c != '"' && c != ';' && !isAsciiSpace(c)) {
                        i++;
                    } else {
                        break;
                    }
                }
                valueEnd = i;
            }

            if (emacsStyle) {
                // Ignore trailing whitespace or ;
                while (i < end && (magicLine.get(i) == ';' || isAsciiSpace(magicLine.get(i)))) {
                    i++;
                }
            } else {
                // Ignore trailing whitespace
                while (i < end && isAsciiSpace(magicLine.get(i))) {
                    i++;
                }

                if (i < end) {
                    return false;
                }
            }

            final String name = RopeOperations
                    .decodeRopeSegment(magicLine, nameBegin, nameEnd - nameBegin)
                    .replace('-', '_');
            final Rope value = parserRopeOperations.makeShared(magicLine, valueBegin, valueEnd - valueBegin);

            if (!magicCommentHandler.onMagicComment(name, value)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isIgnoredMagicLineCharacter(byte c) {
        switch (c) {
            case '\'':
            case '"':
            case ':':
            case ';':
                return true;
            default:
                return false;
        }
    }

    /* MRI: magic_comment_marker Find -*-, as in emacs "file local variable" (special comment at the top of the file) */
    private static int findEmacsStyleMarker(Rope str, int begin, int end) {
        final Bytes bytes = str.getBytes();
        int i = begin;

        while (i < end) {
            switch (bytes.get(i)) {
                case '-':
                    if (i >= 2 && bytes.get(i - 1) == '*' && bytes.get(i - 2) == '-') {
                        return i + 1;
                    }
                    i += 2;
                    break;
                case '*':
                    if (i + 1 >= end) {
                        return -1;
                    }

                    if (bytes.get(i + 1) != '-') {
                        i += 4;
                    } else if (bytes.get(i - 1) != '-') {
                        i += 2;
                    } else {
                        return i + 2;
                    }
                    break;
                default:
                    i += 3;
                    break;
            }
        }
        return -1;
    }

    @Override
    public boolean onMagicComment(String name, Rope value) {
        if (isMagicEncodingComment(name)) {
            magicCommentEncoding(value);
            return true;
        } else if ("frozen_string_literal".equalsIgnoreCase(name)) {
            setCompileOptionFlag("frozen_string_literal", value);
            return true;
        } else if ("truffleruby_primitives".equalsIgnoreCase(name)) {
            setCompileOptionFlag("truffleruby_primitives", value);
            return true;
        } else if ("warn_indent".equalsIgnoreCase(name)) {
            setTokenInfo(name, value);
            return true;
        }
        return false;
    }

    private static boolean isKnownMagicComment(String name) {
        if (isMagicEncodingComment(name)) {
            return true;
        } else if ("frozen_string_literal".equalsIgnoreCase(name)) {
            return true;
        } else if ("warn_indent".equalsIgnoreCase(name)) {
            return true;
        } else if ("truffleruby_primitives".equalsIgnoreCase(name)) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isMagicEncodingComment(String name) {
        return "coding".equalsIgnoreCase(name) || "encoding".equalsIgnoreCase(name);
    }

    private int at() {
        newtok(true);
        int c = nextc();
        int result;
        if (c == '@') {
            c = nextc();
            result = RubyParser.tCVAR;
        } else {
            result = RubyParser.tIVAR;
        }

        if (c == EOF || isSpace(c)) {
            if (result == RubyParser.tIVAR) {
                compile_error("`@' without identifiers is not allowed as an instance variable name");
            }

            compile_error("`@@' without identifiers is not allowed as a class variable name");
        } else if (Character.isDigit(c) || !isIdentifierChar(c)) {
            pushback(c);
            if (result == RubyParser.tIVAR) {
                compile_error(
                        SyntaxException.PID.IVAR_BAD_NAME,
                        "`@" + ((char) c) + "' is not allowed as an instance variable name");
            }
            compile_error(
                    SyntaxException.PID.CVAR_BAD_NAME,
                    "`@@" + ((char) c) + "' is not allowed as a class variable name");
        }

        if (!tokadd_ident(c)) {
            return EOF;
        }

        last_state = lex_state;
        setState(EXPR_END);

        return tokenize_ident(result);
    }

    private int backtick(boolean commandState) {
        yaccValue = RopeConstants.BACKTICK;

        if (isLexState(lex_state, EXPR_FNAME)) {
            setState(EXPR_ENDFN);
            return RubyParser.tBACK_REF2;
        }
        if (isLexState(lex_state, EXPR_DOT)) {
            setState(commandState ? EXPR_CMDARG : EXPR_ARG);

            return RubyParser.tBACK_REF2;
        }

        lex_strterm = new StringTerm(str_xquote, '\0', '`', ruby_sourceline);
        return RubyParser.tXSTRING_BEG;
    }

    private int bang() {
        int c = nextc();

        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = RopeConstants.BANG;
                return RubyParser.tBANG;
            }
        } else {
            setState(EXPR_BEG);
        }

        switch (c) {
            case '=':
                yaccValue = RopeConstants.BANG_EQ;

                return RubyParser.tNEQ;
            case '~':
                yaccValue = RopeConstants.BANG_TILDE;

                return RubyParser.tNMATCH;
            default: // Just a plain bang
                pushback(c);
                yaccValue = RopeConstants.BANG;

                return RubyParser.tBANG;
        }
    }

    private int caret() {
        int c = nextc();
        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = RopeConstants.CARET;
            return RubyParser.tOP_ASGN;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        pushback(c);
        yaccValue = RopeConstants.CARET;
        return RubyParser.tCARET;
    }

    private int colon(boolean spaceSeen) {
        int c = nextc();

        if (c == ':') {
            if (isBEG() || isLexState(lex_state, EXPR_CLASS) || (isARG() && spaceSeen)) {
                setState(EXPR_BEG);
                yaccValue = RopeConstants.COLON_COLON;
                return RubyParser.tCOLON3;
            }
            setState(EXPR_DOT);
            yaccValue = RopeConstants.COLON_COLON;
            return RubyParser.tCOLON2;
        }

        if (isEND() || Character.isWhitespace(c) || c == '#') {
            pushback(c);
            setState(EXPR_BEG);
            yaccValue = RopeConstants.COLON;
            warn_balanced(c, spaceSeen, ":", "symbol literal");
            return ':';
        }

        switch (c) {
            case '\'':
                lex_strterm = new StringTerm(str_ssym, '\0', c, ruby_sourceline);
                break;
            case '"':
                lex_strterm = new StringTerm(str_dsym, '\0', c, ruby_sourceline);
                break;
            default:
                pushback(c);
                break;
        }

        setState(EXPR_FNAME);
        yaccValue = RopeConstants.COLON;
        return RubyParser.tSYMBEG;
    }

    private int comma(int c) {
        setState(EXPR_BEG | EXPR_LABEL);
        yaccValue = RopeConstants.COMMA;

        return c;
    }

    private int doKeyword(int state) {
        int leftParenBegin = getLeftParenBegin();
        if (leftParenBegin > 0 && leftParenBegin == parenNest) {
            setLeftParenBegin(0);
            parenNest--;
            return RubyParser.keyword_do_lambda;
        }

        if (conditionState.isInState()) {
            return RubyParser.keyword_do_cond;
        }

        if (cmdArgumentState.isInState() && !isLexState(state, EXPR_CMDARG)) {
            return RubyParser.keyword_do_block;
        }
        if (isLexState(state, EXPR_BEG | EXPR_ENDARG)) {
            return RubyParser.keyword_do_block;
        }
        return RubyParser.keyword_do;
    }

    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings("SF")
    private int dollar() {
        setState(EXPR_END);
        newtok(true);
        int c = nextc();

        switch (c) {
            case '_': /* $_: last read line string */
                c = nextc();
                if (isIdentifierChar(c)) {
                    if (!tokadd_ident(c)) {
                        return EOF;
                    }

                    last_state = lex_state;
                    yaccValue = createTokenRope();
                    return RubyParser.tGVAR;
                }
                pushback(c);
                c = '_';
                // fall through
            case '~': /* $~: match-data */
            case '*': /* $*: argv */
            case '$': /* $$: pid */
            case '?': /* $?: last status */
            case '!': /* $!: error string */
            case '@': /* $@: error position */
            case '/': /* $/: input record separator */
            case '\\': /* $\: output record separator */
            case ';': /* $;: field separator */
            case ',': /* $,: output field separator */
            case '.': /* $.: last read line number */
            case '=': /* $=: ignorecase */
            case ':': /* $:: load path */
            case '<': /* $<: reading filename */
            case '>': /* $>: default output handle */
            case '\"': /* $": already loaded files */
                yaccValue = RopeOperations
                        .create(new Bytes(new byte[]{ '$', (byte) c }), USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT);
                return RubyParser.tGVAR;

            case '-':
                c = nextc();
                if (isIdentifierChar(c)) {
                    if (!tokadd_mbchar(c)) {
                        return EOF;
                    }
                } else {
                    pushback(c);
                    pushback('-');
                    return '$';
                }
                yaccValue = createTokenRope();
                /* xxx shouldn't check if valid option variable */
                return RubyParser.tGVAR;

            case '&': /* $&: last match */
            case '`': /* $`: string before last match */
            case '\'': /* $': string after last match */
            case '+': /* $+: string matches last paren. */
                // Explicit reference to these vars as symbols...
                if (isLexState(last_state, EXPR_FNAME)) {
                    yaccValue = RopeOperations
                            .create(
                                    new Bytes(new byte[]{ '$', (byte) c }),
                                    USASCIIEncoding.INSTANCE,
                                    CodeRange.CR_7BIT);
                    return RubyParser.tGVAR;
                }

                yaccValue = new BackRefParseNode(getPosition(), c);
                return RubyParser.tBACK_REF;

            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                do {
                    c = nextc();
                } while (Character.isDigit(c));
                pushback(c);
                if (isLexState(last_state, EXPR_FNAME)) {
                    yaccValue = createTokenRope();
                    return RubyParser.tGVAR;
                }

                int ref;
                String refAsString = createTokenRope().getJavaString();

                try {
                    ref = Integer.parseInt(refAsString.substring(1).intern());
                } catch (NumberFormatException e) {
                    warnings.warn("`" + refAsString + "' is too big for a number variable, always nil");
                    ref = 0;
                }

                yaccValue = new NthRefParseNode(getPosition(), ref);
                return RubyParser.tNTH_REF;
            case '0':
                return identifierToken(RubyParser.tGVAR, RopeConstants.DOLLAR_ZERO);
            default:
                if (!isIdentifierChar(c)) {
                    if (c == EOF || isSpace(c)) {
                        compile_error(
                                SyntaxException.PID.CVAR_BAD_NAME,
                                "`$' without identifiers is not allowed as a global variable name");
                    } else {
                        pushback(c);
                        compile_error(
                                SyntaxException.PID.CVAR_BAD_NAME,
                                "`$" + ((char) c) + "' is not allowed as a global variable name");
                    }
                }

                last_state = lex_state;
                setState(EXPR_END);

                tokadd_ident(c);

                return identifierToken(RubyParser.tGVAR, createTokenRope());  // $blah
        }
    }

    private int dot() {
        int c;

        final boolean isBeg = isBEG();
        setState(EXPR_BEG);
        if ((c = nextc()) == '.') {
            if ((c = nextc()) == '.') {
                yaccValue = RopeConstants.DOT_DOT_DOT;
                return isBeg ? RubyParser.tBDOT3 : RubyParser.tDOT3;
            }
            pushback(c);
            yaccValue = RopeConstants.DOT_DOT;
            return isBeg ? RubyParser.tBDOT2 : RubyParser.tDOT2;
        }

        pushback(c);
        if (Character.isDigit(c)) {
            compile_error(
                    SyntaxException.PID.FLOAT_MISSING_ZERO,
                    "no .<digit> floating literal anymore; put 0 before dot");
        }

        setState(EXPR_DOT);
        yaccValue = RopeConstants.DOT;
        return RubyParser.tDOT;
    }

    private int doubleQuote(boolean commandState) {
        int label = isLabelPossible(commandState) ? str_label : 0;
        lex_strterm = new StringTerm(str_dquote | label, '\0', '"', ruby_sourceline);
        yaccValue = RopeConstants.QQ;

        return RubyParser.tSTRING_BEG;
    }

    private int greaterThan() {
        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        int c = nextc();

        switch (c) {
            case '=':
                yaccValue = RopeConstants.GT_EQ;

                return RubyParser.tGEQ;
            case '>':
                if ((c = nextc()) == '=') {
                    setState(EXPR_BEG);
                    yaccValue = RopeConstants.GT_GT;
                    return RubyParser.tOP_ASGN;
                }
                pushback(c);

                yaccValue = RopeConstants.GT_GT;
                return RubyParser.tRSHFT;
            default:
                pushback(c);
                yaccValue = RopeConstants.GT;
                return RubyParser.tGT;
        }
    }

    private int identifier(int c, boolean commandState) {
        if (!isIdentifierChar(c)) {
            String badChar = "\\" + Integer.toOctalString(c & 0xff);
            compile_error(
                    SyntaxException.PID.CHARACTER_BAD,
                    "Invalid char `" + badChar + "' ('" + (char) c + "') in expression");
        }

        newtok(true);
        do {
            if (!tokadd_mbchar(c)) {
                return EOF;
            }
            c = nextc();
        } while (isIdentifierChar(c));

        boolean lastBangOrPredicate = false;

        // methods 'foo!' and 'foo?' are possible but if followed by '=' it is relop
        if (c == '!' || c == '?') {
            if (!peek('=')) {
                lastBangOrPredicate = true;
            } else {
                pushback(c);
            }
        } else {
            pushback(c);
        }

        int result = 0;

        last_state = lex_state;
        Rope tempVal;
        if (lastBangOrPredicate) {
            result = RubyParser.tFID;
            tempVal = createTokenRope();
        } else {
            if (isLexState(lex_state, EXPR_FNAME)) {
                if ((c = nextc()) == '=') {
                    int c2 = nextc();

                    if (c2 != '~' && c2 != '>' &&
                            (c2 != '=' || peek('>'))) {
                        result = RubyParser.tIDENTIFIER;
                        pushback(c2);
                    } else {
                        pushback(c2);
                        pushback(c);
                    }
                } else {
                    pushback(c);
                }
            }
            tempVal = createTokenRope();

            if (result == 0 && isFirstCodepointUppercase(tempVal)) {
                result = RubyParser.tCONSTANT;
            } else {
                result = RubyParser.tIDENTIFIER;
            }
        }

        if (isLabelPossible(commandState)) {
            if (isLabelSuffix()) {
                setState(EXPR_ARG | EXPR_LABELED);
                nextc();
                yaccValue = tempVal;
                return RubyParser.tLABEL;
            }
        }

        if (lex_state != EXPR_DOT) {
            Keyword keyword = getKeyword(tempVal); // Is it is a keyword?

            if (keyword != null) {
                int state = lex_state; // Save state at time keyword is encountered
                setState(keyword.state);

                if (isLexState(state, EXPR_FNAME)) {
                    yaccValue = keyword.bytes;
                    return keyword.id0;
                } else {
                    yaccValue = getPosition();
                }

                if (isLexState(lex_state, EXPR_BEG)) {
                    commandStart = true;
                }

                if (keyword.id0 == RubyParser.keyword_do) {
                    return doKeyword(state);
                }

                if (isLexState(state, EXPR_BEG | EXPR_LABELED)) {
                    return keyword.id0;
                } else {
                    if (keyword.id0 != keyword.id1) {
                        setState(EXPR_BEG | EXPR_LABEL);
                    }
                    return keyword.id1;
                }
            }
        }

        if (isLexState(lex_state, EXPR_BEG_ANY | EXPR_ARG_ANY | EXPR_DOT)) {
            setState(commandState ? EXPR_CMDARG : EXPR_ARG);
        } else if (lex_state == EXPR_FNAME) {
            setState(EXPR_ENDFN);
        } else {
            setState(EXPR_END);
        }

        return identifierToken(result, tempVal);
    }

    private int leftBracket(boolean spaceSeen) {
        parenNest++;
        int c = '[';
        if (isAfterOperator()) {
            setState(EXPR_ARG);

            if ((c = nextc()) == ']') {
                if (peek('=')) {
                    nextc();
                    yaccValue = RopeConstants.LBRACKET_RBRACKET_EQ;
                    return RubyParser.tASET;
                }
                yaccValue = RopeConstants.LBRACKET_RBRACKET;
                return RubyParser.tAREF;
            }
            pushback(c);
            setState(getState() | EXPR_LABEL);
            yaccValue = RopeConstants.LBRACKET;
            return '[';
        } else if (isBEG() || (isARG() && (spaceSeen || isLexState(lex_state, EXPR_LABELED)))) {
            c = RubyParser.tLBRACK;
        }

        setState(EXPR_BEG | EXPR_LABEL);
        conditionState.stop();
        cmdArgumentState.stop();
        yaccValue = RopeConstants.LBRACKET;
        return c;
    }

    private int leftCurly() {
        braceNest++;
        int leftParenBegin = getLeftParenBegin();
        if (leftParenBegin > 0 && leftParenBegin == parenNest) {
            setState(EXPR_BEG);
            setLeftParenBegin(0);
            parenNest--;
            conditionState.stop();
            cmdArgumentState.stop();
            yaccValue = RopeConstants.LCURLY;
            return RubyParser.tLAMBEG;
        }

        char c;
        if (isLexState(lex_state, EXPR_LABELED)) {
            c = RubyParser.tLBRACE;
        } else if (isLexState(lex_state, EXPR_ARG_ANY | EXPR_END | EXPR_ENDFN)) { // block (primary)
            c = RubyParser.tLCURLY;
        } else if (isLexState(lex_state, EXPR_ENDARG)) { // block (expr)
            c = RubyParser.tLBRACE_ARG;
        } else { // hash
            c = RubyParser.tLBRACE;
        }

        conditionState.stop();
        cmdArgumentState.stop();
        setState(EXPR_BEG);
        if (c != RubyParser.tLBRACE_ARG) {
            setState(getState() | EXPR_LABEL);
        }
        if (c != RubyParser.tLBRACE) {
            commandStart = true;
        }
        yaccValue = getPosition();

        return c;
    }

    private int leftParen(boolean spaceSeen) {
        int result;

        if (isBEG()) {
            result = RubyParser.tLPAREN;
        } else if (isSpaceArg('(', spaceSeen)) {
            result = RubyParser.tLPAREN_ARG;
        } else {
            result = RubyParser.tLPAREN2;
        }

        parenNest++;
        conditionState.stop();
        cmdArgumentState.stop();
        setState(EXPR_BEG | EXPR_LABEL);

        yaccValue = getPosition();
        return result;
    }

    private int lessThan(boolean spaceSeen) {
        last_state = lex_state;
        int c = nextc();
        if (c == '<' && !isLexState(lex_state, EXPR_DOT | EXPR_CLASS) &&
                !isEND() && (!isARG() || isLexState(lex_state, EXPR_LABELED) || spaceSeen)) {
            int tok = hereDocumentIdentifier();

            if (tok != 0) {
                return tok;
            }
        }

        if (isAfterOperator()) {
            setState(EXPR_ARG);
        } else {
            if (isLexState(lex_state, EXPR_CLASS)) {
                commandStart = true;
            }
            setState(EXPR_BEG);
        }

        switch (c) {
            case '=':
                if ((c = nextc()) == '>') {
                    yaccValue = RopeConstants.LT_EQ_GT;
                    return RubyParser.tCMP;
                }
                pushback(c);
                yaccValue = RopeConstants.LT_EQ;
                return RubyParser.tLEQ;
            case '<':
                if ((c = nextc()) == '=') {
                    setState(EXPR_BEG);
                    yaccValue = RopeConstants.LT_LT;
                    return RubyParser.tOP_ASGN;
                }
                pushback(c);
                yaccValue = RopeConstants.LT_LT;
                warn_balanced(c, spaceSeen, "<<", "here document");
                return RubyParser.tLSHFT;
            default:
                yaccValue = RopeConstants.LT;
                pushback(c);
                return RubyParser.tLT;
        }
    }

    private int minus(boolean spaceSeen) {
        int c = nextc();

        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = RopeConstants.MINUS_AT;
                return RubyParser.tUMINUS;
            }
            pushback(c);
            yaccValue = RopeConstants.MINUS;
            return RubyParser.tMINUS;
        }
        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = RopeConstants.MINUS;
            return RubyParser.tOP_ASGN;
        }
        if (c == '>') {
            setState(EXPR_ENDFN);
            yaccValue = RopeConstants.MINUS_GT;
            return RubyParser.tLAMBDA;
        }
        if (isBEG() || (isSpaceArg(c, spaceSeen) && arg_ambiguous())) {
            setState(EXPR_BEG);
            pushback(c);
            yaccValue = RopeConstants.MINUS_AT;
            if (Character.isDigit(c)) {
                return RubyParser.tUMINUS_NUM;
            }
            return RubyParser.tUMINUS;
        }
        setState(EXPR_BEG);
        pushback(c);
        yaccValue = RopeConstants.MINUS;
        warn_balanced(c, spaceSeen, "-", "unary operator");
        return RubyParser.tMINUS;
    }

    private int percent(boolean spaceSeen) {
        if (isBEG()) {
            return parseQuote(nextc());
        }

        int c = nextc();

        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = RopeConstants.PERCENT;
            return RubyParser.tOP_ASGN;
        }

        if (isSpaceArg(c, spaceSeen)) {
            return parseQuote(c);
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        pushback(c);
        yaccValue = RopeConstants.PERCENT;
        warn_balanced(c, spaceSeen, "%", "string literal");
        return RubyParser.tPERCENT;
    }

    private int pipe() {
        int c = nextc();

        switch (c) {
            case '|':
                setState(EXPR_BEG);
                if ((c = nextc()) == '=') {
                    setState(EXPR_BEG);
                    yaccValue = RopeConstants.OR_OR;
                    return RubyParser.tOP_ASGN;
                }
                pushback(c);
                yaccValue = RopeConstants.OR_OR;
                return RubyParser.tOROP;
            case '=':
                setState(EXPR_BEG);
                yaccValue = RopeConstants.OR;
                return RubyParser.tOP_ASGN;
            default:
                setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG | EXPR_LABEL);

                pushback(c);
                yaccValue = RopeConstants.OR;
                return RubyParser.tPIPE;
        }
    }

    private int plus(boolean spaceSeen) {
        int c = nextc();
        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = RopeConstants.PLUS_AT;
                return RubyParser.tUPLUS;
            }
            pushback(c);
            yaccValue = RopeConstants.PLUS;
            return RubyParser.tPLUS;
        }

        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = RopeConstants.PLUS;
            return RubyParser.tOP_ASGN;
        }

        if (isBEG() || (isSpaceArg(c, spaceSeen) && arg_ambiguous())) {
            setState(EXPR_BEG);
            pushback(c);
            if (Character.isDigit(c)) {
                c = '+';
                return parseNumber(c);
            }
            yaccValue = RopeConstants.PLUS_AT;
            return RubyParser.tUPLUS;
        }

        setState(EXPR_BEG);
        pushback(c);
        yaccValue = RopeConstants.PLUS;
        warn_balanced(c, spaceSeen, "+", "unary operator");
        return RubyParser.tPLUS;
    }

    private int questionMark() {
        int c;

        if (isEND()) {
            setState(EXPR_VALUE);
            yaccValue = RopeConstants.QUESTION;
            return '?';
        }

        c = nextc();
        if (c == EOF) {
            compile_error(SyntaxException.PID.INCOMPLETE_CHAR_SYNTAX, "incomplete character syntax");
        }

        if (Character.isWhitespace(c)) {
            if (!isARG()) {
                int c2 = 0;
                switch (c) {
                    case ' ':
                        c2 = 's';
                        break;
                    case '\n':
                        c2 = 'n';
                        break;
                    case '\t':
                        c2 = 't';
                        break;
                    /* What is \v in C? case '\v': c2 = 'v'; break; */
                    case '\r':
                        c2 = 'r';
                        break;
                    case '\f':
                        c2 = 'f';
                        break;
                }
                if (c2 != 0) {
                    warnings.warn(
                            getFile(),
                            getPosition().toSourceSection(src.getSource()).getStartLine(),
                            "invalid character syntax; use ?\\" + c2);
                }
            }
            pushback(c);
            setState(EXPR_VALUE);
            yaccValue = RopeConstants.QUESTION;
            return '?';
        }

        if (!isASCII(c)) {
            if (!tokadd_mbchar(c)) {
                return EOF;
            }
        } else if (isIdentifierChar(c) && !peek('\n') && isNext_identchar()) {
            newtok(true);
            pushback(c);
            setState(EXPR_VALUE);
            yaccValue = RopeConstants.QUESTION;
            return '?';
        } else if (c == '\\') {
            if (peek('u')) {
                nextc(); // Eat 'u'
                RopeBuilder oneCharBL = new RopeBuilder();
                oneCharBL.setEncoding(getEncoding());

                c = readUTFEscape(oneCharBL, false, false);

                if (c >= 0x80) {
                    tokaddmbc(c, oneCharBL);
                } else {
                    oneCharBL.append(c);
                }

                setState(EXPR_END);
                yaccValue = new StrParseNode(getPosition(), oneCharBL.toRope());

                return RubyParser.tCHAR;
            } else {
                c = readEscape();
            }
        } else {
            newtok(true);
        }

        yaccValue = new StrParseNode(getPosition(), RopeConstants.ASCII_8BIT_SINGLE_BYTE_ROPES[c]);
        setState(EXPR_END);
        return RubyParser.tCHAR;
    }

    private int rightBracket() {
        parenNest--;
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_END);
        yaccValue = RopeConstants.RBRACKET;
        return RubyParser.tRBRACK;
    }

    private int rightCurly() {
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_END);
        yaccValue = RopeConstants.RCURLY;
        int tok = braceNest == 0 ? RubyParser.tSTRING_DEND : RubyParser.tRCURLY;
        braceNest--;
        return tok;
    }

    private int rightParen() {
        parenNest--;
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_ENDFN);
        yaccValue = RopeConstants.RPAREN;
        return RubyParser.tRPAREN;
    }

    private int singleQuote(boolean commandState) {
        int label = isLabelPossible(commandState) ? str_label : 0;
        lex_strterm = new StringTerm(str_squote | label, '\0', '\'', ruby_sourceline);
        yaccValue = RopeConstants.Q;

        return RubyParser.tSTRING_BEG;
    }

    private int slash(boolean spaceSeen) {
        if (isBEG()) {
            lex_strterm = new StringTerm(str_regexp, '\0', '/', ruby_sourceline);
            yaccValue = RopeConstants.SLASH;
            return RubyParser.tREGEXP_BEG;
        }

        int c = nextc();

        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = RopeConstants.SLASH;
            return RubyParser.tOP_ASGN;
        }
        pushback(c);
        if (isSpaceArg(c, spaceSeen)) {
            arg_ambiguous();
            lex_strterm = new StringTerm(str_regexp, '\0', '/', ruby_sourceline);
            yaccValue = RopeConstants.SLASH;
            return RubyParser.tREGEXP_BEG;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        yaccValue = RopeConstants.SLASH;
        warn_balanced(c, spaceSeen, "/", "regexp literal");
        return RubyParser.tDIVIDE;
    }

    private int star(boolean spaceSeen) {
        int c = nextc();

        switch (c) {
            case '*':
                if ((c = nextc()) == '=') {
                    setState(EXPR_BEG);
                    yaccValue = RopeConstants.STAR_STAR;
                    return RubyParser.tOP_ASGN;
                }

                pushback(c); // not a '=' put it back
                yaccValue = RopeConstants.STAR_STAR;

                if (isSpaceArg(c, spaceSeen)) {
                    warnings.warning(
                            getFile(),
                            getPosition().toSourceSection(src.getSource()).getStartLine(),
                            "`**' interpreted as argument prefix");
                    c = RubyParser.tDSTAR;
                } else if (isBEG()) {
                    c = RubyParser.tDSTAR;
                } else {
                    warn_balanced(c, spaceSeen, "**", "argument prefix");
                    c = RubyParser.tPOW;
                }
                break;
            case '=':
                setState(EXPR_BEG);
                yaccValue = RopeConstants.STAR;
                return RubyParser.tOP_ASGN;
            default:
                pushback(c);
                if (isSpaceArg(c, spaceSeen)) {
                    warnings.warning(
                            getFile(),
                            getPosition().toSourceSection(src.getSource()).getStartLine(),
                            "`*' interpreted as argument prefix");
                    c = RubyParser.tSTAR;
                } else if (isBEG()) {
                    c = RubyParser.tSTAR;
                } else {
                    warn_balanced(c, spaceSeen, "*", "argument prefix");
                    c = RubyParser.tSTAR2;
                }
                yaccValue = RopeConstants.STAR;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);
        return c;
    }

    private int tilde() {
        int c;

        if (isAfterOperator()) {
            if ((c = nextc()) != '@') {
                pushback(c);
            }
            setState(EXPR_ARG);
        } else {
            setState(EXPR_BEG);
        }

        yaccValue = RopeConstants.TILDE;
        return RubyParser.tTILDE;
    }

    private ByteArrayBuilder numberBuffer = new ByteArrayBuilder(); // ascii is good enough.

    /** Parse a number from the input stream.
     *
     * @param c The first character of the number.
     * @return A int constant wich represents a token. */
    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings("SF")
    private int parseNumber(int c) {
        setState(EXPR_END);
        newtok(true);

        numberBuffer.clear();

        if (c == '-') {
            numberBuffer.append((char) c);
            c = nextc();
        } else if (c == '+') {
            // We don't append '+' since Java number parser gets confused
            c = nextc();
        }

        int nondigit = 0;

        if (c == '0') {
            int startLen = numberBuffer.getLength();

            switch (c = nextc()) {
                case 'x':
                case 'X': //  hexadecimal
                    c = nextc();
                    if (isHexChar(c)) {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') {
                                    break;
                                }
                                nondigit = c;
                            } else if (isHexChar(c)) {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.BAD_HEX_NUMBER, "Hexadecimal number without hex-digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 16, numberLiteralSuffix(SUFFIX_ALL));
                case 'b':
                case 'B': // binary
                    c = nextc();
                    if (c == '0' || c == '1') {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') {
                                    break;
                                }
                                nondigit = c;
                            } else if (c == '0' || c == '1') {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.EMPTY_BINARY_NUMBER, "Binary number without digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 2, numberLiteralSuffix(SUFFIX_ALL));
                case 'd':
                case 'D': // decimal
                    c = nextc();
                    if (Character.isDigit(c)) {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') {
                                    break;
                                }
                                nondigit = c;
                            } else if (Character.isDigit(c)) {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.EMPTY_BINARY_NUMBER, "Binary number without digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
                case 'o':
                case 'O':
                    c = nextc();
                case '0':
                case '1':
                case '2':
                case '3':
                case '4': //Octal
                case '5':
                case '6':
                case '7':
                case '_':
                    for (;; c = nextc()) {
                        if (c == '_') {
                            if (nondigit != '\0') {
                                break;
                            }

                            nondigit = c;
                        } else if (c >= '0' && c <= '7') {
                            nondigit = '\0';
                            numberBuffer.append((char) c);
                        } else {
                            break;
                        }
                    }
                    if (numberBuffer.getLength() > startLen) {
                        pushback(c);

                        if (nondigit != '\0') {
                            compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                        }

                        return getIntegerToken(numberBuffer.toString(), 8, numberLiteralSuffix(SUFFIX_ALL));
                    }
                case '8':
                case '9':
                    compile_error(SyntaxException.PID.BAD_OCTAL_DIGIT, "Illegal octal digit.");
                case '.':
                case 'e':
                case 'E':
                    numberBuffer.append('0');
                    break;
                default:
                    pushback(c);
                    numberBuffer.append('0');
                    return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
            }
        }

        boolean seen_point = false;
        boolean seen_e = false;

        for (;; c = nextc()) {
            switch (c) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    nondigit = '\0';
                    numberBuffer.append((char) c);
                    break;
                case '.':
                    if (nondigit != '\0') {
                        pushback(c);
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    } else if (seen_point || seen_e) {
                        pushback(c);
                        return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
                    } else {
                        int c2;
                        if (!Character.isDigit(c2 = nextc())) {
                            pushback(c2);
                            pushback('.');
                            if (c == '_') {
                                // Enebo: c can never be antrhign but '.'
                                // Why did I put this here?
                            } else {
                                return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
                            }
                        } else {
                            numberBuffer.append('.');
                            numberBuffer.append((char) c2);
                            seen_point = true;
                            nondigit = '\0';
                        }
                    }
                    break;
                case 'e':
                case 'E':
                    if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    } else if (seen_e) {
                        pushback(c);
                        return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
                    } else {
                        numberBuffer.append((char) c);
                        seen_e = true;
                        nondigit = c;
                        c = nextc();
                        if (c == '-' || c == '+') {
                            numberBuffer.append((char) c);
                            nondigit = c;
                        } else {
                            pushback(c);
                        }
                    }
                    break;
                case '_': //  '_' in number just ignored
                    if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    nondigit = c;
                    break;
                default:
                    pushback(c);
                    return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
            }
        }
    }

    private int getNumberToken(String number, boolean seen_e, boolean seen_point, int nondigit) {
        boolean isFloat = seen_e || seen_point;
        if (nondigit != '\0') {
            compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
        } else if (isFloat) {
            int suffix = numberLiteralSuffix(seen_e ? SUFFIX_I : SUFFIX_ALL);
            return getFloatToken(number, suffix);
        }
        return getIntegerToken(number, 10, numberLiteralSuffix(SUFFIX_ALL));
    }

    // Note: parser_tokadd_utf8 variant just for regexp literal parsing.  This variant is to be
    // called when string_literal and regexp_literal.
    public void readUTFEscapeRegexpLiteral(RopeBuilder buffer) {
        buffer.append('\\');
        buffer.append('u');

        if (peek('{')) { // handle \\u{...}
            do {
                buffer.append(nextc());
                if (scanHexLiteral(buffer, 6, false, "invalid Unicode escape") > 0x10ffff) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "invalid Unicode codepoint (too large)");
                }
            } while (peek(' ') || peek('\t'));

            int c = nextc();
            if (c != '}') {
                compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "unterminated Unicode escape");
            }

            buffer.append((char) c);
        } else { // handle \\uxxxx
            scanHexLiteral(buffer, 4, true, "Invalid Unicode escape");
        }
    }

    // MRI: parser_tokadd_utf8 sans regexp literal parsing
    public int readUTFEscape(RopeBuilder buffer, boolean stringLiteral, boolean symbolLiteral) {
        int codepoint;
        int c;

        if (peek('{')) { // handle \\u{...}
            do {
                nextc(); // Eat curly or whitespace
                codepoint = scanHex(6, false, "invalid Unicode escape");
                if (codepoint > 0x10ffff) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "invalid Unicode codepoint (too large)");
                }
                if (buffer != null) {
                    readUTF8EscapeIntoBuffer(codepoint, buffer, stringLiteral);
                }
            } while (peek(' ') || peek('\t'));

            c = nextc();
            if (c != '}') {
                compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "unterminated Unicode escape");
            }
        } else { // handle \\uxxxx
            codepoint = scanHex(4, true, "Invalid Unicode escape");
            if (buffer != null) {
                readUTF8EscapeIntoBuffer(codepoint, buffer, stringLiteral);
            }
        }

        return codepoint;
    }

    private void readUTF8EscapeIntoBuffer(int codepoint, RopeBuilder buffer, boolean stringLiteral) {
        if (codepoint >= 0x80) {
            buffer.setEncoding(UTF8Encoding.INSTANCE);
            if (stringLiteral) {
                tokaddmbc(codepoint, buffer);
            }
        } else if (stringLiteral) {
            buffer.append((char) codepoint);
        }
    }

    @SuppressWarnings("fallthrough")
    @SuppressFBWarnings("SF")
    public int readEscape() {
        int c = nextc();

        switch (c) {
            case '\\': // backslash
                return c;
            case 'n': // newline
                return '\n';
            case 't': // horizontal tab
                return '\t';
            case 'r': // carriage return
                return '\r';
            case 'f': // form feed
                return '\f';
            case 'v': // vertical tab
                return '\u000B';
            case 'a': // alarm(bell)
                return '\u0007';
            case 'e': // escape
                return '\u001B';
            case '0':
            case '1':
            case '2':
            case '3': // octal constant
            case '4':
            case '5':
            case '6':
            case '7':
                pushback(c);
                return scanOct(3);
            case 'x': // hex constant
                return scanHex(2, false, "Invalid escape character syntax");
            case 'b': // backspace
                return '\010';
            case 's': // space
                return ' ';
            case 'M':
                if ((c = nextc()) != '-') {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                } else if ((c = nextc()) == '\\') {
                    return (char) (readEscape() | 0x80);
                } else if (c == EOF) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                }
                return (char) ((c & 0xff) | 0x80);
            case 'C':
                if (nextc() != '-') {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                }
            case 'c':
                if ((c = nextc()) == '\\') {
                    c = readEscape();
                } else if (c == '?') {
                    return '\177';
                } else if (c == EOF) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                }
                return (char) (c & 0x9f);
            case EOF:
                compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
            default:
                return c;
        }
    }

    /** Read up to count hexadecimal digits and store those digits in a token numberBuffer. If strict is provided then
     * count number of hex digits must be present. If no digits can be read a syntax exception will be thrown. This will
     * also return the codepoint as a value so codepoint ranges can be checked. */
    private char scanHexLiteral(RopeBuilder buffer, int count, boolean strict, String errorMessage) {
        int i = 0;
        char hexValue = '\0';

        for (; i < count; i++) {
            int h1 = nextc();

            if (!isHexChar(h1)) {
                pushback(h1);
                break;
            }

            buffer.append(h1);

            hexValue <<= 4;
            hexValue |= Integer.parseInt(String.valueOf((char) h1), 16) & 15;
        }

        // No hex value after the 'x'.
        if (i == 0 || strict && count != i) {
            compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, errorMessage);
        }

        return hexValue;
    }

    /** Read up to count hexadecimal digits. If strict is provided then count number of hex digits must be present. If
     * no digits can be read a syntax exception will be thrown. */
    private int scanHex(int count, boolean strict, String errorMessage) {
        int i = 0;
        int hexValue = '\0';

        for (; i < count; i++) {
            int h1 = nextc();

            if (!isHexChar(h1)) {
                pushback(h1);
                break;
            }

            hexValue <<= 4;
            hexValue |= Integer.parseInt("" + (char) h1, 16) & 15;
        }

        // No hex value after the 'x'.
        if (i == 0 || (strict && count != i)) {
            compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, errorMessage);
        }

        return hexValue;
    }

    // --- LEXER STATES ---

    public static final int EXPR_BEG = 1;
    public static final int EXPR_END = 1 << 1;
    public static final int EXPR_ENDARG = 1 << 2;
    public static final int EXPR_ENDFN = 1 << 3;
    public static final int EXPR_ARG = 1 << 4;
    public static final int EXPR_CMDARG = 1 << 5;
    public static final int EXPR_MID = 1 << 6;
    public static final int EXPR_FNAME = 1 << 7;
    public static final int EXPR_DOT = 1 << 8;
    public static final int EXPR_CLASS = 1 << 9;
    public static final int EXPR_LABEL = 1 << 10;
    public static final int EXPR_LABELED = 1 << 11;
    public static final int EXPR_FITEM = 1 << 12;
    public static final int EXPR_VALUE = EXPR_BEG;
    public static final int EXPR_BEG_ANY = EXPR_BEG | EXPR_MID | EXPR_CLASS;
    public static final int EXPR_ARG_ANY = EXPR_ARG | EXPR_CMDARG;
    public static final int EXPR_END_ANY = EXPR_END | EXPR_ENDARG | EXPR_ENDFN;

    /** Current lexer state, see the constants above. */
    private int lex_state;
    /** Previous value of {@link #lex_state}, periodically reset to {@link #lex_state} at key points in the lexer (e.g.
     * at the start of the {@link #yylex()} loop. */
    private int last_state;

    // --- LINE + POSITION ---

    /** The current line being parsed */
    Rope lexb = null;
    // There use to be a variable called lex_lastline, but it was always identical to lexb.

    /** Always 0, except when parsing a UTF-8 BOM in parser_prepare() */
    private int lex_pbeg = 0;
    /** The current position, as an offset of lexb */
    private int lex_p = 0;
    /** The offset of lexb at which the line ends */
    int lex_pend = 0;

    /** Number of lines read in the source so far. Note that this is not necessarily the line index of {@link #lexb}, as
     * for instance heredocs need to "read ahead" before continuing to process characters on line where the heredoc
     * marker first appeared. */
    private int line_count = 0;
    /** Index of the current source line (the first line has index 1). This is not necessarily the same line as
     * {@link #lexb} and is used to track the logical source position to report in syntax errors, etc. */
    int ruby_sourceline = 1;
    /** Offset in the full source of the start of the current source line. */
    private int ruby_sourceline_char_offset = 0;
    /** Length of the current source line. */
    private int ruby_sourceline_char_length = 0;

    // --- SOURCE & RELATED PROPERTIES ---

    /** The stream of data examined by {@link #yylex()}. */
    private final LexerSource src;

    /** Whether a token was already seen while parsing the file. Used for magic comment processing. */
    private boolean tokenSeen = false;
    /** Does the source starts with a shebang? */
    private boolean has_shebang = false;
    /** See {@link #getEndPosition()}. */
    private int endPosition = -1;

    // --- TOKEN RELATED FIELDS ---

    /** Last token read by {@link #yylex()}. See constants in {@link RubyParser} (derived from {@code RubyParser.y} for
     * potential values. */
    private int token;
    /** Where the last token started. */
    private int tokp = 0;
    /** Value of last token which had a value associated with it. */
    private Object yaccValue;
    /** The character code range for the last token. */
    private CodeRange tokenCR;
    /** Snapshot of {@link #ruby_sourceline} for the last token. */
    private int ruby_sourceline_when_tokline_created;
    /** Source span for the whole line of the last token. */
    public SourceIndexLength tokline;

    // --- HEREDOC RELATED FIELDS

    /** The amount by which the current heredoc should be dedented when using a squiggly heredoc (<<~). This is
     * initially set to 0 (<<-) or MAX_VALUE (<<~) to act as a flag for whether the heredoc is indent-sensitive. Later
     * it is updated by calling {@link #update_heredoc_indent(int)}. */
    private int heredoc_indent = 0;

    /** This field records the indentation of the current line as its leading characters are supplied one by one to
     * {@link #update_heredoc_indent(int)}. It is not used elsewhere. When set to -1, signifies that we have seen the
     * first non-whitespace character on the line and that {@link #heredoc_indent} has been updated if needed. */
    private int heredoc_line_indent = 0;

    /** After a heredoc has been parsed, updated to point at the last line of the heredoc, so that the already-lexed
     * heredoc can be skipped, after we finish lexing the rest of the line where the heredoc end marker was declared. */
    private int heredoc_end = 0;

    // --- OTHER ---

    /** Was end-of-file reached? (or {@link #END_MARKER}) */
    public boolean eofp = false;

    protected int parenNest = 0;
    protected int braceNest = 0;
    public boolean commandStart;
    protected StackState conditionState = new StackState();
    protected StackState cmdArgumentState = new StackState();
    private Rope current_arg;
    public boolean inKwarg = false;
    protected int last_cr_line;
    private int leftParenBegin = 0;

    // -- END FIELDS ---

    // MRI: comment_at_top
    protected boolean comment_at_top() {
        int p = lex_pbeg;
        int pend = lex_p - 1;
        if (line_count != (has_shebang ? 2 : 1)) {
            return false;
        }
        while (p < pend) {
            if (!isAsciiSpace(p(p))) {
                return false;
            }
            p++;
        }
        return true;
    }

    /** Returns a rope for the current token, spanning from {@link #tokp} to {@link #lex_p}. */
    public Rope createTokenByteArrayView() {
        return parserRopeOperations.makeShared(lexb, tokp, lex_p - tokp);
    }

    @Deprecated
    public String createTokenString(int start) {
        return RopeOperations.decodeRopeSegment(lexb, start, lex_p - start);
    }

    @Deprecated
    public String createTokenString() {
        return createTokenString(tokp);
    }

    public Rope createTokenRope(int start) {
        return parserRopeOperations.makeShared(lexb, start, lex_p - start);
    }

    public Rope createTokenRope() {
        return createTokenRope(tokp);
    }

    /** Returns a substring rope equivalent equivalent to the given rope (which contains a single line), dedented by the
     * given width. */
    private Rope dedent_string(Rope string, int width) {
        int len = string.byteLength();
        int i, col = 0;

        for (i = 0; i < len && col < width; i++) {
            if (string.get(i) == ' ') {
                col++;
            } else if (string.get(i) == '\t') {
                int n = TAB_WIDTH * (col / TAB_WIDTH + 1);
                if (n > width) {
                    break;
                }
                col = n;
            } else {
                break;
            }
        }

        return parserRopeOperations.makeShared(string, i, len - i);
    }

    /** Sets the token start position ({@link #tokp}) to the current position ({@link #lex_p}). */
    private void flush() {
        tokp = lex_p;
    }

    public int getBraceNest() {
        return braceNest;
    }

    public StackState getCmdArgumentState() {
        return cmdArgumentState;
    }

    public StackState getConditionState() {
        return conditionState;
    }

    public Rope getCurrentArg() {
        return current_arg;
    }

    public String getCurrentLine() {
        return RopeOperations.decodeRope(lexb);
    }

    public Encoding getEncoding() {
        return src.getEncoding();
    }

    public String getFile() {
        return src.getSourcePath();
    }

    public int getLineOffset() {
        return src.getLineOffset();
    }

    public int getHeredocIndent() {
        return heredoc_indent;
    }

    public int getLeftParenBegin() {
        return leftParenBegin;
    }

    public int getState() {
        return lex_state;
    }

    public CodeRange getTokenCR() {
        if (tokenCR != null) {
            return tokenCR;
        } else {
            // The CR is null if the yaccValue is hard-coded inside the lexer, rather than determined by a token scan.
            // This can happen, for instance, if the lexer is consuming tokens that might correspond to operators and
            // then determines the characters are actually part of an identifier (see <code>lessThan</code> for such
            // a case).

            if (lexb.isAsciiOnly()) {
                // We don't know which substring of lexb was used for the token at this point, but if the source string
                // is CR_7BIT, all substrings must be CR_7BIT by definition.
                return CR_7BIT;
            } else {
                return CR_UNKNOWN;
            }
        }
    }

    public int incrementParenNest() {
        parenNest++;

        return parenNest;
    }

    /** Returns the end position in the source. This is usually -1 (the source code spans the whole source file), but
     * can be set to a number when the {@link #END_MARKER} ({@code __END__}) is encountered. */
    public int getEndPosition() {
        return endPosition;
    }

    public boolean isASCII(int c) {
        return c < 128;
    }

    // Return of 0 means failed to find anything.  Non-zero means return that from lexer.
    public int peekVariableName(int tSTRING_DVAR, int tSTRING_DBEG) {
        int c = nextc(); // byte right after #
        int significant = -1;
        switch (c) {
            case '$': {  // we unread back to before the $ so next lex can read $foo
                int c2 = nextc();

                if (c2 == '-') {
                    int c3 = nextc();

                    if (c3 == EOF) {
                        pushback(c3);
                        pushback(c2);
                        return 0;
                    }

                    significant = c3;                              // $-0 potentially
                    pushback(c3);
                    pushback(c2);
                    break;
                } else if (isGlobalCharPunct(c2)) {          // $_ potentially
                    setValue("#" + (char) c2);

                    pushback(c2);
                    pushback(c);
                    return tSTRING_DVAR;
                }

                significant = c2;                                  // $FOO potentially
                pushback(c2);
                break;
            }
            case '@': {  // we unread back to before the @ so next lex can read @foo
                int c2 = nextc();

                if (c2 == '@') {
                    int c3 = nextc();

                    if (c3 == EOF) {
                        pushback(c3);
                        pushback(c2);
                        return 0;
                    }

                    significant = c3;                                // #@@foo potentially
                    pushback(c3);
                    pushback(c2);
                    break;
                }

                significant = c2;                                    // #@foo potentially
                pushback(c2);
                break;
            }
            case '{':
                //setBraceNest(getBraceNest() + 1);
                setValue("#" + (char) c);
                commandStart = true;
                return tSTRING_DBEG;
            default:
                return 0;
        }

        // We found #@, #$, #@@ but we don't know what at this point (check for valid chars).
        if (significant != -1 && Character.isAlphabetic(significant) || significant == '_') {
            pushback(c);
            setValue("#" + significant);
            return tSTRING_DVAR;
        }

        return 0;
    }

    public boolean isGlobalCharPunct(int c) {
        switch (c) {
            case '_':
            case '~':
            case '*':
            case '$':
            case '?':
            case '!':
            case '@':
            case '/':
            case '\\':
            case ';':
            case ',':
            case '.':
            case '=':
            case ':':
            case '<':
            case '>':
            case '\"':
            case '-':
            case '&':
            case '`':
            case '\'':
            case '+':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
            case '0':
                return true;
        }
        return isIdentifierChar(c);
    }

    /** This is a valid character for an identifier?
     *
     * @param c is character to be compared
     * @return whether c is an identifier or not
     *
     *         mri: is_identchar */
    public boolean isIdentifierChar(int c) {
        return c != EOF && (Character.isLetterOrDigit(c) || c == '_' || !isASCII(c));
    }

    /** Sets the current position in the current line ({@link #lex_p}) to the end of the line ({@link #lex_pend}). */
    public void lex_goto_eol() {
        lex_p = lex_pend;
    }

    protected void magicCommentEncoding(Rope encoding) {
        if (!comment_at_top()) {
            return;
        }

        setEncoding(encoding);
    }

    /** Reset the starting token position.
     * 
     * @param unreadOnce whether we should assume the position starts 1 before the current lexer position */
    public void newtok(boolean unreadOnce) {

        // This significantly differs from MRI in that we are just mucking with lex_p pointers and not allocating our
        // own buffer (or using bytelist). In most cases this does not matter much but for ripper or a place where we
        // remove actual source characters (like extra '"') then this acts differently.

        // NOTE(norswap, 28 Jan 2021): Since TruffleRuby does not implement Ripper via the usual lexer & parser, the
        //     above concern can be safely ignored.

        tokline = getPosition();
        ruby_sourceline_when_tokline_created = ruby_sourceline;

        // We assume all idents are valid (or 7BIT if ASCII-compatible), until they aren't.
        tokenCR = src.getEncoding().isAsciiCompatible() ? CodeRange.CR_7BIT : CodeRange.CR_VALID;

        tokp = lex_p - (unreadOnce ? 1 : 0);
    }

    protected int numberLiteralSuffix(int mask) {
        int c = nextc();

        if (c == 'i') {
            return (mask & SUFFIX_I) != 0 ? mask & SUFFIX_I : 0;
        }

        if (c == 'r') {
            int result = 0;
            if ((mask & SUFFIX_R) != 0) {
                result |= (mask & SUFFIX_R);
            }

            if (peek('i') && (mask & SUFFIX_I) != 0) {
                c = nextc();
                result |= (mask & SUFFIX_I);
            }

            return result;
        }
        if (c == '.') {
            int c2 = nextc();
            if (Character.isDigit(c2)) {
                compile_error("unexpected fraction part after numeric literal");
                do { // Ripper does not stop so we follow MRI here and read over next word...
                    c2 = nextc();
                } while (isIdentifierChar(c2));
            } else {
                pushback(c2);
            }
        }
        pushback(c);

        return 0;
    }

    public void parser_prepare() {
        int c = nextc();

        switch (c) {
            case '#':
                if (peek('!')) {
                    has_shebang = true;
                }
                break;
            case 0xef:
                if (lex_pend - lex_p >= 2 && p(lex_p) == 0xbb && p(lex_p + 1) == 0xbf) {
                    setEncoding(UTF8Encoding.INSTANCE);
                    lex_p += 2;
                    lex_pbeg = lex_p;
                    return;
                }
                break;
            case EOF:
                return;
        }
        pushback(c);
    }

    public int p(int offset) {
        return lexb.getBytes().get(offset) & 0xff;
    }

    public boolean peek(int c) {
        return peek(c, 0);
    }

    protected boolean peek(int c, int n) {
        return lex_p + n < lex_pend && p(lex_p + n) == c;
    }

    public int precise_mbclen() {
        // A broken string has at least one character with an invalid byte sequence. It doesn't matter which one we
        // report as invalid because the error reported to the user will only note the start position of the string.
        if (lexb.getCodeRange() == CR_BROKEN) {
            return -1;
        }

        // A substring of a single-byte optimizable string is always single-byte optimizable, so there's no need
        // to actually perform the substring operation.
        if (lexb.isSingleByteOptimizable()) {
            return 1;
        }

        // we subtract one since we have read past first byte by time we are calling this.
        final int start = lex_p - 1;
        final int end = lex_pend;
        final int length = end - start;

        // Otherwise, take the substring and see if that new string is single-byte optimizable.
        final Rope rope = parserRopeOperations.makeShared(lexb, start, length);
        if (rope.isSingleByteOptimizable()) {
            return 1;
        }

        // Barring all else, we must inspect the bytes for the substring.
        return StringSupport
                .characterLength(src.getEncoding(), rope.getCodeRange(), rope.getBytes(), 0, rope.byteLength());
    }

    public void pushback(int c) {
        if (c == -1) {
            return;
        }

        lex_p--;

        if (lex_p > lex_pbeg && p(lex_p) == '\n' && p(lex_p - 1) == '\r') {
            lex_p--;
        }
    }

    public void superReset() {
        braceNest = 0;
        commandStart = true;
        heredoc_indent = 0;
        heredoc_line_indent = 0;
        last_cr_line = -1;
        parenNest = 0;
        ruby_sourceline = 1;
        // No updateLineOffset because it's about to be done anyway in the only caller of this method
        token = 0;
        tokenSeen = false;
        tokp = 0;
        yaccValue = null;

        setState(0);
        resetStacks();
    }

    public void resetStacks() {
        conditionState.reset();
        cmdArgumentState.reset();
    }

    protected char scanOct(int count) {
        char value = '\0';

        for (int i = 0; i < count; i++) {
            int c = nextc();

            if (!isOctChar(c)) {
                pushback(c);
                break;
            }

            value <<= 3;
            value |= Integer.parseInt(String.valueOf((char) c), 8);
        }

        return value;
    }

    public void setCurrentArg(Rope current_arg) {
        this.current_arg = current_arg;
    }

    public void setEncoding(Encoding encoding) {
        src.setEncoding(encoding);
        lexb = parserRopeOperations.withEncoding(lexb, encoding);
    }

    protected void set_file_encoding(int str, int send) {
        boolean sep = false;
        for (;;) {
            if (send - str <= 6) {
                return;
            }

            switch (p(str + 6)) {
                case 'C':
                case 'c':
                    str += 6;
                    continue;
                case 'O':
                case 'o':
                    str += 5;
                    continue;
                case 'D':
                case 'd':
                    str += 4;
                    continue;
                case 'I':
                case 'i':
                    str += 3;
                    continue;
                case 'N':
                case 'n':
                    str += 2;
                    continue;
                case 'G':
                case 'g':
                    str += 1;
                    continue;
                case '=':
                case ':':
                    sep = true;
                    str += 6;
                    break;
                default:
                    str += 6;
                    if (Character.isSpaceChar(p(str))) {
                        break;
                    }
                    continue;
            }
            if (RopeOperations.caseInsensitiveCmp(parserRopeOperations.makeShared(lexb, str - 6, 6), CODING) == 0) {
                break;
            }
        }

        for (;;) {
            do {
                str++;
                if (str >= send) {
                    return;
                }
            } while (Character.isSpaceChar(p(str)));
            if (sep) {
                break;
            }

            if (p(str) != '=' && p(str) != ':') {
                return;
            }
            sep = true;
            str++;
        }

        int beg = str;
        while ((p(str) == '-' || p(str) == '_' || Character.isLetterOrDigit(p(str))) && ++str < send) {
        }
        setEncoding(parserRopeOperations.makeShared(lexb, beg, str - beg));
    }

    public void setHeredocLineIndent(int heredoc_line_indent) {
        this.heredoc_line_indent = heredoc_line_indent;
    }

    /** Sets {@link #heredoc_indent}. Usually used to reset the indent to 0 in the parser after we've finished parsing a
     * heredoc ({@link RubyParser#tSTRING_END} has been seen). */
    public void setHeredocIndent(int heredoc_indent) {
        this.heredoc_indent = heredoc_indent;
    }

    public void setBraceNest(int nest) {
        braceNest = nest;
    }

    public void setLeftParenBegin(int value) {
        leftParenBegin = value;
    }

    public void setState(int state) {
        this.lex_state = state;
    }

    /** Sets the value associated with the current token. */
    public void setValue(Object yaccValue) {
        this.yaccValue = yaccValue;
    }

    protected boolean strncmp(Rope one, Rope two, int length) {
        if (one.byteLength() != two.byteLength() && (one.byteLength() < length || two.byteLength() < length)) {
            return false;
        }

        return ArrayUtils.regionEquals(one.getBytes(), 0, two.getBytes(), 0, length);
    }

    public void tokAdd(int first_byte, RopeBuilder buffer) {
        buffer.append((byte) first_byte);
    }

    public void tokCopy(int length, RopeBuilder buffer) {
        buffer.append(Bytes.extractRange(lexb.getBytes(), lex_p - length, lex_p));
    }

    public boolean tokadd_ident(int c) {
        do {
            if (!tokadd_mbchar(c)) {
                return false;
            }
            c = nextc();
        } while (isIdentifierChar(c));
        pushback(c);

        return true;
    }

    // mri: parser_tokadd_mbchar
    /** This differs from MRI in a few ways. This version does not apply value to a separate token buffer. It is for use
     * when we know we will not be omitting or including ant non-syntactical characters. Use tokadd_mbchar(int,
     * ByteArrayView) if the string differs from actual source. Secondly, this returns a boolean instead of the first
     * byte passed. MRI only used the return value as a success/failure code to return EOF.
     *
     * Because this version does not use a separate token buffer we only just increment lex_p. When we reach end of the
     * token it will just get the bytes directly from source directly. */
    public boolean tokadd_mbchar(int firstByte) {
        int length = precise_mbclen();

        if (length <= 0) {
            compile_error("invalid multibyte char (" + getEncoding() + ")");
        } else if (length > 1 || (tokenCR == CR_7BIT && !isASCII(firstByte))) {
            tokenCR = CodeRange.CR_VALID;
        }

        lex_p += length - 1;  // we already read first byte so advance pointer for remainder

        return true;
    }

    // mri: parser_tokadd_mbchar
    public boolean tokadd_mbchar(int firstByte, RopeBuilder buffer) {
        int length = precise_mbclen();

        if (length <= 0) {
            compile_error("invalid multibyte char (" + getEncoding() + ")");
        }

        tokAdd(firstByte, buffer);                  // add first byte since we have it.
        lex_p += length - 1;                         // we already read first byte so advance pointer for remainder
        if (length > 1) {
            tokCopy(length - 1, buffer); // copy next n bytes over.
        }

        return true;
    }

    /** This looks deceptively like tokadd_mbchar(int, ByteArrayView) but it differs in that it uses the bytelists
     * encoding and the first parameter is a full codepoint and not the first byte of a mbc sequence. */
    public void tokaddmbc(int codepoint, RopeBuilder buffer) {
        Encoding encoding = buffer.getEncoding();
        int length = encoding.codeToMbcLength(codepoint);
        final Bytes bytes = Bytes.copyOf(buffer.getBytes(), buffer.getLength() + length);
        encoding.codeToMbc(codepoint, bytes.array, bytes.offset + buffer.getLength());
        buffer.clear();
        buffer.append(bytes);
    }

    /** Updates {@link #heredoc_line_indent} and {@link #heredoc_indent} based on the current value of these two
     * variables and of the character {@code c} read on the current line.
     *
     * <p>
     * This always returns false if {@link #heredoc_line_indent} is -1, and the only effect to to reset
     * {@link #heredoc_line_indent} to 0 if if the character is a newline.
     *
     * <p>
     * Otherwise, if the character is a space or a tab, increments {@link #heredoc_line_indent} with its width and
     * return true. In every other case, false is returned. Refer to the source code for more details.
     *
     * <p>
     * Return false without further actions for newlines.
     *
     * <p>
     * Otherwise, this is the first non-whitespace character, and {@link #heredoc_indent} is set to
     * {@link #heredoc_line_indent} if the later is smaller. {@link #heredoc_line_indent} is set to -1. */
    public boolean update_heredoc_indent(int c) {
        if (heredoc_line_indent == -1) {
            if (c == '\n') {
                heredoc_line_indent = 0;
            }
            return false;
        } else if (c == ' ') {
            heredoc_line_indent++;
            return true;
        } else if (c == '\t') {
            int w = (heredoc_line_indent / TAB_WIDTH) + 1;
            heredoc_line_indent = w * TAB_WIDTH;
            return true;
        } else if (c == '\n') {
            return false;
        } else {
            if (heredoc_indent > heredoc_line_indent) {
                heredoc_indent = heredoc_line_indent;
            }
            heredoc_line_indent = -1;
            return false;
        }
    }

    public void validateFormalIdentifier(Rope identifier) {
        if (isFirstCodepointUppercase(identifier)) {
            compile_error("formal argument cannot be a constant");
        }

        int first = identifier.get(0) & 0xFF;

        switch (first) {
            case '@':
                if (identifier.get(1) == '@') {
                    compile_error("formal argument cannot be a class variable");
                } else {
                    compile_error("formal argument cannot be an instance variable");
                }
                break;
            case '$':
                compile_error("formal argument cannot be a global variable");
                break;
            default:
                // This mechanism feels a tad dicey but at this point we are dealing with a valid
                // method name at least so we should not need to check the entire string...
                byte last = identifier.get(identifier.byteLength() - 1);

                if (last == '=' || last == '?' || last == '!') {
                    compile_error("formal argument must be local variable");
                }
        }
    }

    /** Value of last token (if it is a token which has a value).
     *
     * @return value of last value-laden token */
    public Object value() {
        return yaccValue;
    }

    protected void warn_balanced(int c, boolean spaceSeen, String op, String syn) {
        if (!isLexState(last_state, EXPR_CLASS | EXPR_DOT | EXPR_FNAME | EXPR_ENDFN | EXPR_ENDARG) && spaceSeen &&
                !isAsciiSpace(c)) {
            ambiguousOperator(op, syn);
        }
    }

    /** Is the lexer position one past the begin of the current line? */
    public boolean was_bol() {
        return lex_p == lex_pbeg + 1;
    }

    /** Indicates whether the current line matches the given marker, after stripping away leading whitespace if
     * {@code indent} is true. Does not advance the input position ({@link #lex_p}). */
    boolean whole_match_p(Rope eos, boolean indent) {
        int len = eos.byteLength();
        int p = lex_pbeg;

        if (indent) {
            for (int i = 0; i < lex_pend; i++) {
                if (!Character.isWhitespace(p(i + p))) {
                    p += i;
                    break;
                }
            }
        }
        int n = lex_pend - (p + len);
        if (n < 0) {
            return false;
        }
        if (n > 0 && p(p + len) != '\n') {
            if (p(p + len) != '\r') {
                return false;
            }
            if (n == 1 || p(p + len + 1) != '\n') {
                return false;
            }
        }

        return strncmp(eos, parserRopeOperations.makeShared(lexb, p, len), len);
    }

    public static final int TAB_WIDTH = 8;

    // ruby constants for strings (should this be moved somewhere else?)
    public static final int STR_FUNC_ESCAPE = 0x01;
    public static final int STR_FUNC_EXPAND = 0x02;
    public static final int STR_FUNC_REGEXP = 0x04;
    public static final int STR_FUNC_QWORDS = 0x08;
    public static final int STR_FUNC_SYMBOL = 0x10;
    // When the heredoc identifier specifies <<-EOF that indents before ident. are ok (the '-').
    public static final int STR_FUNC_INDENT = 0x20;
    public static final int STR_FUNC_LABEL = 0x40;
    public static final int STR_FUNC_LIST = 0x4000;
    public static final int STR_FUNC_TERM = 0x8000;

    public static final int str_label = STR_FUNC_LABEL;
    public static final int str_squote = 0;
    public static final int str_dquote = STR_FUNC_EXPAND;
    public static final int str_xquote = STR_FUNC_EXPAND;
    public static final int str_regexp = STR_FUNC_REGEXP | STR_FUNC_ESCAPE | STR_FUNC_EXPAND;
    public static final int str_sword = STR_FUNC_QWORDS | STR_FUNC_LIST;
    public static final int str_dword = STR_FUNC_QWORDS | STR_FUNC_EXPAND | STR_FUNC_LIST;
    public static final int str_ssym = STR_FUNC_SYMBOL;
    public static final int str_dsym = STR_FUNC_SYMBOL | STR_FUNC_EXPAND;

    public static final int EOF = -1; // 0 in MRI

    public static final Rope END_MARKER = RopeOperations
            .create(new Bytes(new byte[]{ '_', '_', 'E', 'N', 'D', '_', '_' }), ASCIIEncoding.INSTANCE, CR_7BIT);
    public static final Rope BEGIN_DOC_MARKER = RopeOperations
            .create(new Bytes(new byte[]{ 'b', 'e', 'g', 'i', 'n' }), ASCIIEncoding.INSTANCE, CR_7BIT);
    public static final Rope END_DOC_MARKER = RopeOperations
            .create(new Bytes(new byte[]{ 'e', 'n', 'd' }), ASCIIEncoding.INSTANCE, CR_7BIT);
    public static final Rope CODING = RopeOperations
            .create(new Bytes(new byte[]{ 'c', 'o', 'd', 'i', 'n', 'g' }), ASCIIEncoding.INSTANCE, CR_7BIT);

    public static final int SUFFIX_R = 1 << 0;
    public static final int SUFFIX_I = 1 << 1;
    public static final int SUFFIX_ALL = 3;

    /** @param c the character to test
     * @return true if character is a hex value (0-9a-f) */
    public static boolean isHexChar(int c) {
        return Character.isDigit(c) || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F');
    }

    public static boolean isLexState(int state, int mask) {
        return (mask & state) != 0;
    }

    protected boolean isLexStateAll(int state, int mask) {
        return (mask & state) == mask;
    }

    protected boolean isARG() {
        return isLexState(lex_state, EXPR_ARG_ANY);
    }

    protected boolean isBEG() {
        return isLexState(lex_state, EXPR_BEG_ANY) || isLexStateAll(lex_state, EXPR_ARG | EXPR_LABELED);
    }

    protected boolean isEND() {
        return isLexState(lex_state, EXPR_END_ANY);
    }

    protected boolean isLabelPossible(boolean commandState) {
        return (isLexState(lex_state, EXPR_LABEL | EXPR_ENDFN) && !commandState) || isARG();
    }

    protected boolean isLabelSuffix() {
        return peek(':') && !peek(':', 1);
    }

    protected boolean isAfterOperator() {
        return isLexState(lex_state, EXPR_FNAME | EXPR_DOT);
    }

    protected boolean isNext_identchar() {
        int c = nextc();
        pushback(c);

        return c != EOF && (Character.isLetterOrDigit(c) || c == '_');
    }

    /** @param c the character to test
     * @return true if character is an octal value (0-7) */
    public static boolean isOctChar(int c) {
        return '0' <= c && c <= '7';
    }

    public static boolean isSpace(int c) {
        return c == ' ' || ('\t' <= c && c <= '\r');
    }

    protected boolean isSpaceArg(int c, boolean spaceSeen) {
        return isARG() && spaceSeen && !Character.isWhitespace(c);
    }

    /** Encoding-aware (including multi-byte encodings) check of first codepoint of a given rope, usually to determine
     * if it is a constant */
    private boolean isFirstCodepointUppercase(Rope rope) {
        Encoding ropeEncoding = rope.encoding;
        int firstByte = rope.get(0) & 0xFF;

        if (ropeEncoding.isAsciiCompatible() && isASCII(firstByte)) {
            return StringSupport.isAsciiUppercase((byte) firstByte);
        } else {
            Bytes ropeBytes = rope.getBytes();
            int firstCharacter = ropeEncoding
                    .mbcToCode(ropeBytes.array, ropeBytes.offset, ropeBytes.offset + ropeBytes.length);
            return ropeEncoding.isUpper(firstCharacter);
        }
    }

    public String getLocation() {
        return getFile() + ":" + ruby_sourceline;
    }

    @Override
    public String toString() {
        return super.toString() + " @ " + getLocation();
    }
}
