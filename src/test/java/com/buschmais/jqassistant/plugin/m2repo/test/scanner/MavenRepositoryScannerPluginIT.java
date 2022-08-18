package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class MavenRepositoryScannerPluginIT extends AbstractMavenRepositoryIT {

    @Test
    void scan() throws IOException, URISyntaxException {
        Map<String, Object> scannerProperties = new HashMap<>();
        scannerProperties.put("m2repo.artifacts.scan", "true");
        scan(scannerProperties);

        store.beginTransaction();
        Long countJarNodes = store.executeQuery("MATCH (n:Maven:Artifact:Jar) RETURN count(n) as nodes").getSingleResult().get("nodes", Long.class);
        assertThat("Number of jar nodes is wrong.", countJarNodes, equalTo(40l));

        Map<String, Object> params = new HashMap<>();
        params.put("repoUrl", TEST_REPOSITORY_URL);
        MavenRepositoryDescriptor repositoryDescriptor = store.executeQuery("MATCH (r:Maven:Repository{url:$repoUrl}) RETURN r", params).getSingleResult()
                .get("r", MavenRepositoryDescriptor.class);
        assertThat(repositoryDescriptor, notNullValue());
        assertThat(repositoryDescriptor.getUrl(), equalTo(TEST_REPOSITORY_URL));
        assertThat(repositoryDescriptor.getContainedModels(), hasSize(9));
        store.commitTransaction();
    }

    @Test
    void scanWithCustomDirectory() throws IOException, URISyntaxException {
        File customDirectory = new File("target/custom/m2repo");
        FileUtils.deleteDirectory(customDirectory);
        Map<String, Object> scannerProperties = new HashMap<>();
        scannerProperties.put("m2repo.directory", customDirectory.getAbsolutePath());
        scan(scannerProperties);
        assertThat(new File(customDirectory, "localhost/9095").exists(), equalTo(true));
    }

    private void scan(Map<String, Object> scannerProperties) throws IOException, URISyntaxException {
        try {
            startServer("1");
            getScanner(scannerProperties).scan(new URI(TEST_REPOSITORY_URL), TEST_REPOSITORY_URL, MavenScope.REPOSITORY);
        } finally {
            stopServer();
        }
    }

}
