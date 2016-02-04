package org.jenkinsci.plugins.travispipelineconverter

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.yaml.snakeyaml.Yaml

class TravisPipelineConverter implements Serializable {
    private CpsScript script;

    public TravisPipelineConverter(CpsScript script) {
        this.script = script;
    }

    // TODO: make sure this can only run within a node { }. Not sure how to do that here.
    public void run(String path) {
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

    // TODO: Figure out if this actually needs to be NonCPS.
    @NonCPS
    private Map<String,Object> readAndConvertTravis(String travisYml) {
        Yaml yaml = new Yaml()

        Map<String,Object> travisYaml = (Map<String,Object>) yaml.load(travisYml)

        return travisYaml
    }
}
