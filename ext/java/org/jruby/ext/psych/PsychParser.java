/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2010 Charles O Nutter <headius@headius.com>
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
package org.jruby.ext.psych;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.util.Map;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF16BEEncoding;
import org.jcodings.specific.UTF16LEEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jcodings.unicode.UnicodeEncoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyEncoding;
import org.jruby.RubyFixnum;
import org.jruby.RubyIO;
import org.jruby.RubyKernel;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import static org.jruby.ext.psych.PsychLibrary.YAMLEncoding.*;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.IOInputStream;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.DocumentEndEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.Event.ID;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.ReaderException;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerException;

import static org.jruby.runtime.Helpers.arrayOf;
import static org.jruby.runtime.Helpers.invoke;
import org.jruby.util.ByteList;

public class PsychParser extends RubyObject {

    private static final Logger LOG = LoggerFactory.getLogger(PsychParser.class);
    
    public static void initPsychParser(Ruby runtime, RubyModule psych) {
        RubyClass psychParser = runtime.defineClassUnder("Parser", runtime.getObject(), new ObjectAllocator() {
            public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
                return new PsychParser(runtime, klazz);
            }
        }, psych);

        runtime.getLoadService().require("psych/syntax_error");
        psychParser.defineConstant("ANY", runtime.newFixnum(YAML_ANY_ENCODING.ordinal()));
        psychParser.defineConstant("UTF8", runtime.newFixnum(YAML_UTF8_ENCODING.ordinal()));
        psychParser.defineConstant("UTF16LE", runtime.newFixnum(YAML_UTF16LE_ENCODING.ordinal()));
        psychParser.defineConstant("UTF16BE", runtime.newFixnum(YAML_UTF16BE_ENCODING.ordinal()));

        psychParser.defineAnnotatedMethods(PsychParser.class);
    }

    public PsychParser(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    private IRubyObject stringOrNilFor(ThreadContext context, String value, boolean tainted) {
        if (value == null) return context.nil;

        return stringFor(context, value, tainted);
    }
    
    private RubyString stringFor(ThreadContext context, String value, boolean tainted) {
        Ruby runtime = context.runtime;

        Encoding encoding = runtime.getDefaultInternalEncoding();
        if (encoding == null) {
            encoding = UTF8Encoding.INSTANCE;
        }

        Charset charset = RubyEncoding.UTF8;
        if (encoding.getCharset() != null) {
            charset = encoding.getCharset();
        }

        ByteList bytes = new ByteList(value.getBytes(charset), encoding);
        RubyString string = RubyString.newString(runtime, bytes);
        
        string.setTaint(tainted);
        
        return string;
    }
    
    private StreamReader readerFor(ThreadContext context, IRubyObject yaml) {
        if (yaml instanceof RubyString) {
            ByteList byteList = ((RubyString)yaml).getByteList();
            Encoding enc = byteList.getEncoding();

            // if not unicode, transcode to UTF8
            if (!(enc instanceof UnicodeEncoding)) {
                byteList = EncodingUtils.strConvEnc(context, byteList, enc, UTF8Encoding.INSTANCE);
                enc = UTF8Encoding.INSTANCE;
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(byteList.getUnsafeBytes(), byteList.getBegin(), byteList.getRealSize());

            Charset charset = enc.getCharset();

            assert charset != null : "charset for encoding " + enc + " should not be null";

            InputStreamReader isr = new InputStreamReader(bais, charset);

            return new StreamReader(isr);
        }

        // fall back on IOInputStream, using default charset
        if (yaml.respondsTo("read")) {
            Charset charset = null;
            if (yaml instanceof RubyIO) {
                Encoding enc = ((RubyIO) yaml).getReadEncoding();
                charset = enc.getCharset();

                // libyaml treats non-utf encodings as utf-8 and hopes for the best.
                if (!(enc instanceof UTF8Encoding)  && !(enc instanceof UTF16LEEncoding) && !(enc instanceof UTF16BEEncoding)) {
                    charset = UTF8Encoding.INSTANCE.getCharset();
                }
            }
            if (charset == null) {
                // If we can't get it from the IO or it doesn't have a charset, fall back on UTF-8
                charset = UTF8Encoding.INSTANCE.getCharset();
            }
            CharsetDecoder decoder = charset.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onMalformedInput(CodingErrorAction.REPORT);

            return new StreamReader(new InputStreamReader(new IOInputStream(yaml), decoder));
        } else {
            Ruby runtime = context.runtime;

            throw runtime.newTypeError(yaml, runtime.getIO());
        }
    }

    @JRubyMethod(name = "_native_parse")
    public IRubyObject parse(ThreadContext context, IRubyObject handler, IRubyObject yaml, IRubyObject path) {
        Ruby runtime = context.runtime;
        boolean tainted = yaml.isTaint() || yaml instanceof RubyIO;

        try {
            parser = new ParserImpl(readerFor(context, yaml));

            if (path.isNil() && yaml.respondsTo("path")) {
                path = yaml.callMethod(context, "path");
            }

            while (true) {
                event = parser.getEvent();

                IRubyObject start_line = runtime.newFixnum(event.getStartMark().getLine());
                IRubyObject start_column = runtime.newFixnum(event.getStartMark().getColumn());
                IRubyObject end_line = runtime.newFixnum(event.getEndMark().getLine());
                IRubyObject end_column = runtime.newFixnum(event.getEndMark().getColumn());
                invoke(context, handler, "event_location", start_line, start_column, end_line, end_column);

                // FIXME: Event should expose a getID, so it can be switched
                if (event.is(ID.StreamStart)) {
                    invoke(context, handler, "start_stream", runtime.newFixnum(YAML_ANY_ENCODING.ordinal()));
                } else if (event.is(ID.DocumentStart)) {
                    handleDocumentStart(context, (DocumentStartEvent) event, tainted, handler);
                } else if (event.is(ID.DocumentEnd)) {
                    IRubyObject notExplicit = runtime.newBoolean(!((DocumentEndEvent) event).getExplicit());
                    
                    invoke(context, handler, "end_document", notExplicit);
                } else if (event.is(ID.Alias)) {
                    IRubyObject alias = stringOrNilFor(context, ((AliasEvent)event).getAnchor(), tainted);

                    invoke(context, handler, "alias", alias);
                } else if (event.is(ID.Scalar)) {
                    handleScalar(context, (ScalarEvent) event, tainted, handler);
                } else if (event.is(ID.SequenceStart)) {
                    handleSequenceStart(context,(SequenceStartEvent) event, tainted, handler);
                } else if (event.is(ID.SequenceEnd)) {
                    invoke(context, handler, "end_sequence");
                } else if (event.is(ID.MappingStart)) {
                    handleMappingStart(context, (MappingStartEvent) event, tainted, handler);
                } else if (event.is(ID.MappingEnd)) {
                    invoke(context, handler, "end_mapping");
                } else if (event.is(ID.StreamEnd)) {
                    invoke(context, handler, "end_stream");
                    
                    break;
                }
            }
        } catch (ParserException pe) {
            parser = null;
            raiseParserException(context, yaml, pe, path);

        } catch (ScannerException se) {
            parser = null;
            StringBuilder message = new StringBuilder("syntax error");
            if (se.getProblemMark() != null) {
                message.append(se.getProblemMark().toString());
            }
            raiseParserException(context, yaml, se, path);

        } catch (ReaderException re) {
            parser = null;
            raiseParserException(context, yaml, re, path);

        } catch (YAMLException ye) {
            Throwable cause = ye.getCause();

            if (cause instanceof MalformedInputException) {
                // failure due to improperly encoded input
                raiseParserException(context, yaml, (MalformedInputException) cause, path);
            }

            throw ye;

        } catch (Throwable t) {
            Helpers.throwException(t);
            return this;
        }

        return this;
    }
    
    private void handleDocumentStart(ThreadContext context, DocumentStartEvent dse, boolean tainted, IRubyObject handler) {
        Ruby runtime = context.runtime;
        DumperOptions.Version _version = dse.getVersion();
        IRubyObject version = _version == null ?
            RubyArray.newArray(runtime) :
            RubyArray.newArray(runtime, runtime.newFixnum(_version.major()), runtime.newFixnum(_version.minor()));
        
        Map<String, String> tagsMap = dse.getTags();
        RubyArray tags = RubyArray.newArray(runtime);
        if (tagsMap != null && tagsMap.size() > 0) {
            for (Map.Entry<String, String> tag : tagsMap.entrySet()) {
                IRubyObject key   = stringFor(context, tag.getKey(), tainted);
                IRubyObject value = stringFor(context, tag.getValue(), tainted);

                tags.append(RubyArray.newArray(runtime, key, value));
            }
        }
        IRubyObject notExplicit = runtime.newBoolean(!dse.getExplicit());

        invoke(context, handler, "start_document", version, tags, notExplicit);
    }
    
    private void handleMappingStart(ThreadContext context, MappingStartEvent mse, boolean tainted, IRubyObject handler) {
        Ruby runtime = context.runtime;
        IRubyObject anchor = stringOrNilFor(context, mse.getAnchor(), tainted);
        IRubyObject tag = stringOrNilFor(context, mse.getTag(), tainted);
        IRubyObject implicit = runtime.newBoolean(mse.getImplicit());
        IRubyObject style = runtime.newFixnum(translateFlowStyle(mse.getFlowStyle()));

        invoke(context, handler, "start_mapping", anchor, tag, implicit, style);
    }
        
    private void handleScalar(ThreadContext context, ScalarEvent se, boolean tainted, IRubyObject handler) {
        Ruby runtime = context.runtime;

        IRubyObject anchor = stringOrNilFor(context, se.getAnchor(), tainted);
        IRubyObject tag = stringOrNilFor(context, se.getTag(), tainted);
        IRubyObject plain_implicit = runtime.newBoolean(se.getImplicit().canOmitTagInPlainScalar());
        IRubyObject quoted_implicit = runtime.newBoolean(se.getImplicit().canOmitTagInNonPlainScalar());
        IRubyObject style = runtime.newFixnum(translateStyle(se.getScalarStyle()));
        IRubyObject val = stringFor(context, se.getValue(), tainted);

        invoke(context, handler, "scalar", val, anchor, tag, plain_implicit,
                quoted_implicit, style);
    }
    
    private void handleSequenceStart(ThreadContext context, SequenceStartEvent sse, boolean tainted, IRubyObject handler) {
        Ruby runtime = context.runtime;
        IRubyObject anchor = stringOrNilFor(context, sse.getAnchor(), tainted);
        IRubyObject tag = stringOrNilFor(context, sse.getTag(), tainted);
        IRubyObject implicit = runtime.newBoolean(sse.getImplicit());
        IRubyObject style = runtime.newFixnum(translateFlowStyle(sse.getFlowStyle()));

        invoke(context, handler, "start_sequence", anchor, tag, implicit, style);
    }

    private static void raiseParserException(ThreadContext context, IRubyObject yaml, ReaderException re, IRubyObject rbPath) {
        Ruby runtime;
        RubyClass se;
        IRubyObject exception;

        runtime = context.runtime;
        se = (RubyClass)runtime.getModule("Psych").getConstant("SyntaxError");

        exception = se.newInstance(context,
                new IRubyObject[] {
                    rbPath,
                    runtime.newFixnum(0),
                    runtime.newFixnum(0),
                    runtime.newFixnum(re.getPosition()),
                    (null == re.getName() ? runtime.getNil() : runtime.newString(re.getName())),
                    (null == re.toString() ? runtime.getNil() : runtime.newString(re.toString()))
                },
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static void raiseParserException(ThreadContext context, IRubyObject yaml, MarkedYAMLException mye, IRubyObject rbPath) {
        Ruby runtime;
        Mark mark;
        RubyClass se;
        IRubyObject exception;

        runtime = context.runtime;
        se = (RubyClass)runtime.getModule("Psych").getConstant("SyntaxError");

        mark = mye.getProblemMark();

        exception = se.newInstance(context,
                new IRubyObject[] {
                    rbPath,
                    runtime.newFixnum(mark.getLine() + 1),
                    runtime.newFixnum(mark.getColumn() + 1),
                    runtime.newFixnum(mark.getIndex()),
                    (null == mye.getProblem() ? runtime.getNil() : runtime.newString(mye.getProblem())),
                    (null == mye.getContext() ? runtime.getNil() : runtime.newString(mye.getContext()))
                },
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static void raiseParserException(ThreadContext context, IRubyObject yaml, MalformedInputException mie, IRubyObject rbPath) {
        Ruby runtime;
        Mark mark;
        RubyClass se;
        IRubyObject exception;

        runtime = context.runtime;
        se = (RubyClass)runtime.getModule("Psych").getConstant("SyntaxError");

        mie.getInputLength();

        exception = se.newInstance(context,
                arrayOf(
                        rbPath,
                        runtime.newFixnum(-1),
                        runtime.newFixnum(-1),
                        runtime.newFixnum(mie.getInputLength()),
                        runtime.getNil(),
                        runtime.getNil()
                ),
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static int translateStyle(DumperOptions.ScalarStyle style) {
        if (style == null) return 0; // any

        switch (style) {
            case PLAIN: return 1; // plain
            case SINGLE_QUOTED: return 2; // single-quoted
            case DOUBLE_QUOTED: return 3; // double-quoted
            case LITERAL: return 4; // literal
            case FOLDED: return 5; // folded
            default: return 0; // any
        }
    }
    
    private static int translateFlowStyle(DumperOptions.FlowStyle flowStyle) {
        switch (flowStyle) {
            case AUTO: return 0;
            case BLOCK: return 1;
            case FLOW:
            default: return 2;
        }
    }

    @JRubyMethod
    public IRubyObject mark(ThreadContext context) {
        Ruby runtime = context.runtime;

        Event event = null;

        if (parser != null) {
            event = parser.peekEvent();

            if (event == null) event = this.event;
        }

        if (event == null) {
            return ((RubyClass)context.runtime.getClassFromPath("Psych::Parser::Mark")).newInstance(
                    context,
                    RubyFixnum.zero(runtime),
                    RubyFixnum.zero(runtime),
                    RubyFixnum.zero(runtime),
                    Block.NULL_BLOCK
            );
        }

        Mark mark = event.getStartMark();

        return ((RubyClass)context.runtime.getClassFromPath("Psych::Parser::Mark")).newInstance(
                context,
                RubyFixnum.zero(runtime),
                runtime.newFixnum(mark.getLine()),
                runtime.newFixnum(mark.getColumn()),
                Block.NULL_BLOCK
        );
    }

    private Parser parser;
    private Event event;
}
