A really quick and dirty Pipeline step to convert Travis YAML to Pipeline on the fly.

Currently supported:
- before_install
- install
- before_script
- script
- after_failure
- after_success
- after_script

Actual guts are all in src/main/resources/org/jenkinsci/plugins/travispipelineconverter/TravisPipelineConverter.groovy.

An example of using a Jenkinsfile to call a .travis.yml can be found at [this repo](https://github.com/abayer/dummy-travis-test).
