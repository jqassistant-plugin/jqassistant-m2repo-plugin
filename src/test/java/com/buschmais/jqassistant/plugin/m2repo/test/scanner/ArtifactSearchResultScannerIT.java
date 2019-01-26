package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import com.buschmais.jqassistant.plugin.m2repo.api.model.ArtifactInfoDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenSnapshotDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.AetherArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactFilter;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResult;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResultScanner;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenPomXmlDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;

import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ArtifactSearchResultScannerIT extends AbstractMavenRepositoryIT {

    private static final String GROUP_ID = "com.buschmais.xo";
    private static final String ARTIFACT_ID_XO_API = "xo.api";
    private static final String PACKAGING_JAR = "jar";
    private static final String VERSION_PREFIX = "0.5.0-SNAPSHOT";
    private static final long LAST_MODIFIED = -1;
    private static final String PACKAGING_POM = "pom";

    private ArtifactSearchResultScanner resultScanner;

    @BeforeEach
    public void init() throws MalformedURLException {
        store.beginTransaction();
        MavenRepositoryDescriptor mavenRepositoryDescriptor = store.create(MavenRepositoryDescriptor.class);
        mavenRepositoryDescriptor.setUrl(TEST_REPOSITORY_URL);
        store.commitTransaction();
        AetherArtifactProvider artifactProvider = new AetherArtifactProvider(new URL(TEST_REPOSITORY_URL), mavenRepositoryDescriptor, localRepositoryDirectory);
        resultScanner = new ArtifactSearchResultScanner(getScanner(), artifactProvider, new ArtifactFilter(null, null), true, true);
    }

    @Test
    public void modelAndArtifact() throws IOException {
        verify();
    }

    @Test
    public void modelOnly() throws IOException {
        verify();
    }

    private void verify() throws IOException {
        try {
            startServer("1");
            store.beginTransaction();
            ArtifactInfo artifactInfo = new ArtifactInfo();
            artifactInfo.setFieldValue(MAVEN.GROUP_ID, GROUP_ID);
            artifactInfo.setFieldValue(MAVEN.ARTIFACT_ID, ARTIFACT_ID_XO_API);
            artifactInfo.setFieldValue(MAVEN.VERSION, "0.5.0-SNAPSHOT");
            artifactInfo.setFieldValue(MAVEN.PACKAGING, PACKAGING_JAR);

            resultScanner.scan(new ArtifactSearchResult(asList(artifactInfo)));

            assertThat("Expecting a directory for the local Maven repository.", new File(localRepositoryDirectory, "localhost/" + REPO_SERVER_PORT).exists(),
                    equalTo(true));
            // Verify model
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
            assertThat(model, instanceOf(MavenSnapshotDescriptor.class));
            assertThat(((MavenSnapshotDescriptor) model).getFullQualifiedName(),
                    equalTo(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_POM + ":" + VERSION_PREFIX));
            assertThat(((MavenSnapshotDescriptor) model).getLastModified(), equalTo(LAST_MODIFIED));
            // Verify artifact
            List<MavenArtifactDescriptor> containedArtifacts = repositoryDescriptor.getContainedArtifacts();
            assertThat(containedArtifacts, hasSize(1));
            MavenArtifactDescriptor artifact = containedArtifacts.get(0);
            assertThat(artifact.getGroup(), equalTo(GROUP_ID));
            assertThat(artifact.getName(), equalTo(ARTIFACT_ID_XO_API));
            assertThat(artifact.getType(), equalTo(PACKAGING_JAR));
            assertThat(artifact.getVersion(), startsWith(VERSION_PREFIX));
            assertThat(artifact, instanceOf(ArtifactInfoDescriptor.class));
            assertThat(artifact.getFullQualifiedName(), startsWith(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_JAR + ":" + VERSION_PREFIX));
            assertThat(artifact, instanceOf(MavenSnapshotDescriptor.class));
            assertThat(((MavenSnapshotDescriptor) artifact).getLastModified(), equalTo(LAST_MODIFIED));
            assertThat(model.getDescribes().contains(artifact), equalTo(true));
        } finally {
            if (store.hasActiveTransaction()) {
                store.rollbackTransaction();
            }
            stopServer();
        }
    }
}
