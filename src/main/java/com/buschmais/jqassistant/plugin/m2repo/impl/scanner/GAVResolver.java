package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import com.buschmais.jqassistant.plugin.maven3.api.artifact.Coordinates;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenArtifactHelper;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenVersionDescriptor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * A resolver for {@link MavenVersionDescriptor} which uses a cache.
 */
class GAVResolver {

    private final MavenRepositoryDescriptor repositoryDescriptor;

    private final Cache<GAV, MavenVersionDescriptor> cache = Caffeine.newBuilder().maximumSize(16).build();

    /**
     * Constructor.
     * 
     * @param repositoryDescriptor
     *            The {@link MavenRepositoryDescriptor}.
     */
    GAVResolver(MavenRepositoryDescriptor repositoryDescriptor) {
        this.repositoryDescriptor = repositoryDescriptor;
    }

    /**
     * Resolve the given {@link Coordinates} to a {@link MavenVersionDescriptor}.
     * 
     * @param coordinates
     *            The {@link Coordinates}.
     * @return The {@link MavenVersionDescriptor}.
     */
    public MavenVersionDescriptor resolve(Coordinates coordinates) {
        String baseVersion = MavenArtifactHelper.getBaseVersion(coordinates);
        GAV gav = GAV.builder().groupId(coordinates.getGroup()).artifactId(coordinates.getName()).version(baseVersion).build();
        return cache.get(gav, key -> repositoryDescriptor.resolveVersion(key.getGroupId(), key.getArtifactId(), key.getVersion()));
    }

    @Getter
    @Builder
    @EqualsAndHashCode
    @ToString
    private static final class GAV {

        private String groupId;
        private String artifactId;
        private String version;

    }
}
