package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.plugin.m2repo.api.ArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.api.model.LastModifiedDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenSnapshotDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.AetherArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResult;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactResolver;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenPomXmlDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;
import com.buschmais.jqassistant.plugin.maven3.impl.scanner.artifact.MavenArtifactResolver;

import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ArtifactSearchResultScannerPluginIT extends AbstractMavenRepositoryTest {

    private static final String GROUP_ID = "com.buschmais.xo";
    private static final String ARTIFACT_ID_XO_API = "xo.api";
    private static final String PACKAGING_JAR = "jar";
    private static final String VERSION_PREFIX = "0.5.0-SNAPSHOT";
    private static final long LAST_MODIFIED = -1;
    public static final String PACKAGING_POM = "pom";

    @Test
    public void modelAndArtifact() throws IOException {
        Map<String, Object> scannerProperties = getScannerProperties();
        verify(scannerProperties);
    }

    @Test
    public void modelOnly() throws IOException {
        Map<String, Object> scannerProperties = getScannerProperties();
        scannerProperties.put("m2repo.artifacts.scan", "false");
        verify(scannerProperties);
    }

    private void verify(Map<String, Object> scannerProperties) throws IOException {
        try {
            startServer("1");
            store.beginTransaction();

            ArtifactInfo artifactInfo = new ArtifactInfo();
            artifactInfo.setFieldValue(MAVEN.GROUP_ID, GROUP_ID);
            artifactInfo.setFieldValue(MAVEN.ARTIFACT_ID, ARTIFACT_ID_XO_API);
            artifactInfo.setFieldValue(MAVEN.VERSION, "0.5.0-SNAPSHOT");
            artifactInfo.setFieldValue(MAVEN.PACKAGING, PACKAGING_JAR);

            scan(scannerProperties, artifactInfo);

            MavenRepositoryDescriptor repositoryDescriptor = store.executeQuery("MATCH (r:Maven:Repository) RETURN r").getSingleResult().get("r",
                MavenRepositoryDescriptor.class);
            assertThat(repositoryDescriptor, not(nullValue()));
            List<MavenPomXmlDescriptor> containedModels = repositoryDescriptor.getContainedModels();
            assertThat(containedModels, hasSize(1));
            MavenPomXmlDescriptor model = containedModels.get(0);
            assertThat(model.getGroupId(), nullValue());
            assertThat(model.getArtifactId(), equalTo(ARTIFACT_ID_XO_API));
            assertThat(model.getPackaging(), equalTo(PACKAGING_JAR));
            assertThat(model.getVersion(), equalTo(null));
            assertThat(model.getFullQualifiedName(), equalTo(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_POM + ":" + VERSION_PREFIX));
            assertThat(model, instanceOf(MavenSnapshotDescriptor.class));
            assertThat(((MavenSnapshotDescriptor)model).getLastModified(), equalTo(LAST_MODIFIED));

            List<MavenArtifactDescriptor> containedArtifacts = repositoryDescriptor.getContainedArtifacts();
            assertThat(containedArtifacts, hasSize(1));
            MavenArtifactDescriptor artifact = containedArtifacts.get(0);
            assertThat(artifact.getGroup(), equalTo(GROUP_ID));
            assertThat(artifact.getName(), equalTo(ARTIFACT_ID_XO_API));
            assertThat(artifact.getType(), equalTo(PACKAGING_JAR));
            assertThat(artifact.getVersion(), startsWith(VERSION_PREFIX));
            assertThat(artifact, instanceOf(LastModifiedDescriptor.class));
            assertThat(artifact.getFullQualifiedName(), startsWith(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_JAR + ":" + VERSION_PREFIX));
            assertThat(artifact, instanceOf(MavenSnapshotDescriptor.class));
            assertThat(((MavenSnapshotDescriptor)artifact).getLastModified(), equalTo(LAST_MODIFIED));
            assertThat(model.getDescribes().contains(artifact), equalTo(true));
        } finally {
            if (store.hasActiveTransaction()) {
                store.rollbackTransaction();
            }
            stopServer();
        }
    }

    private void scan(Map<String, Object> scannerProperties, ArtifactInfo... artifactInfos) throws MalformedURLException {
        Scanner scanner = getScanner(scannerProperties);
        MavenRepositoryDescriptor repoDescriptor = store.create(MavenRepositoryDescriptor.class);
        ArtifactProvider provider = new AetherArtifactProvider(new URL(TEST_REPOSITORY_URL), repoDescriptor, localRepositoryDirectory);
        ScannerContext context = scanner.getContext();
        context.push(ArtifactProvider.class, provider);
        context.push(ArtifactResolver.class, new MavenArtifactResolver());
        repoDescriptor.setUrl(TEST_REPOSITORY_URL);
        scanner.scan(new ArtifactSearchResult(asList(artifactInfos)), "", MavenScope.REPOSITORY);
        assertThat("Expecting a directory for the local Maven repository.", new File(localRepositoryDirectory, "localhost/" + REPO_SERVER_PORT).exists(),
            equalTo(true));
        context.pop(ArtifactProvider.class);
        context.pop(ArtifactResolver.class);
    }
}
