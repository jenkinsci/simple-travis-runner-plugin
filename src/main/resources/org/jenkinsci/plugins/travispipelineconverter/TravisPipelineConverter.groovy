/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.travispipelineconverter

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.yaml.snakeyaml.Yaml

class TravisPipelineConverter implements Serializable {
    private CpsScript script;

    public TravisPipelineConverter(CpsScript script) {
        this.script = script;
    }

    /**
     * Load a ".travis.yml" file from the given path in the current workspace and execute it as best as we can.
     * Errors out if run outside a node { } block.
     *
     * @param path The path to the file in question.
     */
    public void run(String path) {
        if (script.env.HOME == null) {
            script.error("travisPipelineConverter.run(...) can only be run within a 'node { ... }' block.")
        } else {
            String travisFile = script.readFile(path)

            def travisSteps = readAndConvertTravis(travisFile)

            // Fail fast on any errors in before_install, install or before_script
            if (travisSteps.containsKey("before_install")) {
                script.stage "Travis Before Install"
                getSteps(travisSteps.get("before_install")).call()

            }
            if (travisSteps.containsKey("install")) {
                script.stage "Travis Install"
                getSteps(travisSteps.get("install")).call()

            }
            if (travisSteps.containsKey("before_script")) {
                script.stage "Travis Before Script"
                getSteps(travisSteps.get("before_script")).call()
            }

            // Note any failure in the script section but don't fail the build yet.
            def failedScript = false
            try {
                // TODO: Ideally we change this to note failures in script steps but continue through all of them
                // to completion anyway, as described in https://docs.travis-ci.com/user/customizing-the-build/#Customizing-the-Build-Step,
                // but I want to think about the implementation more.
                // TODO: Add timeout support (https://docs.travis-ci.com/user/customizing-the-build/#Build-Timeouts) for individual
                // script/test suite steps.
                if (travisSteps.containsKey("script")) {
                    script.stage "Travis Script"
                    getSteps(travisSteps.get("script")).call()
                }
            } catch (Exception e) {
                script.echo("Error on script step: ${e}")
                failedScript = true
            }

            if (!failedScript) {
                // Skip the deploy-related steps since those rely on Travis internals.
                for (String deployStep in ["before_deploy", "deploy", "after_deploy"].toSet()) {
                    if (travisSteps.containsKey(deployStep)) {
                        script.echo("Not executing '${deployStep}' - Travis-specific")
                    }
                }
            }

            // Swallow any errors in after_*.
            try {
                // If the script failed, proceed to after_failure.
                if (failedScript) {
                    if (travisSteps.containsKey("after_failure")) {
                        script.stage "Travis After Failure"
                        getSteps(travisSteps.get("after_failure")).call()
                    }
                } else {
                    // Otherwise, check after_success.
                    if (travisSteps.containsKey("after_success")) {
                        script.stage "Travis After Success"
                        getSteps(travisSteps.get("after_success")).call()
                    }
                }
                if (travisSteps.containsKey("after_script")) {
                    script.stage "Travis After Script"
                    getSteps(travisSteps.get("after_script")).call()
                }
            } catch (Exception e) {
                script.echo("Error on after step(s), ignoring: ${e}")
            }

            // If we saw a failure in the script step earlier, error out now.
            if (failedScript) {
                script.error("Failing build due to failure of script step.")
            }
        }
    }

    /**
     * Takes a Travis "step", which could either be a String or an ArrayList of Strings, and returns an array of
     * Pipeline "sh" steps inside a closure to execute those "steps".
     *
     * @param travisStep The value for a Travis "step" - could be either a single String or an ArrayList of Strings.
     * @return A closure containing a possibly-empty array of Pipeline "sh" steps.
     */
    def getSteps(travisStep) {
        def actualSteps = []
        if (travisStep instanceof String) {
            actualSteps[0] = script.sh((String) travisStep)
        } else if (travisStep instanceof ArrayList) {
            for (int i = 0; i < travisStep.size(); i++) {
                def thisStep = travisStep.get(i)
                actualSteps[i] = script.sh((String) thisStep)
            }
        }

        return {
            actualSteps
        }
    }

    /**
     * Given a ".travis.yml" formatted String (already read from a file), parses that as YAML and returns a nested Map
     * of the contents.
     *
     * @param travisYml The String contents of a ".travis.yml" file.
     * @return A Map with the top-level entries in the YAML as keys and their contents as Objects.
     */
    private Map<String,Object> readAndConvertTravis(String travisYml) {
        Yaml yaml = new Yaml()

        Map<String,Object> travisYaml = (Map<String,Object>) yaml.load(travisYml)

        return travisYaml
    }
}
