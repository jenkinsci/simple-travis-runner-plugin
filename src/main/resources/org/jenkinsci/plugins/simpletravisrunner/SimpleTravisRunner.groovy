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
package org.jenkinsci.plugins.simpletravisrunner

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.yaml.snakeyaml.Yaml

class SimpleTravisRunner implements Serializable {
    private CpsScript script;

    public SimpleTravisRunner(CpsScript script) {
        this.script = script;
    }

    /**
     * Load a ".travis.yml" file from the given path in the current workspace and execute it as best as we can.
     * Errors out if run outside a node { } block.
     *
     * @param path The path to the file in question.
     * @param labelExpr Optional label expression to run parallel "matrix" executions on. If not given, will
     *                    just run on 'node { }'.
     * @param timeout Optional timeout, in minutes, for individual steps - defaults to 50 minutes, like travis-ci.org.
     */
    public void call(String path, String labelExpr = null, Integer timeout = 50) {
        if (script.env.HOME != null) {
            script.error("simpleTravisRunner(travisFile[, label, timeout]) cannot be run within a 'node { ... }' block.")
        } else {
            try {
                script.node(labelExpr) {
                    script.checkout script.scm
                    String travisFile = script.readFile(path)

                    def travisSteps = readAndConvertTravis(travisFile)

                    def envCombos

                    // Let's see what we get from env!
                    if (travisSteps.containsKey("env")) {
                        envCombos = generateEnvCombinations(travisSteps.get("env"))
                    }

                    if (envCombos != null && envCombos.size() > 0) {
                        def parallelInvocations = [:]

                        for (int i = 0; i < envCombos.size(); i++) {
                            def thisEnv = envCombos.get(i)
                            parallelInvocations[thisEnv.toString()] = {
                                script.node(labelExpr) {
                                    script.withEnv(thisEnv, executeSteps(travisSteps, true, timeout))
                                }
                            }
                        }

                        script.stage "Parallel Travis Execution"
                        script.parallel parallelInvocations

                    } else {
                        executeSteps(travisSteps, false, timeout).call()
                    }

                }
            } catch (IllegalStateException e) {
                script.error("simpleTravisRunner(travisFile[, label, timeout]) can only be run in a Pipeline script from SCM.")
            }
        }

    }

    private def executeSteps(travisSteps, boolean inParallel, Integer timeout) {
        return {
            // Fail fast on any errors in before_install, install or before_script
            if (travisSteps.containsKey("before_install")) {
                if (!inParallel)
                    script.stage "Travis Before Install"
                getSteps(travisSteps.get("before_install"), timeout)

            }
            if (travisSteps.containsKey("install")) {
                if (!inParallel)
                    script.stage "Travis Install"
                getSteps(travisSteps.get("install"), timeout)

            }
            if (travisSteps.containsKey("before_script")) {
                if (!inParallel)
                    script.stage "Travis Before Script"
                getSteps(travisSteps.get("before_script"), timeout)
            }

            // Note any failure in the script section but don't fail the build yet.
            def failedScript = false
            try {
                // TODO: Ideally we change this to note failures in script steps but continue through all of them
                // to completion anyway, as described in https://docs.travis-ci.com/user/customizing-the-build/#Customizing-the-Build-Step,
                // but I want to think about the implementation more.
                if (travisSteps.containsKey("script")) {
                    if (!inParallel)
                        script.stage "Travis Script"
                    getSteps(travisSteps.get("script"), timeout)
                }
            } catch (Exception e) {
                script.echo("Error on script step: ${e}")
                failedScript = true
            }

            if (!failedScript) {
                // Skip the deploy-related steps since those rely on Travis internals.
                def deploySteps = ["before_deploy", "deploy", "after_deploy"]
                for (int i = 0; i < deploySteps.size(); i++) {
                    def deployStep = deploySteps.get(i)
                    if (travisSteps.containsKey(deployStep)) {
                        script.echo("Not executing '${deployStep}' - Travis-specific")
                    }
                }
            }

            // Swallow any errors in after_* - may want to change this, not sure.
            try {
                // If the script failed, proceed to after_failure.
                if (failedScript) {
                    if (travisSteps.containsKey("after_failure")) {
                        if (!inParallel)
                            script.stage "Travis After Failure"
                        getSteps(travisSteps.get("after_failure"), timeout)
                    }
                } else {
                    // Otherwise, check after_success.
                    if (travisSteps.containsKey("after_success")) {
                        if (!inParallel)
                            script.stage "Travis After Success"
                        getSteps(travisSteps.get("after_success"), timeout)
                    }
                }
                if (travisSteps.containsKey("after_script")) {
                    if (!inParallel)
                        script.stage "Travis After Script"
                    getSteps(travisSteps.get("after_script"), timeout)
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
     * @param timeout Timeout in minutes for execution of this step.
     * @return A closure containing a possibly-empty array of Pipeline "sh" steps.
     */
    private def getSteps(travisStep, Integer timeout) {
        def actualSteps = []
        def stepsList = getYamlStringOrListAsList(travisStep)
        for (int i = 0; i < stepsList.size(); i++) {
            def thisStep = stepsList.get(i)
            actualSteps[i] = script.timeout(time: timeout, unit: 'MINUTES') {
                script.sh((String) thisStep)
            }
        }
        return {
            actualSteps
        }
    }

    /**
     * Takes a YAML entry that could be either a single String or an ArrayList of Strings. If it's a single String, returns
     * a new List with that String as the only element. If it's an ArrayList, simply return that list. If the entry is of
     * any other type, throws an IllegalArgumentException.
     *
     * @param yamlEntry
     * @return a list of (hopefully) Strings
     * @throws IllegalArgumentException
     */
    private def getYamlStringOrListAsList(yamlEntry) throws IllegalArgumentException {
        if (yamlEntry instanceof String) {
            return [yamlEntry]
        } else if (yamlEntry instanceof ArrayList) {
            return yamlEntry
        } else {
            throw new IllegalArgumentException("Bad format of YAML - found ${yamlEntry.class} when expecting either 'String' or 'ArrayList'")
        }
    }

    /**
     * Takes the value of the 'env' key in the Travis YAML and returns a map with the environment keys as the key and a
     * list of specified values for the environment key as the value.
     *
     * @param travisEnv
     * @return a map of environment keys to lists of values for the key
     */
    private def generateEnvAxes(travisEnv) {
        def rawEnvEntries = getYamlStringOrListAsList(travisEnv)

        def envEntries = [:]

        for (def entryString in rawEnvEntries.toSet()) {
            if (entryString instanceof String) {

                def cleanedString = stripLeadingTrailingQuotes(entryString)
                def stringParts = cleanedString.split("=")

                if (stringParts != null && stringParts.size() == 2) {
                    if (!(envEntries.containsKey(stringParts[0]))) {
                        envEntries[stringParts[0]] = []
                    }
                    envEntries[stringParts[0]].add(stringParts[1])
                }
            }
        }

        return envEntries
    }


    private def generateEnvCombinations(travisEnv) {
        Map<String,List<Object>> axes = generateEnvAxes(travisEnv)

        List<List<Object>> valueCombos = axes.values().toList().combinations()

        def keyList = axes.keySet().toList()

        def combos = []

        for (int i = 0; i < valueCombos.size(); i++) {
            List thisCombo = valueCombos.get(i)
            def thisEnv = []

            for (int j = 0; j < thisCombo.size(); j++) {
                def thisVal = thisCombo.get(j)
                def thisKey = keyList.get(j)
                thisEnv.add(thisKey + "=" + thisVal)
            }

            combos.add(thisEnv)
        }

        return combos
    }

    /**
     * Takes a string, and if it both begins and ends with double quotes or single quotes, returns it with those quotes removed.
     * Otherwise, returns the original string.
     *
     * @param inputString
     * @return either inputString with leading/trailing quotes removed or the original inputString.
     */
    private def stripLeadingTrailingQuotes(String inputString) {
        if ((inputString.startsWith('"') && inputString.endsWith('"')) || (inputString.startsWith("'") && inputString.endsWith("'"))) {
            return inputString.substring(1, inputString.length() - 1)
        } else {
            return inputString
        }
    }

    /**
     * Given a ".travis.yml" formatted String (already read from a file), parses that as YAML and returns a nested Map
     * of the contents.
     *
     * NOTE - this is annotated with "@NonCPS" because the Yaml class isn't serializable.
     *
     * @param travisYml The String contents of a ".travis.yml" file.
     * @return A Map with the top-level entries in the YAML as keys and their contents as Objects.
     */
    @NonCPS
    private Map<String,Object> readAndConvertTravis(String travisYml) {
        Yaml yaml = new Yaml()

        Map<String, Object> travisYaml = (Map<String, Object>) yaml.load(travisYml)

        return travisYaml
    }
}
