package org.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;

import com.buschmais.jqassistant.core.test.plugin.AbstractPluginIT;

import org.apache.commons.io.FileUtils;
import org.javastack.httpd.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractMavenRepositoryIT extends AbstractPluginIT {

    protected static final int REPO_SERVER_PORT = 9095;

    protected static final String TEST_REPOSITORY_URL = "http://localhost:" + REPO_SERVER_PORT;

    private static final String REPOSITORY_DIR_PREFIX = "maven-repository-";

    protected File localRepositoryDirectory;

    private HttpServer httpServer;

    @BeforeEach
    public void createRepo() {
        localRepositoryDirectory = new File("target/m2repo");
        FileUtils.deleteQuietly(localRepositoryDirectory);
    }

    /**
     * Starts a HTTP server as maven repo.
     *
     * @throws IOException
     */
    protected void startServer(String baseDirSuffix) throws IOException {
        File repoDirectory = new File(getClassesDirectory(this.getClass()), REPOSITORY_DIR_PREFIX + baseDirSuffix);
        httpServer = new HttpServer(REPO_SERVER_PORT, repoDirectory.getAbsolutePath());
        httpServer.start();
    }

    /**
     * Stops the HTTP server.
     *
     */
    @AfterEach
    public void stopServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }


    /**
     * Removes the DEFAULT_M2REPO_DIR if existent.
     *
     * @throws IOException
     */
    @AfterEach
    public void clearLocalRepo() throws IOException {
        if (localRepositoryDirectory.exists()) {
            FileUtils.deleteDirectory(localRepositoryDirectory);
        }
    }
}
