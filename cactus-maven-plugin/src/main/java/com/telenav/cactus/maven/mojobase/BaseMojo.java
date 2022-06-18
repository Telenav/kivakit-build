////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// © 2011-2022 Telenav, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
package com.telenav.cactus.maven.mojobase;

import com.telenav.cactus.maven.scope.ProjectFamily;
import com.telenav.cactus.maven.trigger.RunPolicies;
import com.telenav.cactus.maven.trigger.RunPolicy;
import com.mastfrog.function.optional.ThrowingOptional;
import com.mastfrog.function.throwing.ThrowingBiConsumer;
import com.mastfrog.function.throwing.ThrowingConsumer;
import com.mastfrog.function.throwing.ThrowingFunction;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.util.preconditions.Exceptions;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.tree.ProjectTree;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.RemoteRepository;

import static com.mastfrog.util.preconditions.Checks.notNull;

/**
 * A base class for our mojos, which sets up a build logger and provides a way
 * to access some commonly needed types.
 *
 * @author Tim Boudreau
 */
public abstract class BaseMojo extends AbstractMojo
{

    protected static final String MAVEN_CENTRAL_REPO
            = "https://repo1.maven.org/maven2";
    // These are magically injected by Maven:
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    protected BuildLog log;

    ThrowingOptional<ProjectTree> tree;
    private final RunPolicy policy;

    protected BaseMojo(RunPolicy policy)
    {
        this.policy = notNull("policy", policy);
    }

    protected BaseMojo()
    {
        this(RunPolicies.EVERY);
    }

    protected BaseMojo(boolean oncePerSession)
    {
        this(oncePerSession
             ? RunPolicies.LAST
             : RunPolicies.FIRST);
    }

    /**
     * Get the project family for the project the mojo is being run against. The
     * result can be overridden by returning a valid famiily string from
     * <code>overrideProjectFamily()</code> if necessary.
     *
     * @return A project family
     */
    protected final ProjectFamily projectFamily()
    {
        String overriddenFamily = overrideProjectFamily();
        if (overriddenFamily == null || overriddenFamily.isEmpty())
        {
            return ProjectFamily.of(project());
        }
        return ProjectFamily.named(overriddenFamily);
    }

    /**
     * If this mojo allows the project family to be replaced by a parameter, it
     * can provide that here.
     *
     * @return null by default
     */
    protected String overrideProjectFamily()
    {
        return null;
    }

    /**
     * Override to return true if the mojo is intended to run exactly one time
     * for *all* repositories in the checkout, and should not do its work once
     * for every sub-project when called from a multi-module pom.
     *
     * @return true if the mojo should only be run once, on the last project
     */
    protected RunPolicy runPolicy()
    {
        return policy;
    }

    /**
     * Run some code which throws an exception in a context such as
     * <code>Stream.forEach()</code> where you cannot throw checked exceptions.
     *
     * @param r Something to run
     */
    protected static void quietly(ThrowingRunnable r)
    {
        r.toNonThrowing().run();
    }

    /**
     * Create the project try; package private so that SharedProjectTreeMojo can
     * use the shared data to cache the instance.
     *
     * @param invalidateCache Whether or not to invalidate the cache.
     * @return A project tree, if one can be constructed.
     */
    ThrowingOptional<ProjectTree> projectTreeInternal(boolean invalidateCache)
    {
        if (tree == null)
        {
            tree = ProjectTree.from(project());
        }
        else
        {
            if (invalidateCache)
            {
                tree.ifPresent(ProjectTree::invalidateCache);
            }
        }
        return tree;
    }

    protected final ThrowingOptional<ProjectTree> projectTree(
            boolean invalidateCache)
    {
        return projectTreeInternal(invalidateCache);
    }

    /**
     * Get a project tree for the project this mojo is run on. Note this is an
     * expensive operation.
     *
     * @return An optional
     */
    protected final ThrowingOptional<ProjectTree> projectTree()
    {
        return projectTree(true);
    }

    /**
     * Run something against the project tree if one can be constructed.
     *
     * @param <T> The return value type
     * @param func A function applied to the project tree
     * @return An optional result
     */
    protected final <T> ThrowingOptional<T> withProjectTree(
            ThrowingFunction<ProjectTree, T> func)
    {
        return projectTree().map(func);
    }

    /**
     * Run something against the project tree.
     *
     * @param cons A consumer
     * @return true if the code was run
     */
    protected final boolean withProjectTree(ThrowingConsumer<ProjectTree> cons)
    {
        return projectTree().ifPresent(cons);
    }

    /**
     * Run something against the project tree if one can be constructed.
     *
     * @param <T> The return value type
     * @param invalidateCache Whether or not the tree's cache should be cleared
     * before returning the instance if it already existed
     * @param func A function applied to the project tree
     * @return An optional result
     */
    protected final <T> ThrowingOptional<T> withProjectTree(
            boolean invalidateCache,
            ThrowingFunction<ProjectTree, T> func)
    {
        return projectTree(invalidateCache).map(func);
    }

    /**
     * Run something against the project tree.
     *
     * @param invalidateCache Whether or not the tree's cache should be cleared
     * before returning the instance if it already existed
     * @param cons A consumer
     * @return true if the code was run
     */
    protected final boolean withProjectTree(boolean invalidateCache,
            ThrowingConsumer<ProjectTree> cons)
    {
        return projectTree(invalidateCache).ifPresent(cons);
    }

    /**
     * Get the build log for this mojo.
     *
     * @return a logger
     */
    protected final BuildLog log()
    {
        if (log == null)
        {
            log = new BuildLog(getClass());
        }
        return log;
    }

    /**
     * Get the project this mojo is invoked against.
     *
     * @return A project
     */
    protected final MavenProject project()
    {
        return project;
    }

    private void internalValidateParameters(BuildLog log, MavenProject project)
            throws Exception
    {
        if (project == null)
        {
            throw new MojoFailureException("Project was not injected");
        }
        if (mavenSession == null)
        {
            throw new MojoFailureException("MavenSession was not injected");
        }
        internalSubclassValidateParameters(log, project);
        validateParameters(log, project);
    }

    void internalSubclassValidateParameters(BuildLog log, MavenProject project)
            throws Exception
    {

    }

    /**
     * Perform any fail-fast validation here; a super call is not needed.
     *
     * @param log The log
     * @param project A project
     * @throws Exception if something goes wrong
     */
    protected void validateParameters(BuildLog log, MavenProject project) throws Exception
    {
        // do nothing - for subclassers
    }

    /**
     * Implementation of <code>Mojo.execute()</code>, which delegates to
     * <code>performTasks()</code> after validating the parameters.
     *
     * @throws MojoExecutionException If mojo execution fails
     * @throws MojoFailureException If the mojo could not be executed
     */
    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException
    {
        if (policy.shouldRun(project, mavenSession))
        {
            run(this::performTasks);
        }
        else
        {
            new BuildLog(getClass()).info("Skipping " + getClass()
                    .getSimpleName() + " mojo "
                    + " per policy " + policy);
        }
    }

    /**
     * Get the maven session associated with this mojo.
     *
     * @return A session
     */
    protected final MavenSession session()
    {
        return mavenSession;
    }

    /**
     * Override to do the work of this mojo.
     *
     * @param log A log
     * @param project The project
     * @throws Exception If something goes wrong
     */
    protected abstract void performTasks(BuildLog log, MavenProject project)
            throws Exception;

    private void run(ThrowingBiConsumer<BuildLog, MavenProject> run)
            throws MojoExecutionException, MojoFailureException
    {
        try
        {
            BuildLog theLog = log();
            theLog.run(() ->
            {
                internalValidateParameters(theLog, project());
                run.accept(theLog, project());
            });
        }
        catch (MojoFailureException | MojoExecutionException e)
        {
            throw e;
        }
        catch (Exception | Error e)
        {
            Throwable t = e;
            if (e instanceof java.util.concurrent.CompletionException && e
                    .getCause() != null)
            {
                t = e.getCause();
            }
            if (e instanceof java.util.concurrent.ExecutionException && e
                    .getCause() != null)
            {
                t = e.getCause();
            }
            throw new MojoFailureException(t);
        }
    }

    /**
     * Throws an exception if a branch name passed in is invalid.
     *
     * @param branchName A branch name
     * @param nullOk IF true and the branch is null, simply returns
     * @throws MojoExecutionException if the branch is invalid by these criteria
     */
    protected void validateBranchName(String branchName, boolean nullOk)
            throws MojoExecutionException
    {
        if (branchName == null)
        {
            if (nullOk)
            {
                return;
            }
            fail("Branch name unset");
        }
        if (branchName.isBlank() || !branchName.startsWith("-") && branchName
                .contains(" ")
                && !branchName.contains("\"") && !branchName.contains("'"))
        {
            fail("Illegal branch name format: '" + branchName + "'");
        }
    }

    /**
     * Simplified way to throw a MojoExecutionException.
     *
     * @param <T> A type
     * @param msg A message
     * @return Nothing, but parameterized so that this method can be an exit
     * point of any method that returns something
     * @throws MojoExecutionException always, using the passed message
     */
    public <T> T fail(String msg) throws MojoExecutionException
    {
        throw new MojoExecutionException(this, msg, msg);
    }

    /**
     * Downloads or finds in the local repo an artifact from maven central
     * (overridable) independent of what the dependencies of the project are.
     *
     * @param groupId A group id
     * @param artifactId An artifact id
     * @param version A version
     * @return An ArtifactFetcher which can be used to configure the artifact
     * type and repository if needed, and then fetch the artifact.
     */
    protected ArtifactFetcher downloadArtifact(String groupId, String artifactId,
            String version)
    {
        return new ArtifactFetcher(groupId, artifactId, version, mavenSession);
    }

    protected static final class ArtifactFetcher
    {

        private String type = "jar";
        private String repoUrl = MAVEN_CENTRAL_REPO;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final BuildLog log;
        private final MavenSession session;

        private ArtifactFetcher(String groupId, String artifactId,
                String version, MavenSession session)
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.log = BuildLog.get().child("fetch").child(groupId).child(
                    artifactId).child(version);
            this.session = session;
        }

        /**
         * Set the artifact type, if you want something other than the default
         * of "jar".
         *
         * @param type A type
         * @return this
         */
        public ArtifactFetcher withType(String type)
        {
            this.type = notNull("type", type);
            return this;
        }

        /**
         * Change the repository URL used (the default is Maven Central).
         *
         * @param repoUrl A repository URL
         * @return this
         * @throws MalformedURLException if the URL is invalid
         */
        @SuppressWarnings("ResultOfObjectAllocationIgnored")
        public ArtifactFetcher withRepositoryURL(String repoUrl)
        {
            try
            {
                new URL(notNull("repoUrl", repoUrl));
            }
            catch (MalformedURLException ex)
            {
                log.error("Invalid repository URL '" + repoUrl);
                return Exceptions.chuck(new MojoExecutionException(
                        "Invalid repository URL '" + repoUrl + '\''));
            }
            this.repoUrl = repoUrl;
            return this;
        }

        /**
         * Download the artifact if needed, returning a Path to it in the local
         * repository.
         *
         * @return A path
         * @throws MojoFailureException
         */
        public Path get() throws MojoFailureException
        {
            Artifact af = new DefaultArtifact(
                    notNull("groupId", groupId),
                    notNull("artifactId", artifactId),
                    notNull("type", type),
                    notNull("version", version));

            LocalArtifactRequest locArtifact = new LocalArtifactRequest();
            locArtifact.setArtifact(af);
            RemoteRepository remoteRepo = new RemoteRepository.Builder("central",
                    "x", repoUrl).build();

            locArtifact.setRepositories(Collections.singletonList(remoteRepo));
            RepositorySystemSession sess = session.getRepositorySession();
            LocalArtifactResult res = sess.getLocalRepositoryManager()
                    .find(sess, locArtifact);

            log.info("Download result for " + af + ": " + res);
            if (res != null && res.getFile() != null)
            {
                log.info("Have local " + artifactId + " " + type + " "
                        + res.getFile());
                return res.getFile().toPath();
            }
            throw new MojoFailureException("Could not download " + af + " from "
                    + remoteRepo.getUrl());
        }
    }
}
