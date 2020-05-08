package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.util.function.Consumer;
import java.util.function.Predicate;

import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.Coordinates;
import com.buschmais.jqassistant.plugin.maven3.api.artifact.MavenArtifactHelper;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenArtifactIdDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenGroupIdDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.model.MavenVersionDescriptor;
import com.buschmais.xo.api.ResultIterator;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * A caching resolver for {@link MavenVersionDescriptor}s.
 */
class GAVResolver {

    private final Store store;

    private final MavenRepositoryDescriptor repositoryDescriptor;

    private final String CACHE_KEY_GROUP_ID = GAVResolver.class.getName() + "_GROUP_ID";
    private final String CACHE_KEY_ARTIFACT_ID = GAVResolver.class.getName() + "_ARTIFACT_ID";
    private final String CACHE_KEY_VERSION = GAVResolver.class.getName() + "VERSION";

    /**
     * Constructor.
     *
     * @param store
     * @param repositoryDescriptor
     *            The {@link MavenRepositoryDescriptor}.
     */
    GAVResolver(Store store, MavenRepositoryDescriptor repositoryDescriptor) {
        this.store = store;
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
        return store.<GAV, MavenVersionDescriptor> getCache(CACHE_KEY_VERSION).get(gav, key -> {
            GAV ga = GAV.builder().groupId(coordinates.getGroup()).artifactId(coordinates.getName()).build();
            String versionFQN = coordinates.getGroup() + ":" + coordinates.getName() + ":" + baseVersion;
            return getVersion(store.<GAV, MavenArtifactIdDescriptor> getCache(CACHE_KEY_ARTIFACT_ID).get(ga, gaKey -> {
                String artifactFQN = coordinates.getGroup() + ":" + coordinates.getName();
                return getArtifactId(
                        store.<String, MavenGroupIdDescriptor> getCache(CACHE_KEY_GROUP_ID).get(coordinates.getGroup(), groupId -> getGroupId(groupId)),
                        artifactFQN, coordinates.getName());
            }), versionFQN, baseVersion);
        });
    }

    /**
     * Resolve the {@link MavenGroupIdDescriptor} for the given groupId.
     */
    private MavenGroupIdDescriptor getGroupId(String groupId) {
        return getOrCreate(MavenGroupIdDescriptor.class, groupId, g -> repositoryDescriptor.equals(g.getRepository()), g -> {
            g.setName(groupId);
            g.setRepository(repositoryDescriptor);
        });
    }

    /**
     * Resolve the {@link MavenArtifactIdDescriptor}.
     */
    private MavenArtifactIdDescriptor getArtifactId(MavenGroupIdDescriptor groupId, String fqn, String name) {
        return getOrCreate(MavenArtifactIdDescriptor.class, fqn, a -> groupId.equals(a.getGroupId()), a -> {
            a.setFullQualifiedName(fqn);
            a.setName(name);
            a.setGroupId(groupId);
        });
    }

    /**
     * Resolve the {@link MavenVersionDescriptor}.
     */
    private MavenVersionDescriptor getVersion(MavenArtifactIdDescriptor artifactId, String fqn, String version) {
        return getOrCreate(MavenVersionDescriptor.class, fqn, v -> artifactId.equals(v.getArtifactId()), v -> {
            v.setFullQualifiedName(fqn);
            v.setName(version);
            v.setArtifactId(artifactId);
        });
    }

    /**
     * Get or create a descriptor of a given type by an indexed property.
     *
     * @param type
     *            The {@link Descriptor} type.
     * @param value
     *            The indexed value.
     * @param matcher
     *            A {@link Predicate} that matches found {@link Descriptor}s.
     * @param onCreate
     *            A {@link Consumer} that takes a created {@link Descriptor}
     *            instance.
     * @param <T>
     *            The {@link Descriptor} type.
     * @return The {@link Descriptor} of the requested type according to the indexed
     *         value and matcher.
     */
    private <T extends Descriptor> T getOrCreate(Class<T> type, String value, Predicate<T> matcher, Consumer<T> onCreate) {
        try (ResultIterator<T> iterator = store.getXOManager().find(type, value).iterator()) {
            while (iterator.hasNext()) {
                T descriptor = iterator.next();
                if (matcher.test(descriptor)) {
                    return descriptor;
                }
            }
        }
        T descriptor = store.create(type);
        onCreate.accept(descriptor);
        return descriptor;
    }

    /**
     * Represents GroupId/ArtifactId/Version coordinates as key.
     */
    @Builder
    @EqualsAndHashCode
    @ToString
    private static final class GAV {

        private String groupId;
        private String artifactId;
        private String version;

    }
}
