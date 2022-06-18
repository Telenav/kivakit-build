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
package com.telenav.cactus.maven.trigger;

import java.util.Objects;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Policy that determines whether a BaseMojo's execution method should be run.
 * We have a lot of mojos which operate on one or more git checkouts, not
 * per-project, and either need to be run at the start or end of a build.
 *
 * @see RunPolicies
 * @author Tim Boudreau
 */
public interface RunPolicy
{

    boolean shouldRun(MavenProject invokedOn, MavenSession session);

    default RunPolicy and(RunPolicy other)
    {
        // For logging purposes, we need a reasonable implementation of
        // toString(), so use a local class.
        return new RunPolicy()
        {
            @Override
            public boolean shouldRun(MavenProject prj,
                    MavenSession sess)
            {
                return other.shouldRun(prj, sess)
                        && RunPolicy.this.shouldRun(prj, sess);
            }

            @Override
            public String toString()
            {
                return "(" + RunPolicy.this + " and " + other + ")";
            }
        };
    }

    default RunPolicy or(RunPolicy other)
    {
        // For logging purposes, we need a reasonable implementation of
        // toString(), so use a local class.
        return new RunPolicy()
        {
            @Override
            public boolean shouldRun(MavenProject prj,
                    MavenSession sess)
            {
                return other.shouldRun(prj, sess)
                        || RunPolicy.this.shouldRun(prj, sess);
            }

            @Override
            public String toString()
            {
                return "(" + RunPolicy.this + " or " + other + ")";
            }
        };
    }

    default RunPolicy negate()
    {
        return new RunPolicy()
        {
            @Override
            public boolean shouldRun(MavenProject prj,
                    MavenSession sess)
            {
                return !RunPolicy.this.shouldRun(prj, sess);
            }

            @Override
            public RunPolicy negate()
            {
                return RunPolicy.this;
            }

            @Override
            public String toString()
            {
                return "!" + RunPolicy.this;
            }
        };
    }

    static RunPolicy forPackaging(String packaging)
    {
        return new RunPolicy()
        {
            @Override
            public boolean shouldRun(MavenProject prj,
                    MavenSession sess)
            {
                return Objects.equals(packaging, prj.getPackaging());
            }

            @Override
            public String toString()
            {
                return "packaging=" + packaging;
            }
        };
    }
}
