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
package org.jenkinsci.plugins.simpletravisrunner;

import hudson.model.Result;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.scm.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.steps.scm.GitStep;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class SimpleTravisRunnerDSLTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @Test public void firstDoNoHarm() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("semaphore 'wait'", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                assertEquals(Collections.<String>emptySet(), grep(b.getRootDir(),
                        "SimpleTravisRunner"));
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }

    // TODO copied from BindingStepTest, should be made into a utility in Jenkins test harness perhaps (or JenkinsRuleExt as a first step)
    private static Set<String> grep(File dir, String text) throws IOException {
        Set<String> matches = new TreeSet<String>();
        grep(dir, text, "", matches);
        return matches;
    }

    private static void grep(File dir, String text, String prefix, Set<String> matches) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            String qualifiedName = prefix + kid.getName();
            if (kid.isDirectory()) {
                grep(kid, text, qualifiedName + "/", matches);
            } else if (kid.isFile() && FileUtils.readFileToString(kid).contains(text)) {
                matches.add(qualifiedName);
            }
        }
    }

    @Test public void failIfNoFile() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('no-file')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                story.j.assertLogContains("java.io.FileNotFoundException",
                        story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
            }
        });
    }

    @Test public void failIfInNode() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "node { simpleTravisRunner('no-file') }");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                story.j.assertLogContains("ERROR: simpleTravisRunner(travisFile[, label, timeout]) cannot be run within a 'node { ... }' block.",
                        story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
            }
        });
    }

    @Test public void failIfInNotFromSCM() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "simpleTravisRunner('no-file')\n",
                        true));
                story.j.assertLogContains("ERROR: simpleTravisRunner(travisFile[, label, timeout]) can only be run in a Pipeline script from SCM.",
                        story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
            }
        });
    }

    @Test public void simpleTravisYml() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "script: ls -la");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogContains("somefile",
                        story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
                story.j.assertLogNotContains("Travis Install", b);
                story.j.assertLogContains("Travis Script", b);
            }
        });
    }

    @Test public void multipleSteps() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "script:\n" +
                        "  - ls -la\n" +
                        "  - echo pants\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogContains("somefile",
                        story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
                story.j.assertLogContains("pants", b);
                story.j.assertLogNotContains("Travis Install", b);
                story.j.assertLogContains("Travis Script", b);
            }
        });
    }

    @Test public void failureInScript() throws Exception {
        sampleRepo.init();
        sampleRepo.write(".travis.yml",
                "script: exit 1\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogNotContains("Travis Install",
                        story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b)));
                story.j.assertLogContains("Travis Script", b);
            }
        });
    }

    @Test public void multiplePhases() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "before_install: echo 'in before_install'\n" +
                        "install: echo 'in install'\n" +
                        "before_script: echo 'in before_script'\n" +
                        "script:\n" +
                        "  - ls -la\n" +
                        "  - echo pants\n" +
                        "after_script: echo 'in after_script'\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogContains("somefile",
                        story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
                story.j.assertLogContains("pants", b);
                story.j.assertLogContains("in install", b);
                story.j.assertLogContains("Travis Before Install", b);
                story.j.assertLogContains("Travis Install", b);
                story.j.assertLogContains("Travis Before Script", b);
                story.j.assertLogContains("Travis Script", b);
                story.j.assertLogContains("Travis After Script", b);
            }
        });
    }

    @Test public void fastFailOnEarlyPhases() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "before_install: exit 1\n" +
                        "install: echo 'in install'\n" +
                        "before_script: echo 'in before_script'\n" +
                        "script:\n" +
                        "  - ls -la\n" +
                        "  - echo pants\n" +
                        "after_script: echo 'in after_script'\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogContains("Travis Before Install",
                        story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b)));
                story.j.assertLogNotContains("Travis Install", b);
                story.j.assertLogNotContains("Travis Before Script", b);
                story.j.assertLogNotContains("Travis Script", b);
                story.j.assertLogNotContains("Travis After Script", b);
            }
        });
    }

    @Test public void failureOnAfterPhasesIgnored() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "before_install: echo 'in before_install'\n" +
                        "install: echo 'in install'\n" +
                        "before_script: echo 'in before_script'\n" +
                        "script:\n" +
                        "  - ls -la\n" +
                        "  - echo pants\n" +
                        "after_script: exit 1\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogContains("somefile",
                        story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
                story.j.assertLogContains("in install", b);
                story.j.assertLogContains("Travis Before Install", b);
                story.j.assertLogContains("Travis Install", b);
                story.j.assertLogContains("Travis Before Script", b);
                story.j.assertLogContains("Travis Script", b);
                story.j.assertLogContains("Travis After Script", b);
            }
        });
    }

    @Test public void afterFailure() throws Exception {
        sampleRepo.init();
        sampleRepo.write(".travis.yml",
                "script: exit 1\n" +
                        "after_failure: echo 'in after_failure'\n" +
                        "after_success: echo 'in after_success'\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogNotContains("Travis Install",
                        story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b)));
                story.j.assertLogContains("Travis Script", b);
                story.j.assertLogContains("Travis After Failure", b);
                story.j.assertLogNotContains("Travis After Success", b);

            }
        });
    }

    @Test public void afterSuccess() throws Exception {
        sampleRepo.init();
        sampleRepo.write(".travis.yml",
                "script: exit 0\n" +
                        "after_failure: echo 'in after_failure'\n" +
                        "after_success: echo 'in after_success'\n");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml')");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogNotContains("Travis Install",
                        story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
                story.j.assertLogContains("Travis Script", b);
                story.j.assertLogContains("Travis After Success", b);
                story.j.assertLogNotContains("Travis After Failure", b);

            }
        });
    }

    @Test public void timeoutStep() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "script: sleep 75");
        sampleRepo.write("Jenkinsfile", "simpleTravisRunner('.travis.yml', null, 1)");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogNotContains("Travis Install",
                        story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b)));
                story.j.assertLogContains("Travis Script", b);
                story.j.assertLogContains("Enforce time limit : Start", b);
                story.j.assertLogContains("Enforce time limit : End", b);
                story.j.assertLogContains("Sending interrupt signal to process", b);
            }
        });
    }

    // TODO: Env Matrix testing!


    // TODO: Figure out what to do about testing this on non-Unix platforms. The step won't work on Windows by design,
    // but how do we run these tests without failure there? Or do we bother worrying about it?
}
