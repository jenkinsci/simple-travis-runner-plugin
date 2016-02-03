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
        if (travisSteps.containsKey("script")) {
            script.stage "Travis Script"
            getSteps(travisSteps.get("script")).call()
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
