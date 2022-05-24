package com.telenav.cactus.maven.cli;

import com.mastfrog.util.preconditions.Checks;
import com.telenav.cactus.maven.util.CliCommand;
import com.telenav.cactus.maven.util.ProcessResultConverter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 *
 * @author Tim Boudreau
 */
public final class GitCommand<T> extends CliCommand<T>
{

    private final Path workingDir;
    private final String[] args;

    public GitCommand(ProcessResultConverter<T> resultCreator, String... args)
    {
        this(resultCreator, null, args);
    }

    public GitCommand(ProcessResultConverter<T> resultCreator, Path workingDir, String... args)
    {
        super("git", resultCreator);
        this.workingDir = workingDir;
        this.args = Checks.notNull("args", args);
    }

    public GitCommand<T> withWorkingDir(Path dir)
    {
        return new GitCommand<>(resultCreator, dir, args);
    }

    @Override
    protected Optional<Path> workingDirectory()
    {
        return Optional.ofNullable(workingDir);
    }

    @Override
    protected void validate()
    {
        if (workingDir == null)
        {
            throw new IllegalStateException("Command is a template. Use " + "withWorkingDir() to get an instance that has " + "somewhere to run.");
        }
    }

    @Override
    protected void configureArguments(List<String> list)
    {
        list.addAll(Arrays.asList(args));
    }

}
