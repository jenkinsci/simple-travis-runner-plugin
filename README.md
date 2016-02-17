# Introduction
A really quick and dirty Pipeline step to convert Travis YAML to Pipeline on the fly.

# Currently supported
- `before_install`
- `install`
- `before_script`
- `script`
- `after_failure`
- `after_success`
- `after_script`

# Where's the logic?
Actual guts are all in [SimpleTravisRunner.groovy](https://github.com/abayer/simple-travis-runner-plugin/blob/master/src/main/resources/org/jenkinsci/plugins/simpletravisrunner/SimpleTravisRunner.groovy).

# Example
An example of using a Jenkinsfile to call a .travis.yml can be found at [this repo](https://github.com/abayer/dummy-travis-test).

# TODO
- [X] ~~config.jelly and~~ \(config.jelly not needed for global variables\) help HTML for Snippet Generator and reference docs.
- [X] Tests, tests, tests! \(More tests would probably be good-to-have, but what's there covers existing functionality.\)
- [ ] Ideally, find a way to optionally output as Pipeline code rather than execute, for migration purposes.
- [X] Make sure this will fail if run outside of a `node` block.
- [ ] Allow execution of all `script` entries even if one fails.
- [ ] Time out individual `script` entries.
- [ ] Determine what language-specific environment axes will be supported natively (i.e., `rvm`, `php`, etc)
- [X] Implement environment axes equivalent to Jenkins Matrix jobs, serially initially.
- [X] Figure out how to extrapolate `node` labels for axes to enable
`parallel` usage. \(switched this up - requiring `node` context now and/or specification of a label\)
- [ ] Travis `matrix` inclusions and exclusions from environment axes (and `allow_failures`).
- [ ] \(stretch goal\) Implement auto-generation of `script` and friends for (some) languages? Debatable whether to do this.
- [ ] Decide whether to keep emulating Travis's behavior of ignore `after_*` steps' failures when setting build status.


