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
package com.telenav.cactus.maven.tree;

import com.mastfrog.function.optional.ThrowingOptional;
import com.mastfrog.util.preconditions.Exceptions;
import com.telenav.cactus.scope.ProjectFamily;
import com.telenav.cactus.git.Branches;
import com.telenav.cactus.git.GitCheckout;
import com.telenav.cactus.git.Heads;
import com.telenav.cactus.maven.model.Pom;
import com.telenav.cactus.scope.Scope;
import java.io.IOException;
import java.nio.file.Files;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author Tim Boudreau
 */
public class ProjectTree
{

    private final GitCheckout root;
    private final AtomicBoolean upToDate = new AtomicBoolean();
    private final Cache cache = new Cache();

    ProjectTree(GitCheckout root)
    {
        this.root = root;
    }

    public GitCheckout root()
    {
        return root;
    }

    public static ThrowingOptional<ProjectTree> from(MavenProject project)
    {
        return from(project.getBasedir().toPath());
    }

    public static ThrowingOptional<ProjectTree> from(Path fileOrFolder)
    {
        return ThrowingOptional.from(GitCheckout.repository(fileOrFolder))
                .flatMapThrowing(repo -> repo.submoduleRoot())
                .map(ProjectTree::new);
    }

    public void invalidateCache()
    {
        if (upToDate.compareAndSet(true, false))
        {
            cache.clear();
        }
    }

    private synchronized <T> T withCache(Function<Cache, T> func)
    {
        if (upToDate.compareAndSet(false, true))
        {
            cache.populate();
        }
        return func.apply(cache);
    }

    public Optional<Pom> findProject(String groupId, String artifactId)
    {
        return withCache(c ->
        {
            return c.project(groupId, artifactId);
        });
    }

    public boolean areVersionsConsistent()
    {
        return allVersions().size() <= 1;
    }

    public Set<String> allBranches(Predicate<GitCheckout> pred)
    {

        return allCheckouts().stream()
                // filter to the checkouts we want
                .filter(pred)
                // map to the branch, which may not be present
                .map(co -> co.branch().orElse(""))
                // prune those that are not on a branch
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
    }

    public Set<String> allBranches()
    {
        Set<String> branches = new HashSet<>();
        this.allCheckouts().forEach(checkout ->
        {
            checkout.branch().ifPresent(branches::add);
        });
        return branches;
    }

    public Set<String> allVersions()
    {
        Set<String> result = new HashSet<>();
        allProjects().forEach(pom ->
        {
            result.add(pom.version().text());
        });
        return result;
    }

    public Set<String> allVersions(Predicate<Pom> test)
    {
        Set<String> result = new HashSet<>();
        allProjects().forEach(pom ->
        {
            if (test.test(pom))
            {
                result.add(pom.version().text());
            }
        });
        return result;
    }

    public Set<Pom> allProjects()
    {
        return withCache(Cache::allPoms);
    }

    public Set<Pom> projectsForGroupId(String groupId)
    {
        Set<Pom> result = new TreeSet<>();
        allProjects().forEach(project ->
        {
            if (groupId.equals(project.coordinates().groupId))
            {
                result.add(project);
            }
        });
        return result;
    }

    public Set<Pom> projectsForFamily(ProjectFamily fam)
    {
        Set<Pom> result = new TreeSet<>();
        allProjects().forEach(project ->
        {
            if (fam.equals(ProjectFamily.fromGroupId(project.groupId().text())))
            {
                result.add(project);
            }
        });
        return result;

    }

    public Map<String, Set<String>> branchesByGroupId()
    {
        return withCache(c ->
        {
            Map<String, Set<String>> result = new TreeMap<>();
            c.allPoms().forEach(pom ->
            {
                GitCheckout.repository(pom.path()).ifPresent(checkout ->
                {
                    Set<String> branches = result.computeIfAbsent(
                            pom.groupId().text(), g -> new TreeSet<>());
                    checkout.branches().localBranches().forEach(br ->
                    {
                        branches.add(br.trackingName());
                    });
                    checkout.branches().remoteBranches().forEach(br ->
                    {
                        branches.add(br.trackingName());
                    });
                });
            });
            return result;
        });
    }

    public Map<String, Map<String, Set<Pom>>> projectsByBranchByGroupId(
            Predicate<Pom> filter)
    {
        return withCache(c ->
        {
            Map<String, Map<String, Set<Pom>>> result = new TreeMap<>();
            for (Pom pom : c.allPoms())
            {
                if (!filter.test(pom))
                {
                    continue;
                }
                Map<String, Set<Pom>> infosByBranch = result.computeIfAbsent(
                        pom.groupId().text(), id -> new TreeMap<>());
                GitCheckout.repository(pom.path()).ifPresent(checkout ->
                {
                    c.branchFor(checkout).ifPresent(branch ->
                    {
                        Set<Pom> set = infosByBranch.computeIfAbsent(branch,
                                b -> new TreeSet<>());
                        set.add(pom);
                    });
                });
            }
            return result;
        });
    }

    public Map<String, Map<String, Set<Pom>>> projectsByGroupIdAndVersion()
    {
        Map<String, Map<String, Set<Pom>>> result = new TreeMap<>();
        projectsByGroupId().forEach((gid, poms) ->
        {
            Map<String, Set<Pom>> subMap = result.computeIfAbsent(gid,
                    g -> new TreeMap<>());
            for (Pom info : poms)
            {
                Set<Pom> pomSet = subMap.computeIfAbsent(info.version()
                        .text(),
                        v -> new TreeSet<>());
                pomSet.add(info);
            }
        });
        return result;
    }

    public Set<String> groupIdsIn(GitCheckout checkout)
    {
        return withCache(c ->
        {
            return c.projectsWithin(checkout).stream().map(
                    info -> info.groupId().toString())
                    .collect(Collectors.toCollection(HashSet::new));
        });
    }

    public Map<String, Set<Pom>> projectsByGroupId()
    {
        Map<String, Set<Pom>> result = new TreeMap<>();
        allProjects().forEach(pom ->
        {
            Set<Pom> set = result.computeIfAbsent(pom.groupId().text(),
                    x -> new TreeSet<>());
            set.add(pom);
        });
        return result;
    }

    public Set<Path> allProjectFolders()
    {
        return withCache(c ->
        {
            Set<Path> result = new HashSet<>();
            c.allPoms().forEach(pom -> result.add(pom.projectFolder()));
            return result;
        });
    }

    public Map<String, Set<Pom>> projectsByVersion(Predicate<Pom> filter)
    {
        return withCache(c ->
        {
            Map<String, Set<Pom>> result = new TreeMap<>();
            c.allPoms().forEach(pom ->
            {
                if (filter.test(pom))
                {
                    Set<Pom> infos = result.computeIfAbsent(pom.version()
                            .text(),
                            v -> new TreeSet<>());
                    infos.add(pom);
                }
            });
            return result;
        });
    }

    public Optional<Pom> projectOf(Path file)
    {
        withCache(c ->
        {
            Path realFile;
            if (Files.isDirectory(file) && Files.exists(file.resolve("pom.xml")))
            {
                realFile = file.resolve("pom.xml");
            }
            else
            {
                realFile = file;
            }
            if ("pom.xml".equals(realFile.getFileName()))
            {
                for (Pom pom : c.allPoms())
                {
                    if (pom.path().equals(realFile))
                    {
                        return Optional.of(pom);
                    }
                }
            }
            List<Path> paths = new ArrayList<>();
            Map<Path, Pom> candidateItems = new HashMap<>();
            c.projectFolders().forEach((dir, pomInfo) ->
            {
                if (realFile.startsWith(dir))
                {
                    candidateItems.put(dir, pomInfo);
                    paths.add(dir);
                }
            });
            if (paths.isEmpty())
            {
                return Optional.empty();
            }
            Collections.sort(paths, (a, b) ->
            {
                // reverse sort
                return Integer.compare(b.getNameCount(), a.getNameCount());
            });
            return Optional.of(candidateItems.get(paths.get(0)));
        });
        return Optional.empty();
    }

    public GitCheckout checkoutFor(Pom info)
    {
        return withCache(c -> c.checkoutForPom.get(info));
    }

    public Set<GitCheckout> allCheckouts()
    {
        return withCache(Cache::allCheckouts);
    }

    public Optional<String> branchFor(GitCheckout checkout)
    {
        return withCache(c -> c.branchFor(checkout));
    }

    public boolean isDirty(GitCheckout checkout)
    {
        return withCache(c -> c.isDirty(checkout));
    }

    public Set<GitCheckout> checkoutsFor(Collection<? extends Pom> infos)
    {
        return withCache(c ->
        {
            Set<GitCheckout> result = new TreeSet<>();
            infos.forEach(pom -> c.checkoutFor(pom).ifPresent(result::add));
            return result;
        });
    }

    public Branches branches(GitCheckout checkout)
    {
        return withCache(c -> c.branches(checkout));
    }

    public Optional<String> mostCommonBranchForGroupId(String groupId)
    {
        return withCache(c -> c.mostCommonBranchForGroupId(groupId));
    }

    public boolean isDetachedHead(GitCheckout checkout)
    {
        return withCache(c -> c.isDetachedHead(checkout));
    }

    public Set<Pom> projectsWithin(GitCheckout checkout)
    {
        return withCache(c -> c.projectsWithin(checkout));
    }

    public Set<GitCheckout> nonMavenCheckouts()
    {
        return withCache(c -> c.nonMavenCheckouts());
    }

    public Set<GitCheckout> checkoutsContainingGroupId(String groupId)
    {
        return withCache(c -> c.checkoutsContainingGroupId(groupId));
    }

    public Set<GitCheckout> checkoutsInProjectFamily(ProjectFamily family)
    {
        return withCache(c -> c.checkoutsInProjectFamily(family));
    }

    public Set<GitCheckout> checkoutsInProjectFamilyOrChildProjectFamily(
            ProjectFamily family)
    {
        return withCache(c -> c.checkoutsInProjectFamilyOrChildProjectFamily(
                family));
    }

    public Heads remoteHeads(GitCheckout checkout)
    {
        return withCache(c -> c.remoteHeads(checkout));
    }

    /**
     * Get a depth-first list of checkouts matching this scope, given the passed
     * contextual criteria.
     *
     * @param tree A project tree
     * @param callingProjectsCheckout The checkout of the a mojo is currently
     * being run against.
     * @param includeRoot If true, include the root (submodule parent) checkout
     * in the returned list regardless of whether it directly contains a maven
     * project matching the other criteria (needed for operations that change
     * the head commit of a submodule, which will generate modifications in the
     * submodule parent project.
     * @param callingProjectsGroupId The group id of the project whose mojo is
     * being invoked
     */
    public List<GitCheckout> matchCheckouts(Scope scope,
            GitCheckout callingProjectsCheckout, boolean includeRoot,
            ProjectFamily family, String callingProjectsGroupId)
    {
        Set<GitCheckout> checkouts;
        switch (scope)
        {
            case FAMILY:
                checkouts = checkoutsInProjectFamily(family);
                break;
            case FAMILY_OR_CHILD_FAMILY:
                checkouts = checkoutsInProjectFamilyOrChildProjectFamily(
                        family);
                break;
            case SAME_GROUP_ID:
                checkouts = checkoutsContainingGroupId(
                        callingProjectsGroupId);
                break;
            case JUST_THIS:
                checkouts = new HashSet<>(Arrays.asList(callingProjectsCheckout));
                break;
            case ALL_PROJECT_FAMILIES:
                checkouts = new HashSet<>(allCheckouts());
                break;
            case ALL:
                checkouts = new HashSet<>(allCheckouts());
                checkouts.addAll(nonMavenCheckouts());
                break;
            default:
                throw new AssertionError(this);
        }
        checkouts = new LinkedHashSet<>(checkouts);
        if (!includeRoot)
        {
            callingProjectsCheckout.submoduleRoot().ifPresent(checkouts::remove);
        }
        else
        {
            if (!checkouts.isEmpty()) // don't generate a push of _just_ the root checkout
            {
                callingProjectsCheckout.submoduleRoot()
                        .ifPresent(checkouts::add);
            }
        }
        return GitCheckout.depthFirstSort(checkouts);
    }

    final class Cache
    {

        private final Map<String, Map<String, Pom>> infoForGroupAndArtifact
                = new ConcurrentHashMap<>();
        private final Map<GitCheckout, Set<Pom>> projectsByRepository
                = new ConcurrentHashMap<>();
        private final Map<Pom, GitCheckout> checkoutForPom = new ConcurrentHashMap<>();
        private final Map<GitCheckout, Optional<String>> branches = new HashMap<>();
        private final Map<GitCheckout, Boolean> dirty = new ConcurrentHashMap<>();
        private final Map<GitCheckout, Branches> allBranches = new ConcurrentHashMap<>();
        private final Map<String, Optional<String>> branchByGroupId = new HashMap<>();
        private final Map<GitCheckout, Boolean> detachedHeads = new ConcurrentHashMap<>();
        private final Set<GitCheckout> nonMavenCheckouts = new HashSet<>();
        private final Map<GitCheckout, Heads> remoteHeads = new HashMap<>();
        private Map<ProjectFamily, Set<GitCheckout>> checkoutsForProjectFamily = new ConcurrentHashMap<>();

        public Heads remoteHeads(GitCheckout checkout)
        {
            return remoteHeads.computeIfAbsent(checkout, ck -> ck.remoteHeads());
        }

        public Set<GitCheckout> checkoutsContainingGroupId(String groupId)
        {
            Set<GitCheckout> all = new HashSet<>();
            projectsByRepository.forEach((repo, projectSet) ->
            {
                for (Pom project : projectSet)
                {
                    if (groupId.equals(project.coordinates().groupId))
                    {
                        all.add(repo);
                        break;
                    }
                }
            });
            return all;
        }

        public Set<GitCheckout> checkoutsInProjectFamily(ProjectFamily family)
        {
            return checkoutsForProjectFamily.computeIfAbsent(family,
                    key ->
            {
                Set<GitCheckout> all = new HashSet<>();
                projectsByRepository.forEach((repo, projectSet) ->
                {
                    for (Pom project : projectSet)
                    {
                        if (family.equals(ProjectFamily.fromGroupId(
                                project.groupId().text())))
                        {
                            all.add(repo);
                            break;
                        }
                    }
                });
                return all;
            });

        }

        public Set<GitCheckout> checkoutsInProjectFamilyOrChildProjectFamily(
                ProjectFamily family)
        {
            Set<GitCheckout> all = new HashSet<>();
            projectsByRepository.forEach((repo, projectSet) ->
            {
                for (Pom project : projectSet)
                {
                    ProjectFamily pomFamily = ProjectFamily.familyOf(
                            project.groupId());
                    if (family.equals(pomFamily) || family.isParentFamilyOf(
                            project.coordinates().groupId))
                    {
                        all.add(repo);
                        break;
                    }
                }
            });
            return all;
        }

        public Set<GitCheckout> nonMavenCheckouts()
        {
            return Collections.unmodifiableSet(nonMavenCheckouts);
        }

        public boolean isDetachedHead(GitCheckout checkout)
        {
            return detachedHeads.computeIfAbsent(checkout,
                    GitCheckout::isDetachedHead);
        }

        public Optional<String> mostCommonBranchForGroupId(String groupId)
        {
            // Cache these since they are expensive to compute
            return branchByGroupId.computeIfAbsent(groupId,
                    this::_mostCommonBranchForGroupId);
        }

        private Optional<String> _mostCommonBranchForGroupId(String groupId)
        {
            // Collect the number of times a branch name is used in a checkout
            // we have
            Map<String, Integer> branchNameCounts = new HashMap<>();
            Set<GitCheckout> seen = new HashSet<>();
            // Count each checkout exactly once, if it is on a branch
            checkoutForPom.forEach((pom, checkout) ->
            {
                // Filter out any irrelevant or already examined checkouts
                if (seen.contains(checkout) || !pom.groupId().is(groupId))
                {
                    return;
                }
                // If we are on a branch, collect its name and add to the number
                // of times it has been seen
                branchFor(checkout).ifPresent(branch ->
                {
                    seen.add(checkout);
                    branchNameCounts.compute(branch, (b, old) ->
                    {
                        if (old == null)
                        {
                            return 1;
                        }
                        return old + 1;
                    });
                });
            });
            // If we found nothing, we're done
            if (branchNameCounts.isEmpty())
            {
                return Optional.empty();
            }
            // Reverse sort the map entries by the count
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(
                    branchNameCounts.entrySet());
            Collections.sort(entries, (a, b) ->
            {
                return b.getValue().compareTo(a.getValue());
            });
            // And take the greatest
            return Optional.of(entries.get(0).getKey());
        }

        public Set<Pom> projectsWithin(GitCheckout checkout)
        {
            Set<Pom> infos = projectsByRepository.get(checkout);
            return infos == null
                   ? Collections.emptySet()
                   : infos;
        }

        public Branches branches(GitCheckout checkout)
        {
            return allBranches.computeIfAbsent(checkout, co -> co.branches());
        }

        public Optional<GitCheckout> checkoutFor(Pom info)
        {
            return Optional.ofNullable(checkoutForPom.get(info));
        }

        public boolean isDirty(GitCheckout checkout)
        {
            return dirty.computeIfAbsent(checkout, GitCheckout::isDirty);
        }

        public Set<GitCheckout> allCheckouts()
        {
            return Collections.unmodifiableSet(projectsByRepository.keySet());
        }

        public Optional<String> branchFor(GitCheckout checkout)
        {
            return branches.computeIfAbsent(checkout, GitCheckout::branch);
        }

        public Map<Path, Pom> projectFolders()
        {
            Map<Path, Pom> infos = new HashMap<>();
            allPoms().forEach(pom -> infos.put(pom.path().getParent(), pom));
            return infos;
        }

        public Set<Pom> allPoms()
        {
            Set<Pom> set = new HashSet<>();
            projectsByRepository.forEach((repo, infos) -> set.addAll(infos));
            return set;
        }

        Optional<Pom> project(String groupId, String artifactId)
        {
            Map<String, Pom> map = infoForGroupAndArtifact.get(groupId);
            if (map == null)
            {
                return Optional.empty();
            }
            return Optional.ofNullable(map.get(artifactId));
        }

        void clear()
        {
            infoForGroupAndArtifact.clear();
            projectsByRepository.clear();
            checkoutForPom.clear();
            branches.clear();
            dirty.clear();
            allBranches.clear();
            branchByGroupId.clear();
            nonMavenCheckouts.clear();
            detachedHeads.clear();
            checkoutsForProjectFamily.clear();
            remoteHeads.clear();
        }

        synchronized void populate()
        {
            try
            {
                root.allPomFilesInSubtreeParallel(this::cacheOnePomFile);
            }
            catch (IOException ex)
            {
                Exceptions.chuck(ex);
            }
        }

        private final Map<GitCheckout, GitCheckout> repoInternTable = new ConcurrentHashMap<>();

        private GitCheckout intern(GitCheckout co)
        {
            GitCheckout result = repoInternTable.putIfAbsent(co, co);
            if (result == null)
            {
                result = co;
            }
            return result;
        }

        private void cacheOnePomFile(Path path)
        {
//            System.out.println(
//                    "C1 " + Thread.currentThread().getName() + "\t" + path
//                    .getParent().getFileName());
            Pom.from(path).ifPresent(info ->
            {
                Map<String, Pom> subcache
                        = infoForGroupAndArtifact.computeIfAbsent(
                                info.groupId().text(),
                                id -> new ConcurrentHashMap<>());
                subcache.put(info.coordinates().artifactId.text(), info);
                GitCheckout.repository(info.path()).ifPresent(co ->
                {
                    co = intern(co);
                    Set<Pom> poms = projectsByRepository.computeIfAbsent(co,
                            c -> new HashSet<>());
                    poms.add(info);
                    checkoutForPom.put(info, co);
                });
            });
        }
    }
}
