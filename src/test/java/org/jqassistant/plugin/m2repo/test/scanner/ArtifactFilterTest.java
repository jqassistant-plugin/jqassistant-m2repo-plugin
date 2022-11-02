package org.jqassistant.plugin.m2repo.test.scanner;

import com.buschmais.jqassistant.plugin.maven3.api.artifact.ArtifactFilter;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ArtifactFilterTest {

    @Test
    void includes() {
        ArtifactFilter artifactFilter = new ArtifactFilter("com.buschmais.jqassistant.*:*:jar", null);
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", null)), equalTo(true));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(false));
    }

    @Test
    void includesClassifier() {
        ArtifactFilter artifactFilter = new ArtifactFilter("com.buschmais.jqassistant.*:*:jar:sources", null);
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", "sources")),
                equalTo(true));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", "tests")), equalTo(false));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(false));
    }

    @Test
    void excludes() {
        ArtifactFilter artifactFilter = new ArtifactFilter(null, "com.buschmais.jqassistant.*:*:jar");
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", null)), equalTo(false));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(true));
    }

    @Test
    void excludesClassifier() {
        ArtifactFilter artifactFilter = new ArtifactFilter(null, "com.buschmais.jqassistant.*:*:jar:sources");
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", "sources")),
                equalTo(false));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", "tests")), equalTo(true));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(true));
    }

    @Test
    void includesAndExcludes() {
        ArtifactFilter artifactFilter = new ArtifactFilter("com.buschmais.jqassistant.*:*:jar", "com.buschmais.jqassistant.*:*:zip");
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", null)), equalTo(true));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(false));
    }

    @Test
    void noFilter() {
        ArtifactFilter artifactFilter = new ArtifactFilter(null, null);
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "jar", null)), equalTo(true));
        assertThat(artifactFilter.match(getArtifact("com.buschmais.jqassistant.plugin", "jqassistant.plugin.m2repo", "1.0.0", "zip", null)), equalTo(true));
    }

    private Artifact getArtifact(String groupId, String artifactId, String version, String type, String classifier) {
        return new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version), null, type, classifier, new DefaultArtifactHandler());
    }

}
