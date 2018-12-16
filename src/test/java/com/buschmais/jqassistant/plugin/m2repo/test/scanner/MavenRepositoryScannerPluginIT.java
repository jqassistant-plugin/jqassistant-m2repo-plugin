package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

public class MavenRepositoryScannerPluginIT extends AbstractMavenRepositoryTest {

    @Test
    public void testMavenRepoScanner() throws IOException {
        try {
            startServer("1");
            store.beginTransaction();
            getScanner(getScannerProperties()).scan(new URL(TEST_REPOSITORY_URL), TEST_REPOSITORY_URL, MavenScope.REPOSITORY);

            Long countJarNodes = store.executeQuery("MATCH (n:Maven:Artifact:Jar) RETURN count(n) as nodes").getSingleResult().get("nodes", Long.class);
            assertThat("Number of jar nodes is wrong.", countJarNodes, equalTo(40l));

            Map<String, Object> params = new HashMap<>();
            params.put("repoUrl", TEST_REPOSITORY_URL);
            MavenRepositoryDescriptor repositoryDescriptor = store.executeQuery("MATCH (r:Maven:Repository{url:{repoUrl}}) RETURN r", params).getSingleResult()
                    .get("r", MavenRepositoryDescriptor.class);
            assertThat(repositoryDescriptor, notNullValue());
            assertThat(repositoryDescriptor.getUrl(), equalTo(TEST_REPOSITORY_URL));
            assertThat(repositoryDescriptor.getContainedModels(), hasSize(9));
        } finally {
            store.commitTransaction();
            stopServer();
        }
    }

    @Test
    public void testMavenRepoScannerWithUpdate() throws IOException {
        try {
            startServer("2");
            store.beginTransaction();
            getScanner(getScannerProperties()).scan(new URL(TEST_REPOSITORY_URL), TEST_REPOSITORY_URL, MavenScope.REPOSITORY);
            Long countArtifactNodes = store.executeQuery("MATCH (:Repository)-[:CONTAINS_POM]->(n:Maven:Pom:Xml) RETURN count(n) as nodes").getSingleResult()
                    .get("nodes", Long.class);
            assertThat("Number of POM nodes is wrong.", countArtifactNodes, equalTo(1L));

            restartServer("3");
            getScanner(getScannerProperties()).scan(new URL(TEST_REPOSITORY_URL), TEST_REPOSITORY_URL, MavenScope.REPOSITORY);

            countArtifactNodes = store.executeQuery("MATCH (:Repository)-[:CONTAINS_POM]->(n:Maven:Pom:Xml) RETURN count(n) as nodes").getSingleResult()
                    .get("nodes", Long.class);
            assertThat("Number of 'POM' nodes is wrong.", countArtifactNodes, equalTo(2L));
        } finally {
            store.commitTransaction();
            stopServer();
        }
    }
}
