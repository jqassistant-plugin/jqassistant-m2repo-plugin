package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.AetherArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResult;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;
import com.buschmais.jqassistant.plugin.maven3.impl.scanner.artifact.MavenArtifactResolver;

import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class ArtifactSearchResultScannerPluginIT extends AbstractMavenRepositoryTest {

    @Test
    public void mavenRepoScanner() throws IOException {
        try {
            startServer("1");
            store.beginTransaction();

            ArtifactInfo info = new ArtifactInfo();
            info.setFieldValue(MAVEN.GROUP_ID, "com.buschmais.xo");
            info.setFieldValue(MAVEN.ARTIFACT_ID, "xo.api");
            info.setFieldValue(MAVEN.VERSION, "0.5.0-SNAPSHOT");
            info.setFieldValue(MAVEN.PACKAGING, "jar");

            Scanner scanner = getScanner(getScannerProperties());

            MavenRepositoryDescriptor repoDescriptor = store.create(MavenRepositoryDescriptor.class);
            ArtifactProvider provider = new AetherArtifactProvider(new URL(TEST_REPOSITORY_URL), repoDescriptor, localRepositoryDirectory);
            assertThat(new File(localRepositoryDirectory, "localhost/" + REPO_SERVER_PORT).exists(), equalTo(true));
            ScannerContext context = scanner.getContext();
            context.push(ArtifactProvider.class, provider);
            context.push(ArtifactResolver.class, new MavenArtifactResolver());
            repoDescriptor.setUrl(TEST_REPOSITORY_URL);
            scanner.scan(new ArtifactSearchResult(Arrays.asList(info)), info.toString(), MavenScope.REPOSITORY);
            context.pop(ArtifactProvider.class);
            context.pop(ArtifactResolver.class);

            Long countJarNodes = store.executeQuery("MATCH (n:Maven:Artifact:Jar) RETURN count(n) as nodes").getSingleResult().get("nodes", Long.class);
            assertThat("Number of jar nodes is wrong.", countJarNodes, equalTo(1L));

            MavenRepositoryDescriptor repositoryDescriptor = store.executeQuery("MATCH (r:Maven:Repository) RETURN r").getSingleResult().get("r",
                    MavenRepositoryDescriptor.class);
            assertThat(repositoryDescriptor, not(nullValue()));
            assertThat(repositoryDescriptor.getContainedModels(), hasSize(1));
        } finally {
            if (store.hasActiveTransaction()) {
                store.rollbackTransaction();
            }
            stopServer();
        }
    }
}
