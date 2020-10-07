/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class BackportTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This backport pull request has now been updated with issue");
            assertLastCommentContains(pr, "<!-- backport " + releaseHash.hex() + " -->");
            assertEquals(issue1Number + ": An issue", pr.title());

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            var prAsCommitter = author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            for (var c : pr.comments()) {
                System.out.println(c.body());
                System.out.println("-------------------------------");
            }
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void withSummary(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "This is a summary\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This backport pull request has now been updated with issue and summary");
            assertLastCommentContains(pr, "<!-- backport " + releaseHash.hex() + " -->");
            assertEquals(issue1Number + ": An issue", pr.title());

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            var prAsCommitter = author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            for (var c : pr.comments()) {
                System.out.println(c.body());
                System.out.println("-------------------------------");
            }
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of("This is a summary"), message.summaries());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void withMultipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue1Number = issue1.id().split("-")[1];
            var issue2Number = issue2.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "This is a summary\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This backport pull request has now been updated with issues and summary");
            assertLastCommentContains(pr, "<!-- backport " + releaseHash.hex() + " -->");
            assertEquals(issue1Number + ": An issue", pr.title());

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            var prAsCommitter = author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            for (var c : pr.comments()) {
                System.out.println(c.body());
                System.out.println("-------------------------------");
            }
            assertEquals(2, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals("Another issue", message.issues().get(1).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of("This is a summary"), message.summaries());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void nonExitingCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding backport PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport 0123456789012345678901234567890123456789");

            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "could not find any commit with hash `0123456789012345678901234567890123456789`");

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(1, pr.comments().size());
        }
    }
}
