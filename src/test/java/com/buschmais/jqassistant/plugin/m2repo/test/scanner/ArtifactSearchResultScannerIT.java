package com.buschmais.jqassistant.plugin.m2repo.test.scanner;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;

import com.buschmais.jqassistant.plugin.m2repo.api.model.ArtifactInfoDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenSnapshotDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.AetherArtifactProvider;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResult;
import com.buschmais.jqassistant.plugin.m2repo.impl.scanner.ArtifactSearchResultScanner;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactFilter;
import com.buschmais.jqassistant.plugin.maven3.api.model.*;

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
    private static final String VERSION = "0.5.0-20141126.194537-53";
    private static final String BASE_VERSION = "0.5.0-SNAPSHOT";
    private static final long LAST_MODIFIED = -1;
    private static final String PACKAGING_POM = "pom";

    private ArtifactSearchResultScanner resultScanner;

    private MavenRepositoryDescriptor repositoryDescriptor;

    @BeforeEach
    public void init() throws MalformedURLException {
        store.beginTransaction();
        repositoryDescriptor = store.create(MavenRepositoryDescriptor.class);
        repositoryDescriptor.setUrl(TEST_REPOSITORY_URL);
        store.commitTransaction();
        AetherArtifactProvider artifactProvider = new AetherArtifactProvider(new URL(TEST_REPOSITORY_URL), localRepositoryDirectory);
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
            artifactInfo.setFieldValue(MAVEN.VERSION, VERSION);
            artifactInfo.setFieldValue(MAVEN.PACKAGING, PACKAGING_JAR);
            List<ArtifactInfo> artifactInfos = asList(artifactInfo);

            resultScanner.scan(new ArtifactSearchResult(artifactInfos, artifactInfos.size()), repositoryDescriptor);

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
                    equalTo(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_POM + ":" + VERSION));
            assertThat(((MavenSnapshotDescriptor) model).getLastModified(), equalTo(LAST_MODIFIED));
            // Verify artifact
            List<MavenArtifactDescriptor> containedArtifacts = repositoryDescriptor.getContainedArtifacts();
            assertThat(containedArtifacts, hasSize(1));
            MavenArtifactDescriptor artifact = containedArtifacts.get(0);
            assertThat(artifact.getGroup(), equalTo(GROUP_ID));
            assertThat(artifact.getName(), equalTo(ARTIFACT_ID_XO_API));
            assertThat(artifact.getType(), equalTo(PACKAGING_JAR));
            assertThat(artifact.getVersion(), startsWith(VERSION));
            assertThat(artifact, instanceOf(ArtifactInfoDescriptor.class));
            assertThat(artifact.getFullQualifiedName(), startsWith(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + PACKAGING_JAR + ":" + VERSION));
            assertThat(artifact, instanceOf(MavenSnapshotDescriptor.class));
            assertThat(((MavenSnapshotDescriptor) artifact).getLastModified(), equalTo(LAST_MODIFIED));
            assertThat(model.getDescribes().contains(artifact), equalTo(true));
            // Verify GAV
            List<MavenGroupIdDescriptor> groupdIs = query("MATCH (r:Maven:Repository)-[:CONTAINS_GROUP_ID]->(g:GroupId) RETURN g").getColumn("g");
            assertThat(groupdIs.size(), equalTo(1));
            MavenGroupIdDescriptor groupId = groupdIs.get(0);
            assertThat(groupId.getName(), equalTo(GROUP_ID));
            List<MavenArtifactIdDescriptor> artifactIds = groupId.getArtifactIds();
            assertThat(artifactIds.size(), equalTo(1));
            MavenArtifactIdDescriptor artifactId = artifactIds.get(0);
            assertThat(artifactId.getName(), equalTo(ARTIFACT_ID_XO_API));
            assertThat(artifactId.getFullQualifiedName(), equalTo(GROUP_ID + ":" + ARTIFACT_ID_XO_API));
            List<MavenVersionDescriptor> versions = artifactId.getVersions();
            assertThat(versions.size(), equalTo(1));
            MavenVersionDescriptor version = versions.get(0);
            assertThat(version.getName(), equalTo(BASE_VERSION));
            assertThat(version.getFullQualifiedName(), equalTo(GROUP_ID + ":" + ARTIFACT_ID_XO_API + ":" + BASE_VERSION));
            Set<MavenArtifactDescriptor> artifacts = version.getArtifacts();
            assertThat(artifacts.size(), equalTo(1));
            assertThat(artifacts.stream().findFirst().get(), is(artifact));
        } finally {
            if (store.hasActiveTransaction()) {
                store.rollbackTransaction();
            }
            stopServer();
        }
    }
}
