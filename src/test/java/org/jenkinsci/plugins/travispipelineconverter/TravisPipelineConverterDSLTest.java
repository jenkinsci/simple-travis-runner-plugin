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
package org.jenkinsci.plugins.travispipelineconverter;

import hudson.model.Result;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.scm.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class TravisPipelineConverterDSLTest {

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
                        "org.jenkinsci.plugins.travispipelineconverter.TravisPipelineConverter"));
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

    @Test public void failIfNoFile() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "node {\n" +
                        "  travisPipelineConverter.run('no-file')\n" +
                        "}", true));
                story.j.assertLogContains("java.io.FileNotFoundException",
                        story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
            }
        });
    }

    @Test public void failIfNotInNode() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "travisPipelineConverter.run('no-file')\n",
                        true));
                story.j.assertLogContains("ERROR: travisPipelineConverter.run(...) can only be run within a 'node { ... }' block.",
                        story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
            }
        });
    }

    @Test public void simpleTravisYml() throws Exception {
        sampleRepo.init();
        sampleRepo.write("somefile", "");
        sampleRepo.write(".travis.yml",
                "script: ls -la");
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "node {\n" +
                        "  git(url: $/" + sampleRepo + "/$, poll: false, changelog: false)\n" +
                        "  travisPipelineConverter.run('.travis.yml')\n" +
                        "}",
                        true));
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
        sampleRepo.git("add", "somefile", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "node {\n" +
                                "  git(url: $/" + sampleRepo + "/$, poll: false, changelog: false)\n" +
                                "  travisPipelineConverter.run('.travis.yml')\n" +
                                "}",
                        true));
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
        sampleRepo.git("add", ".travis.yml");
        sampleRepo.git("commit", "--message=files");
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "node {\n" +
                                "  git(url: $/" + sampleRepo + "/$, poll: false, changelog: false)\n" +
                                "  travisPipelineConverter.run('.travis.yml')\n" +
                                "}",
                        true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.assertLogNotContains("Travis Install",
                        story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b)));
                story.j.assertLogContains("Travis Script", b);
            }
        });
    }

    // TODO: Test multiple phases

    // TODO: Test after_success and after_failure

    // TODO: Test failing fast from install, before_install, before_script

    // TODO: Test continuing to after_* phases despite failure in script

    // TODO: Test that failure in after_* phases does not cause failure of whole job? Not sure.
}
