package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.javastack.httpd.HttpServer;
import org.junit.After;
import org.junit.Before;

import com.buschmais.jqassistant.plugin.common.test.AbstractPluginIT;
import com.buschmais.jqassistant.plugin.common.test.scanner.MapBuilder;

public abstract class AbstractMavenRepositoryTest extends AbstractPluginIT {

    protected static final int REPO_SERVER_PORT = 9095;

    protected static final String TEST_REPOSITORY_URL = "http://localhost:" + REPO_SERVER_PORT;

    private static final String REPOSITORY_DIR_PREFIX = "maven-repository-";

    protected File localRepositoryDirectory;

    private HttpServer httpServer;

    @Before
    public void createRepo() throws IOException {
        localRepositoryDirectory = Files.createTempDirectory("m2repo").toFile();
        localRepositoryDirectory.mkdirs();
    }

    protected Map<String, Object> getScannerProperties() {
        return MapBuilder.<String, Object> create("m2repo.directory", localRepositoryDirectory.getAbsolutePath()).get();
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

    protected void restartServer(String baseDirSuffix) throws IOException {
        stopServer();
        startServer(baseDirSuffix);
    }



    /**
     * Stops the HTTP server.
     *
     * @throws IOException
     */
    @After
    public void stopServer() throws IOException {
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
    @After
    public void clearLocalRepo() throws IOException {
        if (localRepositoryDirectory.exists()) {
            FileUtils.deleteDirectory(localRepositoryDirectory);
        }
    }
}
