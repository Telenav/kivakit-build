package com.telenav.cactus.maven.commit;

import com.telenav.cactus.metadata.BuildMetadata;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.telenav.cactus.maven.common.CactusCommonPropertyNames.PLUGIN_FAMILY_NAME;
import static java.lang.Math.min;

/**
 * Consistently formatted commit messages with provenance information.
 *
 * @author Tim Boudreau
 */
public class CommitMessage
{
    private final String provenanceIdentifier;
    private final String generatorClass;
    private final StringBuilder summary = new StringBuilder();
    private final StringBuilder detail = new StringBuilder();
    private final List<Section<CommitMessage>> sections = new ArrayList<>();

    public CommitMessage(Class<?> generator, String provenance,
            String initialSummary)
    {
        this.provenanceIdentifier = provenance;
        this.summary.append(initialSummary);
        this.generatorClass = generator.getSimpleName();
    }

    public CommitMessage(Class<?> generator, String summary)
    {
        this(generator, PLUGIN_FAMILY_NAME, summary);
    }

    public CommitMessage appendToSummary(CharSequence what)
    {
        summary.append(what);
        return this;
    }

    public CommitMessage append(CharSequence what)
    {
        detail.append(what).append('\n');
        return this;
    }

    public Section<CommitMessage> section(String title)
    {
        return new Section<>(title, 0, section ->
        {
            sections.add(section);
            return this;
        });
    }

    /**
     * Provides a generic function that can be passed to libraries that need to
     * add sections without requiring they depend on this api.
     *
     * @param sections A set that created sections will be placed into, so they
     * can be closed
     * @return a function
     */
    public Function<? super String, Consumer<Object>> sectionFunction(
            List<? super Section<?>> sections)
    {
        return heading ->
        {
            Section<?> sect = section(heading);
            sections.add(sect);
            return sect::bulletPoint;
        };
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        if (summary.length() == 0)
        {
            sb.append("(summary line not provided)");
        }
        else
        {
            sb.append(summary);
        }
        sb.append("\n\n");
        if (detail.length() > 0)
        {
            sb.append(detail);
        }
        if (sb.charAt(sb.length() - 1) != '\n')
        {
            sb.append('\n');
        }
        for (Object sec : sections())
        {
            sb.append(sec);
        }
        return sb.toString();
    }

    private List<Object> sections()
    {
        List<Object> result = new ArrayList<>(sections);
        result.add(provenanceInfo());
        return result;
    }

    private Section<?> provenanceInfo()
    {
        Section<Void> result = new Section<>("Provenance", 4, sec ->
        {
            return null;
        });
        BuildMetadata meta = BuildMetadata.of(CommitMessage.class);
        result.paragraph("Generated by " + provenanceIdentifier + ": *"
                + meta.projectProperties().get("project-artifact-id")
                + "* version _"
                + meta.projectProperties().get("project-version")
                + "_.\n");
        result.bulletPoint("Mojo:\t\t" + generatorClass);
        result.bulletPoint("Generation-Time:\t" + Instant.now().with(
                ChronoField.NANO_OF_SECOND, 0));
        result.bulletPoint("Plugin-Build:\t" + meta.buildProperties().get(
                BuildMetadata.KEY_BUILD_NAME));
        result.bulletPoint("Plugin-Date:\t" + meta.buildProperties().get(
                BuildMetadata.KEY_BUILD_DATE));
        result.bulletPoint("Plugin-Commit:\t" + meta.buildProperties().get(
                BuildMetadata.KEY_GIT_COMMIT_HASH));
        result.bulletPoint("Plugin-Repo-Clean:\t" + meta.buildProperties()
                .get(
                        BuildMetadata.KEY_GIT_REPO_CLEAN));
        return result;
    }

    public static final class Section<P> implements AutoCloseable
    {
        private final List<Object> items = new ArrayList<>();
        private final String title;
        private final StringBuilder description = new StringBuilder();
        private final int depth;
        private final Function<Section<P>, P> onClose;

        public Section(String title, int depth, Function<Section<P>, P> onClose)
        {
            this.title = title;
            this.depth = depth;
            this.onClose = onClose;
        }

        public void close()
        {
            finish();
        }

        public P finish()
        {
            return onClose.apply(this);
        }

        public Section<P> bulletPoint(Object text)
        {
            items.add("  * " + text);
            return this;
        }

        public Section<P> paragraph(String text)
        {
            items.add(text);
            return this;
        }

        public Section<Section<P>> subsection(String title)
        {
            return new Section<>(title, depth + 1, subSection ->
            {
                items.add(subSection);
                return this;
            });
        }

        @Override
        public String toString()
        {
            if (items.isEmpty() && description.length() == 0)
            {
                return "";
            }
            StringBuilder result = new StringBuilder("\n");
            if (!title.isEmpty())
            {
                // Generate the cleanest markdown we can, since this will be read as text
                if (depth == 0)
                {
                    result.append('\n').append(title).append('\n');
                    for (int i = 0; i < title.length(); i++)
                    {
                        result.append('-');
                    }
                    result.append("\n\n");
                }
                else
                {
                    int count = min(depth, 5) + 1;
                    for (int i = 0; i < count; i++)
                    {
                        result.append('#');
                    }
                    result.append(' ').append(title).append("\n\n");
                }
            }
            if (description.length() > 0)
            {
                result.append(description).append("\n\n");
            }
            for (Object item : items)
            {
                result.append(item).append('\n');
            }
            return result.toString();
        }
    }
}
