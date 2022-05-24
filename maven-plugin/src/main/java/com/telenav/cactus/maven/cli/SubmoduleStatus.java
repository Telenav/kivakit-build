package com.telenav.cactus.maven.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents one line of a "git submodule status" command's output showing this
 * status of all submodules. Example:
 * <pre>
 * +76a661a2dcd519a45fcb121ea255145f083eb94d cactus-build (1.3.0-25-g76a661a)
 * </pre>
 *
 * @author Tim Boudreau
 */
public final class SubmoduleStatus
{

    static final Pattern SUBMODULE_STATUS_LINE
            = Pattern.compile("^([ +])([a-f0-9]+)\\s+(\\S+)\\s+\\((\\S+)\\)\\s*$");
    public final boolean modifications;
    public final String commitId;
    public final String modulePath;
    public final String branchOrTagInfo;
    public final Path path;
    public final boolean exists;

    public SubmoduleStatus(boolean modifications, String commitId, String modulePath, String branchOrTagInfo, Path path, boolean exists)
    {
        this.modifications = modifications;
        this.commitId = commitId;
        this.modulePath = modulePath;
        this.branchOrTagInfo = branchOrTagInfo;
        this.path = path;
        this.exists = exists;
    }

    public static Optional<SubmoduleStatus> from(Path root, String line)
    {
        if (line.isBlank())
        {
            return Optional.empty();
        }
        Matcher m = SUBMODULE_STATUS_LINE.matcher(line);
        if (m.find())
        {
            boolean modifications = "+".equals(m.group(1));
            String id = m.group(2);
            String modulePath = m.group(3);
            String info = m.group(4);
            Path dir = root.resolve(modulePath);
            return Optional.of(new SubmoduleStatus(modifications, id,
                    modulePath, info, dir, Files.exists(dir)));
        }
        return Optional.empty();
    }

    public static List<SubmoduleStatus> fromStatusOutput(Path root, String output)
    {
        List<SubmoduleStatus> result = new ArrayList<>();
        for (String line : output.split("\n"))
        {
            from(root, line).ifPresent(result::add);
        }
        return result;
    }

    public Optional<Git> repository()
    {
        return exists ? Optional.of(new Git(path)) : Optional.empty();
    }

    @Override
    public String toString()
    {
        return (modifications ? "+" : " ") + commitId + " " + modulePath
                + " (" + branchOrTagInfo + ") <- " + path + " "
                + (exists ? "ok" : "?");
    }
}
