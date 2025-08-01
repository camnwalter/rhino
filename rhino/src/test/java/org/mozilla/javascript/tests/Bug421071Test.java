/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * @(#)Bug421071Test.java
 *
 */

package org.mozilla.javascript.tests;

import org.junit.Before;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.testutils.Utils;

public class Bug421071Test {
    private ContextFactory factory;
    private TopLevelScope globalScope;
    private Script testScript;

    @Test
    public void problemReplicator() throws Exception {
        // before debugging please put the breakpoint in the
        // NativeJavaPackage.getPkgProperty()
        // and observe names passed in there
        testScript = compileScript();
        runTestScript(); // this one does not get to the
        // NativeJavaPackage.getPkgProperty() on my
        // variables
        runTestScript(); // however this one does
    }

    private Script compileScript() {
        String scriptSource =
                "importPackage(java.util);\n"
                        + "var searchmon = 3;\n"
                        + "var searchday = 10;\n"
                        + "var searchyear = 2008;\n"
                        + "var searchwkday = 0;\n"
                        + "\n"
                        + "var myDate = Calendar.getInstance();\n // this is a java.util.Calendar"
                        + "myDate.set(Calendar.MONTH, searchmon);\n"
                        + "myDate.set(Calendar.DATE, searchday);\n"
                        + "myDate.set(Calendar.YEAR, searchyear);\n"
                        + "searchwkday.value = myDate.get(Calendar.DAY_OF_WEEK);";
        Script script;
        try (Context context = factory.enterContext()) {
            script = context.compileString(scriptSource, "testScript", 1, null);
            return script;
        }
    }

    private void runTestScript() throws InterruptedException {
        // will start new thread to get as close as possible to original
        // environment, however the same behavior is exposed using new
        // ScriptRunner(script).run();
        Thread thread = new Thread(new ScriptRunner(testScript));
        thread.start();
        thread.join();
    }

    private TopLevelScope createGlobalScope() {
        factory = Utils.contextFactoryWithFeatures(Context.FEATURE_DYNAMIC_SCOPE);

        try (Context context = factory.enterContext()) {
            // noinspection deprecation
            TopLevelScope topLevelScope = new TopLevelScope(context);
            return topLevelScope;
        }
    }

    @Before
    public void setUp() throws Exception {
        globalScope = createGlobalScope();
    }

    private class TopLevelScope extends ImporterTopLevel {
        private static final long serialVersionUID = 7831526694313927899L;

        public TopLevelScope(Context context) {
            super(context);
        }
    }

    private class ScriptRunner implements Runnable {
        private Script script;

        public ScriptRunner(Script script) {
            this.script = script;
        }

        @Override
        public void run() {
            try (Context context = factory.enterContext()) {
                // Run each script in its own scope, to keep global variables
                // defined in each script separate
                Scriptable threadScope = context.newObject(globalScope);
                threadScope.setPrototype(globalScope);
                threadScope.setParentScope(null);
                script.exec(context, threadScope, threadScope);
            } catch (Exception ee) {
                ee.printStackTrace();
            }
        }
    }
}
