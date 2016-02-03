A really quick and dirty Pipeline step to convert Travis YAML to Pipeline on the fly.

Currently just runs before_install, install, before_script and script.

Actual guts are all in src/main/resources/org/jenkinsci/plugins/travispipelineconverter/TravisPipelineConverter.groovy.
