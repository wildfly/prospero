package org.wildfly.prospero.galleon;

import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.wildfly.channel.Repository;

/**
 * Configures repository authentication when creating RemoteRepository instance.
 */
public class RepositoryAuthenticationHandler implements RepositoryConversionHandler {

    private final Settings mavenSettings;

    public RepositoryAuthenticationHandler(Settings mavenSettings) {
        this.mavenSettings = mavenSettings;
    }

    @Override
    public void accept(Repository repository, RemoteRepository.Builder builder) {
        if (mavenSettings != null) {
            Server server = mavenSettings.getServer(repository.getId());
            if (server != null && (server.getUsername() != null || server.getPassword() != null)) {
                Authentication authentication = new AuthenticationBuilder()
                        .addUsername(server.getUsername())
                        .addPassword(server.getPassword())
                        .build();
                builder.setAuthentication(authentication);
            }
        }
    }
}
