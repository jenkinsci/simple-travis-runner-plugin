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


import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.yaml.snakeyaml.Yaml

/**
 * Load a ".travis.yml" file from the given path in the current workspace and execute it as best as we can.
 * Errors out if run outside a node { } block.
 *
 * @param path The path to the file in question.
 * @param labelExpr Optional label expression to run parallel "matrix" executions on. If not given, will
 *                    just run on 'node { }'.
 */
public void call(String path, String labelExpr = null) {
    if (env.HOME == null) {
        error("travisPipelineConverter(...) can only be run within a 'node { ... }' block.")
    } else {
        String travisFile = readFile(path)

        def travisSteps = readAndConvertTravis(travisFile)

        // Let's see what we get from env!
        if (travisSteps.containsKey("env")) {
            def combos = generateEnvAxes(travisSteps.get("env"))

            for (int k = 0; k < combos.size(); k++) {
                echo("combo: ${combos.get(k)}")
            }
        }

        // Fail fast on any errors in before_install, install or before_script
        if (travisSteps.containsKey("before_install")) {
            stage "Travis Before Install"
            getSteps(travisSteps.get("before_install")).call()

        }
        if (travisSteps.containsKey("install")) {
            stage "Travis Install"
            getSteps(travisSteps.get("install")).call()

        }
        if (travisSteps.containsKey("before_script")) {
            stage "Travis Before Script"
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
                stage "Travis Script"
                getSteps(travisSteps.get("script")).call()
            }
        } catch (Exception e) {
            echo("Error on script step: ${e}")
            failedScript = true
        }

        if (!failedScript) {
            // Skip the deploy-related steps since those rely on Travis internals.
            for (String deployStep in ["before_deploy", "deploy", "after_deploy"].toSet()) {
                if (travisSteps.containsKey(deployStep)) {
                    echo("Not executing '${deployStep}' - Travis-specific")
                }
            }
        }

        // Swallow any errors in after_* - may want to change this, not sure.
        try {
            // If the script failed, proceed to after_failure.
            if (failedScript) {
                if (travisSteps.containsKey("after_failure")) {
                    stage "Travis After Failure"
                    getSteps(travisSteps.get("after_failure")).call()
                }
            } else {
                // Otherwise, check after_success.
                if (travisSteps.containsKey("after_success")) {
                    stage "Travis After Success"
                    getSteps(travisSteps.get("after_success")).call()
                }
            }
            if (travisSteps.containsKey("after_script")) {
                stage "Travis After Script"
                getSteps(travisSteps.get("after_script")).call()
            }
        } catch (Exception e) {
            echo("Error on after step(s), ignoring: ${e}")
        }

        // If we saw a failure in the script step earlier, error out now.
        if (failedScript) {
            error("Failing build due to failure of script step.")
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
private def getSteps(travisStep) {
    def actualSteps = []
    def stepsList = getYamlStringOrListAsList(travisStep)
    for (int i = 0; i < stepsList.size(); i++) {
        def thisStep = stepsList.get(i)
        actualSteps[i] = sh((String) thisStep)
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
            def (k, v) = cleanedString.split("=")

            if (!(envEntries.containsKey(k))) {
                envEntries[k] = []
            }
            envEntries[k].add(v)
        }
    }

    return envEntries
}

private def generateEnvCombinations(travisEnv) {
    Map<String,List<Object>> axes = generateEnvAxes(travisEnv)

    List<List<List<Object>>> comboPairs = [axes*.key, axes*.value].transpose()*.combinations().combinations()

    def combos = []

    for (int i = 0; i < comboPairs.size(); i++) {
        List thisCombo = comboPairs.get(i)
        def thisMap = [:]

        for (int j = 0; j < thisCombo.size(); j++) {
            List thisPair = thisCombo.get(j)
            thisMap[thisPair[0]] = thisPair[1]
        }

        combos.add(thisMap)
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
 * @param travisYml The String contents of a ".travis.yml" file.
 * @return A Map with the top-level entries in the YAML as keys and their contents as Objects.
 */
private Map<String,Object> readAndConvertTravis(String travisYml) {
    Yaml yaml = new Yaml()

    Map<String, Object> travisYaml = (Map<String, Object>) yaml.load(travisYml)

    return travisYaml
}