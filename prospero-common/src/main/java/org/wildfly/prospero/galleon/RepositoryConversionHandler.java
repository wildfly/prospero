package org.wildfly.prospero.galleon;

import org.eclipse.aether.repository.RemoteRepository;
import org.wildfly.channel.Repository;

import java.util.function.BiConsumer;

/**
 * Handles conversion of the Wildfly Channel Repository instance into Maven RemoteRepository.
 */
public interface RepositoryConversionHandler extends BiConsumer<Repository, RemoteRepository.Builder> {
}
