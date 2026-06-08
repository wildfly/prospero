package org.wildfly.prospero.galleon;

import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.Test;
import org.wildfly.channel.Repository;

import static org.assertj.core.api.Assertions.assertThat;

public class RepositoryAuthenticationHandlerTest {

    private final RemoteRepository.Builder builder = new RemoteRepository.Builder("test", "default", "...");

    private Settings mavenSettings() {
        Server server = new Server();
        server.setId("test");
        server.setUsername("username");
        server.setPassword("password");

        Settings settings = new Settings();
        settings.addServer(server);

        return settings;
    }

    @Test
    public void testAuthenticationSet() throws Exception {
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(mavenSettings());
        Repository repo = new Repository("test", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication().toString()).isEqualTo("username=username, password=***");
    }

    @Test
    public void testAuthenticationSetForDifferentServerId() throws Exception {
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(mavenSettings());
        Repository repo = new Repository("other", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication()).isNull();
    }

    @Test
    public void testNoMavenSettings() throws Exception {
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(null);
        Repository repo = new Repository("test", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication()).isNull();
    }

    @Test
    public void testPasswordNull() throws Exception {
        Settings settings = mavenSettings();
        settings.getServer("test").setPassword(null);
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(settings);
        Repository repo = new Repository("test", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication().toString()).isEqualTo("username=username");
    }

    @Test
    public void testUsernameNull() throws Exception {
        Settings settings = mavenSettings();
        settings.getServer("test").setUsername(null);
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(settings);
        Repository repo = new Repository("test", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication().toString()).isEqualTo("password=***");
    }

    @Test
    public void testUsernameAndPasswordNull() throws Exception {
        Settings settings = mavenSettings();
        settings.getServer("test").setUsername(null);
        settings.getServer("test").setPassword(null);
        RepositoryAuthenticationHandler handler = new RepositoryAuthenticationHandler(settings);
        Repository repo = new Repository("test", "https://repo");
        handler.accept(repo, builder);

        assertThat(builder.build().getAuthentication()).isNull();
    }
}
