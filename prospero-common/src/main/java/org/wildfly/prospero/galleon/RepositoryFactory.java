package org.wildfly.prospero.galleon;

import org.eclipse.aether.repository.RemoteRepository;
import org.wildfly.channel.Repository;

import java.util.List;
import java.util.function.Function;

import static org.wildfly.channel.maven.VersionResolverFactory.DEFAULT_REPOSITORY_POLICY;

/**
 * Converts Wildfly Channel repository instances into Maven RemoteRepository. Takes a list of handlers to configure
 * specific aspects of the RemoteRepository configuration.
 */
public class RepositoryFactory implements Function<Repository, RemoteRepository> {

    private final List<RepositoryConversionHandler> handlers;

    public RepositoryFactory(List<RepositoryConversionHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public RemoteRepository apply(Repository repository) {
        final RemoteRepository.Builder builder = new RemoteRepository.Builder(repository.getId(), "default", repository.getUrl())
                .setPolicy(DEFAULT_REPOSITORY_POLICY);
        handlers.forEach(handler -> handler.accept(repository, builder));
        return builder.build();
    }
}
