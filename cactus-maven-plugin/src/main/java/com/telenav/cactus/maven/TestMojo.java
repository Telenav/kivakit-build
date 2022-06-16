////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// © 2022 Telenav, Inc.
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
package com.telenav.cactus.maven;

import com.telenav.cactus.maven.git.GitCheckout;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.shared.SharedData;
import com.telenav.cactus.maven.shared.SharedDataKey;
import com.telenav.cactus.maven.tree.ProjectTree;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * A place holder for testing stuff.
 *
 * @author Tim Boudreau
 */
@SuppressWarnings("unused")
@org.apache.maven.plugins.annotations.Mojo(defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        name = "do-something", threadSafe = true)
public class TestMojo extends BaseMojo
{
    @Parameter(property = "telenav.thing", defaultValue = "not really a thing")
    private String thing;

    private static final SharedDataKey<ProjectTree> TREE_KEY = SharedDataKey.of(
            ProjectTree.class);
    private static final SharedDataKey<Set<FamilyAndScope>> SEEN_KEY = SharedDataKey
            .of(
                    Set.class);

    @Inject
    SharedData shared;

    private ProjectTree tree(MavenProject prj)
    {
        // Cache this, as creating the tree is _very_ expensive.
        return shared.computeIfAbsent(TREE_KEY, () -> ProjectTree.from(prj)
                .get());
    }

    private boolean seen(ProjectFamily family, Scope scope, GitCheckout co)
    {
        FamilyAndScope test = new FamilyAndScope(scope, family, co);
        Set<FamilyAndScope> set = shared.computeIfAbsent(SEEN_KEY, HashSet::new);
        return !set.add(test);
    }

    @Override
    public void performTasks(BuildLog log, MavenProject project) throws Exception
    {
        ProjectTree tree = tree(project);
        GitCheckout co = GitCheckout.repository(project.getBasedir()).get();
        ProjectFamily family = ProjectFamily.of(project);
        for (Scope scope : Scope.values())
        {
            if (seen(family, scope, co))
            {
                continue;
            }
            log.info(
                    "------------- " + scope + " " + family + " --------------");
            List<GitCheckout> matched = scope.matchCheckouts(tree, co, true,
                    family, project.getGroupId());
            for (GitCheckout gc : matched)
            {
                String nm = gc.name();
                if (nm.isEmpty())
                {
                    nm = "(root)";
                }
                System.out.println("  * " + nm);
            }
        }
    }

    private static final class FamilyAndScope
    {
        private final Scope scope;
        private final ProjectFamily family;
        private final GitCheckout checkout;

        public FamilyAndScope(Scope scope, ProjectFamily family,
                GitCheckout checkout)
        {
            this.scope = scope;
            this.family = family;
            this.checkout = checkout;
        }

        @Override
        public int hashCode()
        {
            int hash = 7;
            hash = 97 * hash + Objects.hashCode(this.scope);
            hash = 97 * hash + Objects.hashCode(this.family);
            hash = 97 * hash + Objects.hashCode(this.checkout);
            return hash;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            final FamilyAndScope other = (FamilyAndScope) obj;
            if (this.scope != other.scope)
                return false;
            if (!Objects.equals(this.family, other.family))
                return false;
            return Objects.equals(this.checkout, other.checkout);
        }

    }
}
