/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.shell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.GeneratedClassLoader;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Kit;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SecurityController;
import org.mozilla.javascript.commonjs.module.ModuleScope;
import org.mozilla.javascript.commonjs.module.Require;
import org.mozilla.javascript.config.RhinoConfig;
import org.mozilla.javascript.tools.SourceReader;
import org.mozilla.javascript.tools.ToolErrorReporter;

/**
 * The shell program.
 *
 * <p>Can execute scripts interactively or in batch mode at the command line. An example of
 * controlling the JavaScript engine.
 *
 * @author Norris Boyd
 */
public class Main {
    public static ShellContextFactory shellContextFactory = new ShellContextFactory();

    public static Global global = new Global();
    protected static ToolErrorReporter errorReporter;
    protected static int exitCode = 0;
    private static final int EXITCODE_RUNTIME_ERROR = 3;
    private static final int EXITCODE_FILE_NOT_FOUND = 4;
    static boolean processStdin = true;
    static List<String> fileList = new ArrayList<String>();
    static List<String> modulePath;
    static String mainModule;
    static boolean sandboxed = false;
    static boolean useRequire = false;
    static Require require;
    private static SecurityProxy securityImpl;
    private static final ScriptCache scriptCache = new ScriptCache(32);

    static {
        global.initQuitAction(new IProxy(IProxy.SYSTEM_EXIT));
    }

    /** Proxy class to avoid proliferation of anonymous classes. */
    private static class IProxy implements ContextAction<Object>, QuitAction {
        private static final int PROCESS_FILES = 1;
        private static final int EVAL_INLINE_SCRIPT = 2;
        private static final int SYSTEM_EXIT = 3;

        private int type;
        String[] args;
        String scriptText;
        private final Timers timers = new Timers();

        IProxy(int type) {
            this.type = type;
        }

        @Override
        public Object run(Context cx) {
            cx.setTrackUnhandledPromiseRejections(true);
            timers.install(global);
            if (useRequire) {
                require = global.installRequire(cx, modulePath, sandboxed);
            }
            if (type == PROCESS_FILES) {
                processFiles(cx, args);
                printPromiseWarnings(cx, true);
            } else if (type == EVAL_INLINE_SCRIPT) {
                evalInlineScript(cx, scriptText);
            } else {
                throw Kit.codeBug();
            }
            try {
                timers.runAllTimers(cx, global);
            } catch (JavaScriptException e) {
                ToolErrorReporter.reportException(cx.getErrorReporter(), e);
                exitCode = EXITCODE_RUNTIME_ERROR;
            } catch (InterruptedException ie) {
                // Shell has no facility to handle interrupts so stop now
            }
            return null;
        }

        @Override
        public void quit(Context cx, int exitCode) {
            if (type == SYSTEM_EXIT) {
                System.exit(exitCode);
                return;
            }
            throw Kit.codeBug();
        }
    }

    /**
     * Main entry point.
     *
     * <p>Process arguments as would a normal Java program. Also create a new Context and associate
     * it with the current thread. Then set up the execution environment and begin to execute
     * scripts.
     */
    public static void main(String args[]) {
        try {
            if (RhinoConfig.get("rhino.use_java_policy_security", false)) {
                initJavaPolicySecuritySupport();
            }
        } catch (SecurityException ex) {
            ex.printStackTrace(System.err);
        }

        int result = exec(args);
        if (result != 0) {
            System.exit(result);
        }
    }

    /** Execute the given arguments, but don't System.exit at the end. */
    public static int exec(String origArgs[]) {
        errorReporter = new ToolErrorReporter(false, global.getErr());
        shellContextFactory.setErrorReporter(errorReporter);
        String[] args = processOptions(origArgs);
        if (exitCode > 0) {
            return exitCode;
        }
        if (processStdin) {
            fileList.add(null);
        }
        if (!global.initialized) {
            global.init(shellContextFactory);
        }
        IProxy iproxy = new IProxy(IProxy.PROCESS_FILES);
        iproxy.args = args;
        shellContextFactory.call(iproxy);

        return exitCode;
    }

    static void processFiles(Context cx, String[] args) {
        // define "arguments" array in the top-level object:
        // need to allocate new array since newArray requires instances
        // of exactly Object[], not ObjectSubclass[]
        Object[] array = new Object[args.length];
        System.arraycopy(args, 0, array, 0, args.length);
        Scriptable argsObj = cx.newArray(global, array);
        global.defineProperty("arguments", argsObj, ScriptableObject.DONTENUM);

        for (String file : fileList) {
            try {
                processSource(cx, file);
            } catch (IOException ioex) {
                Context.reportError(
                        ToolErrorReporter.getMessage(
                                "msg.couldnt.read.source", file, ioex.getMessage()));
                exitCode = EXITCODE_FILE_NOT_FOUND;
            } catch (RhinoException rex) {
                ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
                exitCode = EXITCODE_RUNTIME_ERROR;
            } catch (VirtualMachineError ex) {
                // Treat StackOverflow and OutOfMemory as runtime errors
                ex.printStackTrace();
                String msg = ToolErrorReporter.getMessage("msg.uncaughtJSException", ex.toString());
                Context.reportError(msg);
                exitCode = EXITCODE_RUNTIME_ERROR;
            }
        }
    }

    static void evalInlineScript(Context cx, String scriptText) {
        try {
            Script script = cx.compileString(scriptText, "<command>", 1, null);
            if (script != null) {
                Scriptable scope = getShellScope();
                script.exec(cx, scope, scope);
            }
        } catch (RhinoException rex) {
            ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (VirtualMachineError ex) {
            // Treat StackOverflow and OutOfMemory as runtime errors
            ex.printStackTrace();
            String msg = ToolErrorReporter.getMessage("msg.uncaughtJSException", ex.toString());
            Context.reportError(msg);
            exitCode = EXITCODE_RUNTIME_ERROR;
        }
    }

    public static Global getGlobal() {
        return global;
    }

    static Scriptable getShellScope() {
        return getScope(null);
    }

    static Scriptable getScope(String path) {
        if (useRequire) {
            // If CommonJS modules are enabled use a module scope that resolves
            // relative ids relative to the current URL, file or working directory.
            URI uri;
            if (path == null) {
                // use current directory for shell and -e switch
                uri = new File(System.getProperty("user.dir")).toURI();
            } else {
                // find out whether this is a file path or a URL
                if (SourceReader.toUrl(path) != null) {
                    try {
                        uri = new URI(path);
                    } catch (URISyntaxException x) {
                        // fall back to file uri
                        uri = new File(path).toURI();
                    }
                } else {
                    uri = new File(path).toURI();
                }
            }
            return new ModuleScope(global, uri, null);
        }
        return global;
    }

    /** Parse arguments. */
    public static String[] processOptions(String args[]) {
        String usageError;
        goodUsage:
        for (int i = 0; ; ++i) {
            if (i == args.length) {
                return new String[0];
            }
            String arg = args[i];
            if (!arg.startsWith("-")) {
                processStdin = false;
                fileList.add(arg);
                mainModule = arg;
                String[] result = new String[args.length - i - 1];
                System.arraycopy(args, i + 1, result, 0, args.length - i - 1);
                return result;
            }
            if (arg.equals("-version")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                int version;
                try {
                    version = Integer.parseInt(args[i]);
                } catch (NumberFormatException ex) {
                    usageError = args[i];
                    break goodUsage;
                }
                if (!Context.isValidLanguageVersion(version)) {
                    usageError = args[i];
                    break goodUsage;
                }
                shellContextFactory.setLanguageVersion(version);
                continue;
            }
            if (arg.equals("-opt") || arg.equals("-O")) {
                // As of 1.8.0, this bit remains for backward compatibility,
                // although it is no longer documented in the "help" message.
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                int opt;
                try {
                    opt = Integer.parseInt(args[i]);
                } catch (NumberFormatException ex) {
                    usageError = args[i];
                    break goodUsage;
                }
                if (opt < 0) {
                    shellContextFactory.setInterpretedMode(true);
                }
                continue;
            }
            if (arg.equals("-int") || arg.equals("-interpreted")) {
                shellContextFactory.setInterpretedMode(true);
                continue;
            }
            if (arg.equals("-encoding")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                String enc = args[i];
                shellContextFactory.setCharacterEncoding(enc);
                continue;
            }
            if (arg.equals("-strict")) {
                shellContextFactory.setStrictMode(true);
                shellContextFactory.setAllowReservedKeywords(false);
                errorReporter.setIsReportingWarnings(true);
                continue;
            }
            if (arg.equals("-fatal-warnings")) {
                shellContextFactory.setWarningAsError(true);
                continue;
            }
            if (arg.equals("-e")) {
                processStdin = false;
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                if (!global.initialized) {
                    global.init(shellContextFactory);
                }
                IProxy iproxy = new IProxy(IProxy.EVAL_INLINE_SCRIPT);
                iproxy.scriptText = args[i];
                shellContextFactory.call(iproxy);
                continue;
            }
            if (arg.equals("-require")) {
                useRequire = true;
                continue;
            }
            if (arg.equals("-sandbox")) {
                sandboxed = true;
                useRequire = true;
                continue;
            }
            if (arg.equals("-modules")) {
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                if (modulePath == null) {
                    modulePath = new ArrayList<String>();
                }
                modulePath.add(args[i]);
                useRequire = true;
                continue;
            }
            if (arg.equals("-w")) {
                errorReporter.setIsReportingWarnings(true);
                continue;
            }
            if (arg.equals("-f")) {
                processStdin = false;
                if (++i == args.length) {
                    usageError = arg;
                    break goodUsage;
                }
                if (args[i].equals("-")) {
                    fileList.add(null);
                } else {
                    fileList.add(args[i]);
                    mainModule = args[i];
                }
                continue;
            }
            if (arg.equals("-sealedlib")) {
                global.setSealedStdLib(true);
                continue;
            }
            if (arg.equals("-debug")) {
                shellContextFactory.setGeneratingDebug(true);
                continue;
            }
            if (arg.equals("-?") || arg.equals("-help")) {
                // print usage message
                global.getOut()
                        .println(
                                ToolErrorReporter.getMessage(
                                        "msg.shell.usage", Main.class.getName()));
                exitCode = 1;
                return null;
            }
            usageError = arg;
            break goodUsage;
        }
        // print error and usage message
        global.getOut().println(ToolErrorReporter.getMessage("msg.shell.invalid", usageError));
        global.getOut()
                .println(ToolErrorReporter.getMessage("msg.shell.usage", Main.class.getName()));
        exitCode = 1;
        return null;
    }

    private static void initJavaPolicySecuritySupport() {
        Throwable exObj;
        try {
            Class<?> cl = Class.forName("org.mozilla.javascript.tools.shell.JavaPolicySecurity");
            securityImpl = (SecurityProxy) cl.getDeclaredConstructor().newInstance();
            SecurityController.initGlobal(securityImpl);
            return;
        } catch (ClassNotFoundException
                | IllegalAccessException
                | InstantiationException
                | LinkageError
                | NoSuchMethodException
                | InvocationTargetException ex) {
            exObj = ex;
        }
        throw new IllegalStateException("Can not load security support: " + exObj, exObj);
    }

    /**
     * Evaluate JavaScript source.
     *
     * @param cx the current context
     * @param filename the name of the file to compile, or null for interactive mode.
     * @throws IOException if the source could not be read
     * @throws RhinoException thrown during evaluation of source
     */
    public static void processSource(Context cx, String filename) throws IOException {
        if (filename == null || filename.equals("-")) {
            Scriptable scope = getShellScope();
            Charset cs;
            String charEnc = shellContextFactory.getCharacterEncoding();
            if (charEnc != null) {
                cs = Charset.forName(charEnc);
            } else {
                cs = Charset.defaultCharset();
            }
            ShellConsole console = global.getConsole(cs);
            if (filename == null) {
                // print implementation version
                console.println(cx.getImplementationVersion());
            }

            int lineno = 1;
            boolean hitEOF = false;
            while (!hitEOF) {
                String[] prompts = global.getPrompts(cx);
                String prompt = null;
                if (filename == null) prompt = prompts[0];
                console.flush();
                StringBuilder source = new StringBuilder();

                // Collect lines of source to compile.
                while (true) {
                    String newline;
                    try {
                        newline = console.readLine(prompt);
                    } catch (IOException ioe) {
                        console.println(ioe.toString());
                        break;
                    }
                    if (newline == null) {
                        hitEOF = true;
                        break;
                    }
                    source.append(newline).append('\n');
                    lineno++;
                    if (cx.stringIsCompilableUnit(source.toString())) break;
                    prompt = prompts[1];
                }
                try {
                    String finalSource = source.toString();
                    Script script = cx.compileString(finalSource, "<stdin>", lineno, null);
                    if (script != null) {
                        Object result = script.exec(cx, scope, scope);
                        // Avoid printing out undefined or function definitions.
                        if (result != Context.getUndefinedValue()
                                && !(result instanceof Function
                                        && finalSource.trim().startsWith("function"))) {
                            try {
                                console.println(Context.toString(result));
                            } catch (RhinoException rex) {
                                ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
                            }
                        }
                        NativeArray h = global.history;
                        h.put((int) h.getLength(), h, source);
                    }
                    printPromiseWarnings(cx, false);
                } catch (RhinoException rex) {
                    ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
                    exitCode = EXITCODE_RUNTIME_ERROR;
                } catch (VirtualMachineError ex) {
                    // Treat StackOverflow and OutOfMemory as runtime errors
                    ex.printStackTrace();
                    String msg =
                            ToolErrorReporter.getMessage("msg.uncaughtJSException", ex.toString());
                    Context.reportError(msg);
                    exitCode = EXITCODE_RUNTIME_ERROR;
                }
            }
            console.println();
            console.flush();
        } else if (useRequire && filename.equals(mainModule)) {
            require.requireMain(cx, filename);
        } else {
            processFile(cx, getScope(filename), filename);
        }
    }

    public static void processFileNoThrow(Context cx, Scriptable scope, String filename) {
        try {
            processFile(cx, scope, filename);
        } catch (IOException ioex) {
            Context.reportError(
                    ToolErrorReporter.getMessage(
                            "msg.couldnt.read.source", filename, ioex.getMessage()));
            exitCode = EXITCODE_FILE_NOT_FOUND;
        } catch (RhinoException rex) {
            ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
            exitCode = EXITCODE_RUNTIME_ERROR;
        } catch (VirtualMachineError ex) {
            // Treat StackOverflow and OutOfMemory as runtime errors
            ex.printStackTrace();
            String msg = ToolErrorReporter.getMessage("msg.uncaughtJSException", ex.toString());
            Context.reportError(msg);
            exitCode = EXITCODE_RUNTIME_ERROR;
        }
    }

    public static void processFile(Context cx, Scriptable scope, String filename)
            throws IOException {
        if (securityImpl == null) {
            processFileSecure(cx, scope, filename, null);
        } else {
            securityImpl.callProcessFileSecure(cx, scope, filename);
        }
    }

    static void processFileSecure(Context cx, Scriptable scope, String path, Object securityDomain)
            throws IOException {

        boolean isClass = path.endsWith(".class");
        Object source = readFileOrUrl(path, !isClass);

        byte[] digest = getDigest(source);
        String key = path + "_" + (cx.isInterpretedMode() ? "interpreted" : "compiled");
        ScriptReference ref = scriptCache.get(key, digest);
        Script script = ref != null ? ref.get() : null;

        if (script == null) {
            if (isClass) {
                script = loadCompiledScript(cx, path, (byte[]) source, securityDomain);
            } else {
                String strSrc = (String) source;
                // Support the executable script #! syntax:  If
                // the first line begins with a '#', treat the whole
                // line as a comment.
                if (strSrc.length() > 0 && strSrc.charAt(0) == '#') {
                    for (int i = 1; i != strSrc.length(); ++i) {
                        int c = strSrc.charAt(i);
                        if (c == '\n' || c == '\r') {
                            strSrc = strSrc.substring(i);
                            break;
                        }
                    }
                }
                script = cx.compileString(strSrc, path, 1, securityDomain);
            }
            scriptCache.put(key, digest, script);
        }

        if (script != null) {
            script.exec(cx, scope, scope);
        }
    }

    private static byte[] getDigest(Object source) {
        byte[] bytes, digest = null;

        if (source != null) {
            if (source instanceof String) {
                bytes = ((String) source).getBytes(StandardCharsets.UTF_8);
            } else {
                bytes = (byte[]) source;
            }
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                digest = md.digest(bytes);
            } catch (NoSuchAlgorithmException nsa) {
                // Should not happen
                throw new RuntimeException(nsa);
            }
        }

        return digest;
    }

    private static Script loadCompiledScript(
            Context cx, String path, byte[] data, Object securityDomain)
            throws FileNotFoundException {
        if (data == null) {
            throw new FileNotFoundException(path);
        }
        // XXX: For now extract class name of compiled Script from path
        // instead of parsing class bytes
        int nameStart = path.lastIndexOf('/');
        if (nameStart < 0) {
            nameStart = 0;
        } else {
            ++nameStart;
        }
        int nameEnd = path.lastIndexOf('.');
        if (nameEnd < nameStart) {
            // '.' does not exist in path (nameEnd < 0)
            // or it comes before nameStart
            nameEnd = path.length();
        }
        String name = path.substring(nameStart, nameEnd);
        try {
            GeneratedClassLoader loader =
                    SecurityController.createLoader(cx.getApplicationClassLoader(), securityDomain);
            Class<?> clazz = loader.defineClass(name, data);
            loader.linkClass(clazz);
            if (!Script.class.isAssignableFrom(clazz)) {
                throw Context.reportRuntimeError("msg.must.implement.Script");
            }
            return (Script) clazz.getDeclaredConstructor().newInstance();
        } catch (IllegalAccessException
                | InstantiationException
                | NoSuchMethodException
                | InvocationTargetException ex) {
            Context.reportError(ex.toString());
            throw new RuntimeException(ex);
        }
    }

    private static void printPromiseWarnings(Context cx, boolean exitOnRejections) {
        List<Object> unhandled = cx.getUnhandledPromiseTracker().enumerate();
        if (!unhandled.isEmpty()) {
            for (Object rejection : unhandled) {
                String msg = "Unhandled rejected promise: " + Context.toString(rejection);
                if (rejection instanceof Scriptable) {
                    Object stack = ScriptableObject.getProperty((Scriptable) rejection, "stack");
                    if (stack != null && stack != Scriptable.NOT_FOUND) {
                        msg += '\n' + Context.toString(stack);
                    }
                }
                System.out.println(msg);
            }

            if (exitOnRejections) {
                exitCode = EXITCODE_RUNTIME_ERROR;
            }
        }
    }

    public static InputStream getIn() {
        return getGlobal().getIn();
    }

    public static void setIn(InputStream in) {
        getGlobal().setIn(in);
    }

    public static PrintStream getOut() {
        return getGlobal().getOut();
    }

    public static void setOut(PrintStream out) {
        getGlobal().setOut(out);
    }

    public static PrintStream getErr() {
        return getGlobal().getErr();
    }

    public static void setErr(PrintStream err) {
        getGlobal().setErr(err);
    }

    /**
     * Read file or url specified by <code>path</code>.
     *
     * @return file or url content as <code>byte[]</code> or as <code>String</code> if <code>
     *     convertToString</code> is true.
     */
    private static Object readFileOrUrl(String path, boolean convertToString) throws IOException {
        return SourceReader.readFileOrUrl(
                path, convertToString, shellContextFactory.getCharacterEncoding());
    }

    static class ScriptReference extends SoftReference<Script> {
        String path;
        byte[] digest;

        ScriptReference(String path, byte[] digest, Script script, ReferenceQueue<Script> queue) {
            super(script, queue);
            this.path = path;
            this.digest = digest;
        }
    }

    static class ScriptCache extends LinkedHashMap<String, ScriptReference> {
        private static final long serialVersionUID = -6866856136258508615L;
        ReferenceQueue<Script> queue;
        int capacity;

        ScriptCache(int capacity) {
            super(capacity + 1, 2f, true);
            this.capacity = capacity;
            queue = new ReferenceQueue<Script>();
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ScriptReference> eldest) {
            return size() > capacity;
        }

        ScriptReference get(String path, byte[] digest) {
            ScriptReference ref;
            while ((ref = (ScriptReference) queue.poll()) != null) {
                remove(ref.path);
            }
            ref = get(path);
            if (ref != null && !Arrays.equals(digest, ref.digest)) {
                remove(ref.path);
                ref = null;
            }
            return ref;
        }

        void put(String path, byte[] digest, Script script) {
            put(path, new ScriptReference(path, digest, script, queue));
        }
    }
}
