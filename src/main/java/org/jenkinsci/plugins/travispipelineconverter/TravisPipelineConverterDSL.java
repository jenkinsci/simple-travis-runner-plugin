/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.travispipelineconverter;

import groovy.lang.Binding;
import hudson.Extension;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import java.io.IOException;

@Extension
public class TravisPipelineConverterDSL extends GlobalVariable {
    @Override
    public String getName() {
        return "travisPipelineConverter";
    }

    @Override
    public Object getValue(CpsScript script) throws Exception {
        Binding binding = script.getBinding();
        Object travisPipelineConverter;

        // If we already have a travisPipelineConverter defined, reuse it. Otherwise, load it from the DSL groovy and
        // add it to the binding.
        if (binding.hasVariable(getName())) {
            travisPipelineConverter = binding.getVariable(getName());
        } else {
            travisPipelineConverter = script.getClass()
                    .getClassLoader()
                    .loadClass("org.jenkinsci.plugins.travispipelineconverter.TravisPipelineConverter")
                    .getConstructor(CpsScript.class)
                    .newInstance(script);

            binding.setVariable(getName(), travisPipelineConverter);
        }

        return travisPipelineConverter;
    }

    @Extension
    public static class MiscWhitelist extends ProxyWhitelist {
        /**
         * Methods to add to the script-security whitelist for this plugin to work.
         *
         * @throws IOException
         */
        public MiscWhitelist() throws IOException {
            super(new StaticWhitelist(
                    "new org.yaml.snakeyaml.Yaml",
                    "method org.yaml.snakeyaml.Yaml load java.lang.String",
                    "method java.util.Map containsKey java.lang.Object",
                    "method java.lang.Class isInstance java.lang.Object",
                    "staticMethod org.codehaus.groovy.runtime.ScriptBytecodeAdapter castToType java.lang.Object java.lang.Class",
                    "staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods toSet java.util.Collection"
            ));
        }
    }
}
